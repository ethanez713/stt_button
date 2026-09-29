import importlib
from dataclasses import dataclass
from pathlib import Path

__all__ = ["Cleaned", "Cleaner", "RawTranscript", "Recognizer", "Result", "clean", "run", "transcribe"]

_LAZY = {
    "RawTranscript": "stt_button.asr", "Recognizer": "stt_button.asr", "transcribe": "stt_button.asr",
    "Cleaned": "stt_button.cleaner", "Cleaner": "stt_button.cleaner", "clean": "stt_button.cleaner",
}


def __getattr__(name: str):
    # Lazy, so the dictation CLI and the recorder start without numpy or the model runtimes.
    if name in _LAZY:
        return getattr(importlib.import_module(_LAZY[name]), name)
    raise AttributeError(f"module 'stt_button' has no attribute {name!r}")


@dataclass
class Result:
    raw: str
    cleaned: str
    asr_ms: int
    clean_ms: int
    recognizer: str
    cleaner: str


def run(wav_path: str | Path, recognizer: str | None = None, cleaner: str | None = None) -> Result:
    from stt_button.asr import Recognizer
    from stt_button.cleaner import Cleaner

    # Loaded one after the other, never together: the phone holds one model at a time.
    asr = Recognizer(recognizer)
    raw = asr.transcribe(wav_path)
    recognizer_used = asr.name
    del asr
    clean_model = Cleaner(cleaner)
    cleaned = clean_model.clean(raw.text)
    return Result(raw.text, cleaned.text, raw.elapsed_ms, cleaned.elapsed_ms, recognizer_used, clean_model.name)
