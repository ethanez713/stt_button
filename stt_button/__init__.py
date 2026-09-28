from dataclasses import dataclass
from pathlib import Path

from stt_button.asr import RawTranscript, Recognizer, transcribe
from stt_button.cleaner import Cleaned, Cleaner, clean


@dataclass
class Result:
    raw: str
    cleaned: str
    asr_ms: int
    clean_ms: int
    recognizer: str
    cleaner: str


def run(wav_path: str | Path, recognizer: str | None = None, cleaner: str | None = None) -> Result:
    # Loaded one after the other, never together: the phone holds one model at a time.
    asr = Recognizer(recognizer)
    raw = asr.transcribe(wav_path)
    recognizer_used = asr.name
    del asr
    clean_model = Cleaner(cleaner)
    cleaned = clean_model.clean(raw.text)
    return Result(raw.text, cleaned.text, raw.elapsed_ms, cleaned.elapsed_ms, recognizer_used, clean_model.name)


__all__ = ["Cleaned", "Cleaner", "RawTranscript", "Recognizer", "Result", "clean", "run", "transcribe"]
