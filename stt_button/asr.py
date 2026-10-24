import bisect
import re
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
    when a cut pause is set (design § Pipeline). The cut pause is cut_pause_s, or pause_s when
    that is omitted. paused stays the last pause_s of audio, the pause a take treats as the user
    thinking. A slice that reaches slice_max_s without that cut is cut at the centre of the
    quietest CUT_WINDOW_S window within its final search_s, so a word is not split when a gap is
    available. Each cut needs only the audio up to the point it is made, so feeding audio in
    pieces gives the same cuts as feeding it whole.

    Window energies are exact integer sums of squared int16 samples, so the silence test gives the
    same answer in every language that implements it.
    """

    def __init__(self, slice_max_s: float, search_s: float, pause_s: float | None = None,
                 slice_min_s: float = 2.0, pause_margin_db: float = 10.0,
                 cut_pause_s: float | None = None):
        self.max_len = int(slice_max_s * SAMPLE_RATE)
        self.search = int(search_s * SAMPLE_RATE)
        self.win = int(CUT_WINDOW_S * SAMPLE_RATE)
        self.pause_windows = None if pause_s is None else round(pause_s / CUT_WINDOW_S)
        cut = pause_s if cut_pause_s is None else cut_pause_s
        self.cut_windows = None if cut is None else round(cut / CUT_WINDOW_S)
        self.min_len = int(slice_min_s * SAMPLE_RATE)
        self.margin = 10 ** (pause_margin_db / 10)
        self.floor_min = self.win * 32768 ** 2 * 10 ** (NOISE_FLOOR_DBFS / 10)
        self.samples = np.zeros(0, dtype=np.float32)
        self.start = 0
        self._energies = 0
        self._sorted: list[int] = []
        self._last_speech: int | None = None
        self._speech: list[bool] = []

    @classmethod
    def from_config(cls, cfg: dict) -> "Slicer":
        return cls(cfg["slice_max_s"], cfg["slice_search_s"], cfg["pause_s"], cfg["slice_min_s"],
                   cfg["pause_margin_db"])

    @classmethod
    def from_config_for_field(cls, cfg: dict) -> "Slicer":
        hard = cfg["field_slice_max_s"]
        return cls(hard, min(cfg["slice_search_s"], hard - 1), cfg["pause_s"], cfg["slice_min_s"],
                   cfg["pause_margin_db"], cut_pause_s=cfg["field_cut_pause_s"])

    @property
    def paused(self) -> bool:
        """Speech has been heard and the last pause_s of audio is silent."""
        return (self.pause_windows is not None and self._last_speech is not None
                and self._energies - 1 - self._last_speech >= self.pause_windows)

    @property
    def last_speech_end(self) -> int | None:
        """Sample offset where the last non-silent window ended."""
        return None if self._last_speech is None else (self._last_speech + 1) * self.win

    def speech_before(self, sample: int) -> int | None:
        """Sample offset where the last non-silent window that ends at or before sample ends."""
        last = min(sample // self.win, len(self._speech)) - 1
        while last >= 0 and not self._speech[last]:
            last -= 1
        return None if last < 0 else (last + 1) * self.win

    def speech_after(self, sample: int) -> int | None:
        """Sample offset where the first non-silent window that starts at or after sample starts."""
        for index in range(-(-sample // self.win), len(self._speech)):
            if self._speech[index]:
                return index * self.win
        return None

    def feed(self, chunk: np.ndarray) -> list[tuple[int, int]]:
        """Append audio; return the slices it completed, as (start, end) sample offsets."""
        self.samples = np.concatenate([self.samples, chunk.astype(np.float32)])
        done: list[tuple[int, int]] = []
        while self.cut_windows is not None and (self._energies + 1) * self.win <= len(self.samples):
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
        self._speech.append(energy >= floor * self.margin)
        if self._speech[-1]:
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
        if (self.cut_windows is None or speech is None or speech < -(-self.start // self.win)
                or boundary - self.start < self.min_len):
            return None
        if self._energies - 1 - speech < self.cut_windows:
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

    def decode(self, samples: np.ndarray):
        stream = self._recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        self._recognizer.decode_stream(stream)
        return stream.result

    def recognize(self, samples: np.ndarray) -> str:
        return self.decode(samples).text.strip()

    def recognize_after(self, audio: np.ndarray, junction_s: float) -> tuple[str, str]:
        """Decodes context_audio's output; returns (the context's closing punctuation, the slice's text)."""
        result = self.decode(audio)
        return split_at_junction(result.tokens, result.timestamps, junction_s)

    def transcribe(self, wav_path: str | Path) -> RawTranscript:
        started = time.monotonic()
        samples = read_wav(wav_path)
        slicer = Slicer.from_config(self.cfg)
        bounds = slicer.feed(samples) + slicer.finish()
        texts = [self.recognize(samples[start:end]) for start, end in bounds]
        return RawTranscript(join_slices(texts), int((time.monotonic() - started) * 1000))


