"""One take's pipeline as the phone runs it: slices are recognized as the audio grows, and the
chunks that close are cleaned straight away, so only the last slice and the last chunk wait for
the stop.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass

import numpy as np

from stt_button.asr import SAMPLE_RATE, Slicer, join_slices
from stt_button.cleaner import casual, live_chunks, matched_cue, skips_cleaner


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

    def idle_step(self, poll: Callable[[], np.ndarray]) -> bool:
        """While the user is paused and every slice is recognized, clean the chunks the stop would
        need, so a stop during the pause has nothing left to wait for. poll returns the audio that
        arrived since the last read; the first non-silent window cancels the clean in progress and
        discards its output. Returns whether any generation was started."""
        slicer = self.slicer
        spoken = slicer.last_speech_end
        if not slicer.paused or self._pending or slicer.start < spoken or skips_cleaner(self.raw, self.cfg):
            return False
        cleaner = self.get_cleaner()
        chunks = [c for c in live_chunks(self.raw, cleaner.count_tokens, self.cfg["chunk_min_tokens"],
                                         self.cfg["chunk_max_tokens"], final=True)
                  if c not in self.cleaned_chunks]
        last_poll = 0.0

        def speech_resumed() -> bool:
            nonlocal last_poll
            if time.monotonic() - last_poll >= IDLE_POLL_S:
                last_poll = time.monotonic()
                self._pending += slicer.feed(poll())
            return slicer.last_speech_end != spoken

        for chunk in chunks:
            self.idle_cleans += 1
            started = time.monotonic()
            cleaned = cleaner.clean_chunk(chunk, speech_resumed)
            self.clean_ms += _ms(started)
            if cleaned is None:
                break
            self.cleaned_chunks[chunk] = cleaned[0]
            self.idle_chunks.add(chunk)
            self._announce(chunk)
        return bool(chunks)

    def mark_stop(self) -> None:
        self._stop_asr_ms, self._stop_clean_ms = self.asr_ms, self.clean_ms

    def finish(self) -> LiveResult:
        for start, end in self._pending + self.slicer.finish():
            self._recognize(start, end)
        self._pending = []
        raw = self.raw
        if skips_cleaner(raw, self.cfg):
            return LiveResult(raw, casual(raw, self.cfg), 0, "raw", self.asr_ms - self._stop_asr_ms, 0)
        chunks = self._clean_closed(final=True)
        cleaned = "\n\n".join(o for o in (self.cleaned_chunks[c] for c in chunks) if o)
        return LiveResult(raw, casual(cleaned, self.cfg), len(chunks), "chunks", self.asr_ms - self._stop_asr_ms,
                          self.clean_ms - self._stop_clean_ms, self.idle_cleans,
                          sum(c in self.idle_chunks for c in chunks), matched_cue(raw, self.cfg["self_correction_cues"]))

    def _recognize(self, start: int, end: int) -> None:
        started = time.monotonic()
        self.texts.append(self.recognizer.recognize(self.slicer.samples[start:end]))
        self.asr_ms += _ms(started)
        if self.bubble:
            self.bubble.slice_landed(end / SAMPLE_RATE, self.texts[-1])

    def _clean_closed(self, final: bool) -> list[str]:
        raw = self.raw
        if skips_cleaner(raw, self.cfg):
            return []
        cleaner = self.get_cleaner()
        chunks = live_chunks(raw, cleaner.count_tokens, self.cfg["chunk_min_tokens"],
                             self.cfg["chunk_max_tokens"], final)
        for chunk in chunks:
            if chunk not in self.cleaned_chunks:
                started = time.monotonic()
                self.cleaned_chunks[chunk] = cleaner.clean_chunk(chunk)[0]
                self.clean_ms += _ms(started)
                self._announce(chunk)
        return chunks

    def _announce(self, chunk: str) -> None:
        if self.bubble:
            self.bubble.chunk_cleaned(chunk, self.cleaned_chunks[chunk])
