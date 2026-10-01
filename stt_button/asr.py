import bisect
import time
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from stt_button import config

SAMPLE_RATE = 16000
CUT_WINDOW_S = 0.1
NOISE_FLOOR_DBFS = -70


@dataclass
class RawTranscript:
    text: str
    elapsed_ms: int


def read_wav(path: str | Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        shape = (w.getframerate(), w.getnchannels(), w.getsampwidth())
        if shape != (SAMPLE_RATE, 1, 2):
            raise ValueError(f"{path}: expected 16000 Hz mono 16-bit, got {shape}")
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return pcm.astype(np.float32) / 32768


class Slicer:
    """Cuts recognizer slices from audio that may still be growing.

    A slice ends at the centre of a pause once it holds slice_min_s of audio and has had speech,
    when pause_s is set (design § Pipeline). A slice that reaches slice_max_s without a pause is
    cut at the centre of the quietest CUT_WINDOW_S window within its final search_s, so a word is
    not split when a gap is available. Each cut needs only the audio up to the point it is made,
    so feeding audio in pieces gives the same cuts as feeding it whole.

    Window energies are exact integer sums of squared int16 samples, so the silence test gives the
    same answer in every language that implements it.
    """

    def __init__(self, slice_max_s: float, search_s: float, pause_s: float | None = None,
                 slice_min_s: float = 2.0, pause_margin_db: float = 10.0):
        self.max_len = int(slice_max_s * SAMPLE_RATE)
        self.search = int(search_s * SAMPLE_RATE)
        self.win = int(CUT_WINDOW_S * SAMPLE_RATE)
        self.pause_windows = None if pause_s is None else round(pause_s / CUT_WINDOW_S)
        self.min_len = int(slice_min_s * SAMPLE_RATE)
        self.margin = 10 ** (pause_margin_db / 10)
        self.floor_min = self.win * 32768 ** 2 * 10 ** (NOISE_FLOOR_DBFS / 10)
        self.samples = np.zeros(0, dtype=np.float32)
        self.start = 0
        self._energies = 0
        self._sorted: list[int] = []
        self._last_speech: int | None = None

    @classmethod
    def from_config(cls, cfg: dict) -> "Slicer":
        return cls(cfg["slice_max_s"], cfg["slice_search_s"], cfg["pause_s"], cfg["slice_min_s"],
                   cfg["pause_margin_db"])

    @property
    def paused(self) -> bool:
        """Speech has been heard and the last pause_s of audio is silent."""
        return (self.pause_windows is not None and self._last_speech is not None
                and self._energies - 1 - self._last_speech >= self.pause_windows)

    @property
    def last_speech_end(self) -> int | None:
        """Sample offset where the last non-silent window ended."""
        return None if self._last_speech is None else (self._last_speech + 1) * self.win

    def feed(self, chunk: np.ndarray) -> list[tuple[int, int]]:
        """Append audio; return the slices it completed, as (start, end) sample offsets."""
        self.samples = np.concatenate([self.samples, chunk.astype(np.float32)])
        done: list[tuple[int, int]] = []
        while self.pause_windows is not None and (self._energies + 1) * self.win <= len(self.samples):
            self._classify_next_window()
            self._cut_at((self._energies) * self.win, done)
        while len(self.samples) - self.start > self.max_len:
            done.append(self._hard_cut())
        return done

    def finish(self) -> list[tuple[int, int]]:
        """The final slice, once no more audio will arrive."""
        return [(self.start, len(self.samples))] if len(self.samples) > self.start else []

    def _classify_next_window(self) -> None:
        a = self._energies * self.win
        pcm = np.rint(self.samples[a:a + self.win].astype(np.float64) * 32768).astype(np.int64)
        energy = int(np.square(pcm).sum())
        bisect.insort(self._sorted, energy)
        floor = max(self._sorted[(len(self._sorted) - 1) // 10], self.floor_min)
        if energy >= floor * self.margin:
            self._last_speech = self._energies
        self._energies += 1

    def _cut_at(self, boundary: int, done: list[tuple[int, int]]) -> None:
        while True:
            if boundary - self.start > self.max_len:
                done.append(self._hard_cut())
                continue
            cut = self._pause_cut(boundary)
            if cut is None:
                return
            done.append((self.start, cut))
            self.start = cut

    def _pause_cut(self, boundary: int) -> int | None:
        speech = self._last_speech
        if speech is None or speech < -(-self.start // self.win) or boundary - self.start < self.min_len:
            return None
        if self._energies - 1 - speech < self.pause_windows:
            return None
        return ((speech + 1) * self.win + boundary) // 2

    def _hard_cut(self) -> tuple[int, int]:
        hard_end = self.start + self.max_len
        lo = hard_end - self.search
        region = self.samples[lo:hard_end].astype(np.float64) ** 2
        energy = np.concatenate([[0.0], np.cumsum(region)])
        window_energy = energy[self.win:] - energy[:-self.win]
        cut = lo + int(np.argmin(window_energy)) + self.win // 2
        done = (self.start, cut)
        self.start = cut
        return done


def slice_bounds(samples: np.ndarray, slice_max_s: float, search_s: float, **pause) -> list[tuple[int, int]]:
    slicer = Slicer(slice_max_s, search_s, **pause)
    return slicer.feed(samples) + slicer.finish()


class Recognizer:
    def __init__(self, name: str | None = None):
        import sherpa_onnx

        cfg = config.load()
        self.name = name or cfg["recognizer"]
        self.cfg = cfg
        model = config.model_dir() / self.name
        self._recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=str(_one(model, "encoder*.onnx")),
            decoder=str(_one(model, "decoder*.onnx")),
            joiner=str(_one(model, "joiner*.onnx")),
            tokens=str(_one(model, "tokens.txt")),
            num_threads=8,
            model_type="nemo_transducer",
            decoding_method="greedy_search",
        )

    def recognize(self, samples: np.ndarray) -> str:
        stream = self._recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        self._recognizer.decode_stream(stream)
        return stream.result.text.strip()

    def transcribe(self, wav_path: str | Path) -> RawTranscript:
        started = time.monotonic()
        samples = read_wav(wav_path)
        slicer = Slicer.from_config(self.cfg)
        bounds = slicer.feed(samples) + slicer.finish()
        texts = [self.recognize(samples[start:end]) for start, end in bounds]
        return RawTranscript(join_slices(texts), int((time.monotonic() - started) * 1000))


def join_slices(texts: list[str]) -> str:
    return " ".join(t for t in texts if t)


def _one(model: Path, pattern: str) -> Path:
    matches = sorted(model.glob(pattern))
    if len(matches) != 1:
        raise FileNotFoundError(f"expected exactly one {pattern} in {model}, found {len(matches)}")
    return matches[0]


def transcribe(wav_path: str | Path, recognizer: str | None = None) -> RawTranscript:
    return Recognizer(recognizer).transcribe(wav_path)
