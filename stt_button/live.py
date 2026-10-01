"""One take's pipeline as the phone runs it: slices are recognized as the audio grows, and the
chunks that close are cleaned straight away, so only the last slice and the last chunk wait for
the stop.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass

import numpy as np

from stt_button.asr import Slicer, join_slices
from stt_button.cleaner import live_chunks, skips_cleaner


@dataclass
class LiveResult:
    raw: str
    cleaned: str
    chunks: int
    clean_mode: str
    asr_ms_after_stop: int
    clean_ms_after_stop: int


def _ms(since: float) -> int:
    return int((time.monotonic() - since) * 1000)


class LiveTake:
    """recognizer.recognize(samples) -> text; get_cleaner() -> a cleaner with count_tokens and
    clean_chunk, called at the first clean so the caller can still be loading it."""

    def __init__(self, recognizer, get_cleaner: Callable[[], object], cfg: dict):
        self.recognizer = recognizer
        self.get_cleaner = get_cleaner
        self.cfg = cfg
        self.slicer = Slicer.from_config(cfg)
        self.texts: list[str] = []
        self.cleaned_chunks: dict[str, str] = {}
        self.asr_ms = 0
        self.clean_ms = 0
        self._stop_asr_ms = self._stop_clean_ms = 0

    @property
    def raw(self) -> str:
        return join_slices(self.texts)

    def feed(self, samples: np.ndarray) -> None:
        for start, end in self.slicer.feed(samples):
            self._recognize(start, end)
            self._clean_closed(final=False)

    def mark_stop(self) -> None:
        self._stop_asr_ms, self._stop_clean_ms = self.asr_ms, self.clean_ms

    def finish(self) -> LiveResult:
        for start, end in self.slicer.finish():
            self._recognize(start, end)
        raw = self.raw
        if skips_cleaner(raw, self.cfg):
            return LiveResult(raw, raw, 0, "raw", self.asr_ms - self._stop_asr_ms, 0)
        chunks = self._clean_closed(final=True)
        cleaned = "\n\n".join(o for o in (self.cleaned_chunks[c] for c in chunks) if o)
        return LiveResult(raw, cleaned, len(chunks), "chunks", self.asr_ms - self._stop_asr_ms,
                          self.clean_ms - self._stop_clean_ms)

    def _recognize(self, start: int, end: int) -> None:
        started = time.monotonic()
        self.texts.append(self.recognizer.recognize(self.slicer.samples[start:end]))
        self.asr_ms += _ms(started)

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
        return chunks
