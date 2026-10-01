"""The live preview's text, from three sources in descending reliability: cleaned chunks, then the
recognizer's text for slices not yet cleaned, then the streaming preview model's words for the
audio no slice has covered yet.
"""

import json
import threading
import time
from pathlib import Path

from stt_button.asr import join_slices


def words_from_tokens(tokens: list[str], timestamps: list[float]) -> list[tuple[str, float]]:
    """Group streaming BPE tokens into words; a token that starts with a space starts a word."""
    words: list[list] = []
    for token, t in zip(tokens, timestamps):
        if token.startswith(" ") or not words:
            words.append([token.strip(), t])
        else:
            words[-1][0] += token
    return [(w, t) for w, t in words if w]


class Bubble:
    def __init__(self):
        self.hypothesis: list[tuple[str, float]] = []
        self.slices: list[str] = []
        self.covered_s = 0.0
        self.chunks: list[tuple[str, str]] = []

    def preview(self, words: list[tuple[str, float]]) -> None:
        self.hypothesis = list(words)

    def slice_landed(self, end_s: float, text: str) -> None:
        self.slices.append(text)
        self.covered_s = max(self.covered_s, end_s)

    def chunk_cleaned(self, raw: str, cleaned: str) -> None:
        self.chunks.append((raw, cleaned))

    def text(self) -> tuple[str, str]:
        """(settled, preview): cleaned chunks and then raw sentences, and the preview words after
        the last landed slice."""
        raw = join_slices(self.slices)
        position, blocks = 0, []
        for chunk_raw, cleaned in self.chunks:
            found = raw.find(chunk_raw, position)
            if found < 0:
                continue
            position = found + len(chunk_raw)
            if cleaned:
                blocks.append(cleaned)
        remainder = raw[position:].strip()
        if remainder:
            blocks.append(remainder)
        preview = " ".join(w for w, t in self.hypothesis if t >= self.covered_s)
        return "\n\n".join(blocks), preview


class SharedBubble(Bubble):
    """A Bubble the worker and the preview thread both feed, rewritten atomically to a file for the
    helper to draw. Preview updates are written at most every min_interval_s."""

    def __init__(self, path: Path, min_interval_s: float = 0.15):
        super().__init__()
        self.path = path
        self.min_interval_s = min_interval_s
        self._lock = threading.Lock()
        self._written: tuple[str, str] | None = None
        self._last_write = 0.0

    def preview(self, words):
        with self._lock:
            super().preview(words)
            self._write(throttle=True)

    def slice_landed(self, end_s, text):
        with self._lock:
            super().slice_landed(end_s, text)
            self._write()

    def chunk_cleaned(self, raw, cleaned):
        with self._lock:
            super().chunk_cleaned(raw, cleaned)
            self._write()

    def flush(self) -> None:
        with self._lock:
            self._write()

    def _write(self, throttle: bool = False) -> None:
        now = time.monotonic()
        if throttle and now - self._last_write < self.min_interval_s:
            return
        settled, preview = self.text()
        if (settled, preview) == self._written:
            return
        partial = self.path.with_suffix(".tmp")
        partial.write_text(json.dumps({"settled": settled, "preview": preview}, ensure_ascii=False))
        partial.replace(self.path)
        self._written, self._last_write = (settled, preview), now
