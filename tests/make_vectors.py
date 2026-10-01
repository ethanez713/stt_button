"""Writes tests/fixtures/pipeline_vectors.json, which the Python and Kotlin test suites both read.

    pyrun python -m tests.make_vectors

Signals are int16 pseudo-noise from a Lehmer generator, so both languages produce the same samples.
Token counts are word counts.
"""

import json
from pathlib import Path

import numpy as np

from stt_button import asr, config
from stt_button.cleaner import live_chunks, skips_cleaner

FIXTURE = Path(__file__).parent / "fixtures" / "pipeline_vectors.json"
SR = asr.SAMPLE_RATE


def pcm_signal(segments: list[list]) -> np.ndarray:
    """Noise segments are ["noise", seconds, amplitude]; silence is ["silence", seconds]."""
    parts, index = [], 0
    for kind, seconds, *rest in segments:
        count = int(seconds * SR)
        if kind == "noise":
            i = np.arange(index, index + count, dtype=np.int64)
            parts.append((i * 48271) % 2147483647 % (2 * rest[0] + 1) - rest[0])
        else:
            parts.append(np.zeros(count, dtype=np.int64))
        index += count
    return np.concatenate(parts).astype(np.int16)


def to_float(pcm: np.ndarray) -> np.ndarray:
    return pcm.astype(np.float32) / 32768


def sentence(n_words: int, end: str = ".") -> str:
    return " ".join(["word"] * (n_words - 1) + ["last" + end])


def words(text: str) -> int:
    return len(text.split())


PAUSE = {"pause_s": 0.8, "slice_min_s": 2, "pause_margin_db": 10}

SLICER_CASES = {
    "pauses_inside_search_window": ([["noise", 27, 8000], ["silence", 0.2], ["noise", 29.8, 8000],
                                     ["silence", 0.2], ["noise", 17.8, 8000]], 30, 5),
    "no_pause_at_all": ([["noise", 95, 8000]], 30, 5),
    "exactly_one_slice": ([["noise", 30, 8000]], 30, 5),
    "empty": ([["silence", 0]], 30, 5),
    "short_slice_limit": ([["noise", 9, 8000], ["silence", 0.3], ["noise", 11, 8000]], 8, 2),
    "pause_cuts_at_the_middle": ([["silence", 0.3], ["noise", 3, 8000], ["silence", 1.5], ["noise", 4, 8000], ["silence", 1.0],
                                  ["noise", 2, 8000]], 30, 5, PAUSE),
    "pause_inside_slice_min": ([["silence", 0.3], ["noise", 0.5, 8000], ["silence", 1.0], ["noise", 3, 8000]], 30, 5, PAUSE),
    "all_silence": ([["silence", 10]], 30, 5, PAUSE),
    "leading_silence": ([["silence", 3], ["noise", 2, 8000], ["silence", 2], ["noise", 1, 8000]], 30, 5, PAUSE),
    "short_gap_then_hard_cut": ([["silence", 0.3], ["noise", 29, 8000], ["silence", 0.5], ["noise", 10, 8000], ["silence", 1.0],
                                 ["noise", 3, 8000]], 30, 5, PAUSE),
    "long_silence_cuts_once": ([["silence", 0.3], ["noise", 3, 8000], ["silence", 5], ["noise", 3, 8000]], 30, 5, PAUSE),
    "quiet_speech_over_a_noise_floor": ([["noise", 2, 300], ["noise", 2, 3000], ["noise", 1.5, 300],
                                         ["noise", 2, 3000]], 30, 5, PAUSE),
}

CHUNK_TEXTS = {
    "mixed_sentences": " ".join(sentence(n, e) for n, e in
                                [(7, "."), (31, "?"), (12, "."), (45, "!"), (9, "."), (28, "."), (60, ".")]),
    "long_and_short": " ".join(sentence(n) for n in [100, 3, 150, 20, 5, 80, 130, 4]),
    "no_final_punctuation": "so um " + sentence(40) + " and then " + " ".join(sentence(n) for n in [33, 71, 12, 90]),
    "one_overlong_sentence": sentence(3) + " " + sentence(25) + " " + sentence(3),
    "blank": "   ",
}

SKIP_TEXTS = ["", "Yes.", "meet Tuesday, actually no, Wednesday", "Dinner is at seven I mean eight",
              "a " * 41, "a " * 40, "this is a cannot be rather odd", "the knot was nothing", "Okay. Wait, what?",
              "Not apples, pears?", "555-010 no 555-010-7896", "Tuesday? Actually make that Wednesday"]


def vectors() -> dict:
    cfg = config.load()
    slicer = []
    for name, (segments, slice_max_s, search_s, *pause) in SLICER_CASES.items():
        pause = pause[0] if pause else {}
        bounds = asr.slice_bounds(to_float(pcm_signal(segments)), slice_max_s, search_s, **pause)
        slicer.append({"name": name, "segments": segments, "slice_max_s": slice_max_s, "search_s": search_s,
                       "pause": pause, "bounds": [list(b) for b in bounds]})
    chunks = []
    for name, text in CHUNK_TEXTS.items():
        for lo, hi in ((40, cfg["chunk_max_tokens"]), (cfg["chunk_min_tokens"], cfg["chunk_max_tokens"]), (10, 10)):
            for final in (False, True):
                chunks.append({"name": name, "text": text, "min_tokens": lo, "max_tokens": hi, "final": final,
                               "chunks": live_chunks(text, words, lo, hi, final)})
    return {
        "slicer": slicer,
        "live_chunks": chunks,
        "skips_cleaner": {
            "skip_max_words": cfg["skip_max_words"],
            "self_correction_cues": cfg["self_correction_cues"],
            "cases": [{"text": t, "skipped": skips_cleaner(t, cfg)} for t in SKIP_TEXTS],
        },
    }


if __name__ == "__main__":
    FIXTURE.write_text(json.dumps(vectors(), indent=1, ensure_ascii=False) + "\n")
    print(f"wrote {FIXTURE}")