CONTEXT_LEAD_S = 0.1
TRAILING_MARK = re.compile(r"[.,?!;:]$")


def context_audio(samples: np.ndarray, speech_before: int, speech_after: int, end: int, context_s: float,
                  squeeze_s: float | None) -> tuple[np.ndarray, float]:
    """The audio to decode for a slice that follows a pause: the last context_s of speech before the
    pause, the pause (squeezed to squeeze_s of its own audio, or whole when None), then the slice from
    just before its first speech to end. Returns it with the time at which the slice's own speech begins."""
    lead = int(CONTEXT_LEAD_S * SAMPLE_RATE)
    before = samples[max(0, speech_before - int(context_s * SAMPLE_RATE)):speech_before]
    after_start = max(speech_before, speech_after - lead)
    gap = samples[speech_before:after_start]
    if squeeze_s is not None:
        gap = gap[:int(squeeze_s * SAMPLE_RATE)]
    audio = np.concatenate([before, gap, samples[after_start:end]])
    return audio, (len(before) + len(gap)) / SAMPLE_RATE


def split_at_junction(tokens: list[str], timestamps: list[float], junction_s: float) -> tuple[str, str]:
    """(punctuation the context ended with, the words from the junction on) of a context decode."""
    from stt_button.bubble import words_from_tokens

    words = words_from_tokens(tokens, timestamps)
    context = [w for w, t in words if t < junction_s]
    mark = TRAILING_MARK.search(context[-1]) if context else None
    return (mark.group() if mark else ""), " ".join(w for w, t in words if t >= junction_s)


def settled_mark(slice_mark: str, context_mark: str) -> str:
    """The mark that closes a slice once the next one is decoded. The context decode hears the words
    after the pause, so it decides whether the sentence ended; it hears only the sentence's last
    seconds, so a question or exclamation mark from the slice's own decode outranks its full stop."""
    return slice_mark if slice_mark in ("?", "!") and context_mark in (".", "?", "!") else context_mark


def join_slices(texts: list[str]) -> str:
    return " ".join(t for t in texts if t)


# A short slice decoded on its own capitalizes a function word and ends the previous one with a period.
# Those are not sentence boundaries; a name is left capitalized.
_CONTINUE_LOWER = {
    "a", "an", "the", "and", "or", "but", "to", "of", "in", "on", "for", "with", "it", "that", "this",
    "is", "was", "are", "were", "where", "when", "if", "so", "because", "which", "who", "what", "how",
    "we", "they", "he", "she", "you", "there", "then", "than", "as", "at", "from", "by", "not", "also",
    "just", "its", "be", "been", "have", "has", "had", "do", "does", "did", "can", "could", "would",
    "should", "will", "about", "into", "over", "after", "before", "out", "up", "my", "your",
}
_WORD_CORE = re.compile(r"^[^\w']+|[^\w']+$")
_CONTINUATION_WORD = re.compile(r"^([^\w']*)([\w']+)")


def _word_core(word: str) -> str:
    return _WORD_CORE.sub("", word).lower()


def continue_fragment(previous: str, nxt: str) -> tuple[str, str]:
    """(previous, next) once a slice decoded without the speech around it is joined to the one before.
    A repeated boundary word is dropped, a full stop on the previous slice is not kept, and a function
    word that opens the next slice is lower-cased. A question or exclamation mark stays."""
    previous, nxt = previous.rstrip(), nxt.strip()
    if not previous or not nxt:
        return previous, nxt
    prev_words, next_words = previous.split(), nxt.split()
    if prev_words and next_words and _word_core(prev_words[-1]) == _word_core(next_words[0]) and _word_core(prev_words[-1]):
        nxt = " ".join(next_words[1:])
        if not nxt:
            return previous, ""
    if previous.endswith(".") and not previous.endswith(".."):
        previous = previous[:-1].rstrip()
    if previous and previous[-1] not in "?!":
        nxt = _lowercase_continuation(nxt)
    return previous, nxt


def _lowercase_continuation(text: str) -> str:
    match = _CONTINUATION_WORD.match(text)
    if match is None or match.group(2).lower() not in _CONTINUE_LOWER:
        return text
    return match.group(1) + match.group(2).lower() + text[match.end():]


def _one(model: Path, pattern: str) -> Path:
    matches = sorted(model.glob(pattern))
    if len(matches) != 1:
        raise FileNotFoundError(f"expected exactly one {pattern} in {model}, found {len(matches)}")
    return matches[0]


def transcribe(wav_path: str | Path, recognizer: str | None = None) -> RawTranscript:
    return Recognizer(recognizer).transcribe(wav_path)
