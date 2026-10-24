"""One take's pipeline as the phone runs it: slices are recognized as the audio grows, and the
chunks that close are cleaned straight away, so only the last slice and the last chunk wait for
the stop.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass

import numpy as np

from stt_button.asr import (SAMPLE_RATE, TRAILING_MARK, Slicer, context_audio, continue_fragment, join_slices,
                            settled_mark)
from stt_button.cleaner import (casual, join_cleaned, live_chunks, matched_cue, skips_cleaner, strip_hidden,
                                trim_message_end, unresolved_corrections, untrusted_clean, without_sign_off)
from stt_button.spoken import keep_spoken_ending, spoken_punctuation


@dataclass
class LiveResult:
    raw: str
    cleaned: str
    chunks: int
    clean_mode: str
    asr_ms_after_stop: int
    clean_ms_after_stop: int
    idle_cleans: int = 0
    idle_hits: int = 0
    cue: str | None = None
    context_decodes: int = 0
    context_fallbacks: int = 0
    drifts: int = 0
    corrections: int = 0
    corrections_kept: int = 0
    rejects: int = 0


IDLE_POLL_S = 0.05


def _ms(since: float) -> int:
    return int((time.monotonic() - since) * 1000)


class LiveTake:
    """recognizer.recognize(samples) -> text; get_cleaner() -> a cleaner with count_tokens and
    clean_chunk, called at the first clean so the caller can still be loading it."""

    def __init__(self, recognizer, get_cleaner: Callable[[], object], cfg: dict, bubble=None):
        self.bubble = bubble
        self.recognizer = recognizer
        self.get_cleaner = get_cleaner
        self.cfg = cfg
        self.slicer = Slicer.from_config(cfg)
        self.texts: list[str] = []
        self.cleaned_chunks: dict[str, str] = {}
        self.idle_chunks: set[str] = set()
        self.idle_cleans = 0
        self._pending: list[tuple[int, int]] = []
        self.asr_ms = 0
        self.context_decodes = 0
        self.context_fallbacks = 0
        self.drifts = 0
        self.rejects = 0
        self.clean_ms = 0
        self._stop_asr_ms = self._stop_clean_ms = 0

    @property
    def raw(self) -> str:
        return join_slices(self.texts)

    def feed(self, samples: np.ndarray) -> None:
        slices, self._pending = self._pending + self.slicer.feed(samples), []
        for start, end in slices:
            self._recognize(start, end)
            self._clean_closed(final=False)

    def idle_step(self, poll: Callable[[], np.ndarray], halted: Callable[[], bool] | None = None) -> bool:
        """While the user is paused and every slice is recognized, clean the chunks the stop would
        need, so a stop during the pause has nothing left to wait for. poll returns the audio that
        arrived since the last read; the first non-silent window cancels the clean in progress and
        discards its output. halted(), when set, is read on that same poll and ends the pass before
        the next chunk; a clean that finished is kept, so the stop still finds it cached.
        Returns whether any generation was started."""
        slicer = self.slicer
        spoken = slicer.last_speech_end
        if not slicer.paused or self._pending or slicer.start < spoken or skips_cleaner(self.raw, self.cfg):
            return False
        cleaner = self.get_cleaner()
        chunks = [c for c in live_chunks(self.raw, cleaner.count_tokens, self.cfg["chunk_min_tokens"],
                                         self.cfg["chunk_max_tokens"], final=True,
                                         cues=self.cfg["self_correction_cues"])
                  if c not in self.cleaned_chunks]
        last_poll = 0.0
        stop_seen = False

        def speech_resumed() -> bool:
            nonlocal last_poll, stop_seen
            if time.monotonic() - last_poll >= IDLE_POLL_S:
                last_poll = time.monotonic()
                self._pending += slicer.feed(poll())
                if halted is not None and halted():
                    stop_seen = True
            return slicer.last_speech_end != spoken

        started_any = False
        for chunk in chunks:
            if stop_seen or (halted is not None and halted()):
                break
            started_any = True
            self.idle_cleans += 1
            began = time.monotonic()
            cleaned = cleaner.clean_chunk(chunk, speech_resumed)
            self.clean_ms += _ms(began)
            if cleaned is None:
                break
            self._keep(chunk, cleaned[0])
            self.idle_chunks.add(chunk)
            self._announce(chunk)
            if stop_seen:
                break
        return started_any

    def mark_stop(self) -> None:
        self._stop_asr_ms, self._stop_clean_ms = self.asr_ms, self.clean_ms

    def finish(self) -> LiveResult:
        for start, end in self._pending + self.slicer.finish():
            self._recognize(start, end)
        self._pending = []
        raw = self.raw
        if skips_cleaner(raw, self.cfg):
            return LiveResult(raw, trim_message_end(casual(without_sign_off(spoken_punctuation(raw)), self.cfg)), 0, "raw", self.asr_ms - self._stop_asr_ms, 0,
                              context_decodes=self.context_decodes, context_fallbacks=self.context_fallbacks)
        chunks = self._clean_closed(final=True)
        outputs = [self.cleaned_chunks[c] for c in chunks]
        cleaned = join_cleaned(chunks, outputs, self.cfg["self_correction_cues"])
        return LiveResult(raw, trim_message_end(casual(without_sign_off(cleaned), self.cfg)), len(chunks), "chunks", self.asr_ms - self._stop_asr_ms,
                          self.clean_ms - self._stop_clean_ms, self.idle_cleans,
                          sum(c in self.idle_chunks for c in chunks), matched_cue(raw, self.cfg["self_correction_cues"]),
                          self.context_decodes, self.context_fallbacks, self.drifts,
                          *unresolved_corrections(chunks, outputs, self.cfg["self_correction_cues"]),
                          rejects=self.rejects)

    def _recognize(self, start: int, end: int) -> None:
        started = time.monotonic()
        self.texts.append(strip_hidden(self._slice_text(start, end)))
        self.asr_ms += _ms(started)
        if self.bubble:
            self.bubble.slice_landed(end / SAMPLE_RATE, self.texts[-1])

    def _slice_text(self, start: int, end: int) -> str:
        """A slice after a pause is decoded with the speech before it, so the recognizer judges the
        sentence break and the capital from the words around it; the previous slice's closing
        punctuation is then the one the context decode gave it."""
        before, after = self.slicer.speech_before(start), self.slicer.speech_after(start)
        if not self.texts or before is None or after is None or after >= end:
            return self._without_context(self.recognizer.recognize(self.slicer.samples[start:end]))
        audio, junction_s = context_audio(self.slicer.samples, before, after, end, self.cfg["context_s"],
                                          self.cfg["junction_squeeze_s"])
        self.context_decodes += 1
        mark, text = self.recognizer.recognize_after(audio, junction_s)
        if not text:
            self.context_fallbacks += 1
            return self._without_context(self.recognizer.recognize(self.slicer.samples[start:end]))
        if self.texts[-1]:
            own = TRAILING_MARK.search(self.texts[-1])
            self.texts[-1] = TRAILING_MARK.sub("", self.texts[-1]) + settled_mark(own.group() if own else "", mark)
        return text

    def _without_context(self, text: str) -> str:
        if not text:
            return text
        for index in range(len(self.texts) - 1, -1, -1):
            if self.texts[index]:
                self.texts[index], text = continue_fragment(self.texts[index], text)
                break
        return text

    def _clean_closed(self, final: bool) -> list[str]:
        raw = self.raw
        if skips_cleaner(raw, self.cfg):
            return []
        cleaner = self.get_cleaner()
        chunks = live_chunks(raw, cleaner.count_tokens, self.cfg["chunk_min_tokens"],
                             self.cfg["chunk_max_tokens"], final, cues=self.cfg["self_correction_cues"])
        for chunk in chunks:
            if chunk not in self.cleaned_chunks:
                started = time.monotonic()
                self._keep(chunk, cleaner.clean_chunk(chunk)[0])
                self.clean_ms += _ms(started)
                self._announce(chunk)
        return chunks

    def _keep(self, chunk: str, cleaned: str) -> None:
        """A clean that swapped a name or number for another, or that untrusted_clean otherwise rejects,
        is replaced by the chunk as heard."""
        reason = "empty" if not cleaned.strip() else untrusted_clean(chunk, cleaned)
        if reason == "drift":
            self.drifts += 1
        elif reason:
            self.rejects += 1
        if reason:
            cleaned = spoken_punctuation(chunk)
        self.cleaned_chunks[chunk] = keep_spoken_ending(chunk, cleaned)

    def _announce(self, chunk: str) -> None:
        if self.bubble:
            self.bubble.chunk_cleaned(chunk, self.cleaned_chunks[chunk])
