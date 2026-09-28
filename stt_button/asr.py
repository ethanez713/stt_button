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


def slice_bounds(samples: np.ndarray, slice_max_s: float, search_s: float) -> list[tuple[int, int]]:
    """Cut points for recognizer slices of at most slice_max_s.

    Each cut but the last lands at the centre of the quietest CUT_WINDOW_S window within the
    final search_s of its slice, so a word is not split when a pause is available.
    """
    n = len(samples)
    max_len = int(slice_max_s * SAMPLE_RATE)
    search = int(search_s * SAMPLE_RATE)
    win = int(CUT_WINDOW_S * SAMPLE_RATE)
    energy = np.concatenate([[0.0], np.cumsum(samples.astype(np.float64) ** 2)])
    bounds = []
    start = 0
    while n - start > max_len:
        hard_end = start + max_len
        lo = hard_end - search
        window_energy = energy[lo + win:hard_end + 1] - energy[lo:hard_end + 1 - win]
        cut = lo + int(np.argmin(window_energy)) + win // 2
        bounds.append((start, cut))
        start = cut
    if n > start:
        bounds.append((start, n))
    return bounds


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

    def transcribe(self, wav_path: str | Path) -> RawTranscript:
        started = time.monotonic()
        samples = read_wav(wav_path)
        texts = []
        for start, end in slice_bounds(samples, self.slice_max_s, self.slice_search_s):
            stream = self._recognizer.create_stream()
            stream.accept_waveform(SAMPLE_RATE, samples[start:end])
            self._recognizer.decode_stream(stream)
            texts.append(stream.result.text.strip())
        text = " ".join(t for t in texts if t)
        return RawTranscript(text, int((time.monotonic() - started) * 1000))


def _one(model: Path, pattern: str) -> Path:
    matches = sorted(model.glob(pattern))
    if len(matches) != 1:
        raise FileNotFoundError(f"expected exactly one {pattern} in {model}, found {len(matches)}")
    return matches[0]


def transcribe(wav_path: str | Path, recognizer: str | None = None) -> RawTranscript:
    return Recognizer(recognizer).transcribe(wav_path)
