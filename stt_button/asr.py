import time
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from stt_button import config

SAMPLE_RATE = 16000
CUT_WINDOW_S = 0.1


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
    """Cuts recognizer slices of at most slice_max_s from audio that may still be growing.

    Each cut but the last lands at the centre of the quietest CUT_WINDOW_S window within the
    final search_s of its slice, so a word is not split when a pause is available. A cut needs
    only the audio up to its slice's hard end, so feeding audio in pieces gives the same cuts as
    feeding it whole.
    """

    def __init__(self, slice_max_s: float, search_s: float):
        self.max_len = int(slice_max_s * SAMPLE_RATE)
        self.search = int(search_s * SAMPLE_RATE)
        self.win = int(CUT_WINDOW_S * SAMPLE_RATE)
        self.samples = np.zeros(0, dtype=np.float32)
        self.start = 0

    def feed(self, chunk: np.ndarray) -> list[tuple[int, int]]:
        """Append audio; return the slices it completed, as (start, end) sample offsets."""
        self.samples = np.concatenate([self.samples, chunk.astype(np.float32)])
        done = []
        while len(self.samples) - self.start > self.max_len:
            hard_end = self.start + self.max_len
            lo = hard_end - self.search
            region = self.samples[lo:hard_end].astype(np.float64) ** 2
            energy = np.concatenate([[0.0], np.cumsum(region)])
            window_energy = energy[self.win:] - energy[:-self.win]
            cut = lo + int(np.argmin(window_energy)) + self.win // 2
            done.append((self.start, cut))
            self.start = cut
        return done

    def finish(self) -> list[tuple[int, int]]:
        """The final slice, once no more audio will arrive."""
        return [(self.start, len(self.samples))] if len(self.samples) > self.start else []


def slice_bounds(samples: np.ndarray, slice_max_s: float, search_s: float) -> list[tuple[int, int]]:
    slicer = Slicer(slice_max_s, search_s)
    return slicer.feed(samples) + slicer.finish()


class Recognizer:
    def __init__(self, name: str | None = None):
        import sherpa_onnx

        cfg = config.load()
        self.name = name or cfg["recognizer"]
        self.slice_max_s = cfg["slice_max_s"]
        self.slice_search_s = cfg["slice_search_s"]
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
        texts = [self.recognize(samples[start:end])
                 for start, end in slice_bounds(samples, self.slice_max_s, self.slice_search_s)]
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
