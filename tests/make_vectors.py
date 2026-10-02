"""Writes tests/fixtures/pipeline_vectors.json, which the Python and Kotlin test suites both read.

    pyrun python -m tests.make_vectors

Signals are int16 pseudo-noise from a Lehmer generator, so both languages produce the same samples.
Token counts are word counts.
"""

import json
from pathlib import Path

import numpy as np

from stt_button import asr, config
from stt_button.asr import context_audio, settled_mark, split_at_junction
from stt_button.bubble import Bubble
from stt_button.cleaner import casual, live_chunks, matched_cue, skips_cleaner
from stt_button.spoken import spoken_punctuation

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


CASUAL_TEXTS = ["Okay, I'm down.", "I'm down, I think I'm free tomorrow", "Is it the full release?", "It cost 1,000 dollars, I think.",
                "Wait...", "Okay. Wait, what?", "Hi, there!", "ok.  ", "a, " * 41, "", "Version 2.5."]


SPOKEN_TEXTS = [
    "He said quote hello world, unquote and left",
    "He said quote unquote hello, and left",
    "Look at the architecture parentheses, which is decompiled and available close parentheses To see how it works question mark.",
    "set x open parenthesis y close parenthesis.",
    "Is it ready, question mark yes it is",
    "wow exclamation point that is great",
    "first item new line second item new paragraph third thing",
    "a quote from the contractor",
    "a period of time",
    "unquote that",
    "open quote only here",
    "close paren stray",
    "She said open quote " + "word " * 35 + "end quote",
    "the end. Quote of the day question mark",
    "Question mark",
    "",
]


JUNCTION_CASES = [
    {"tokens": [" update", " the", " plan", ".", " have", " some", " monitoring"],
     "times": [0.1, 0.4, 0.7, 0.9, 2.3, 2.6, 2.9], "junction_s": 2.0},
    {"tokens": [" a", " b", " c"], "times": [0.1, 0.5, 2.5], "junction_s": 2.0},
    {"tokens": [" a", " b"], "times": [0.1, 0.5], "junction_s": 2.0},
    {"tokens": [" is", " it", " ok", "?", " yes", ",", " fine"], "times": [0.2, 0.5, 0.8, 1.0, 2.2, 2.4, 2.6], "junction_s": 2.0},
    {"tokens": [" x", " y"], "times": [3.0, 3.5], "junction_s": 2.0},
]

CONTEXT_AUDIO_CASES = [
    {"samples": 10 * SR, "speech_before": 4 * SR, "speech_after": 8 * SR, "end": 10 * SR, "context_s": 2, "squeeze_s": 0.2},
    {"samples": 10 * SR, "speech_before": 4 * SR, "speech_after": 8 * SR, "end": 10 * SR, "context_s": 2, "squeeze_s": None},
    {"samples": 10 * SR, "speech_before": 1 * SR, "speech_after": 1 * SR + 800, "end": 6 * SR, "context_s": 4, "squeeze_s": 0.2},
    {"samples": 10 * SR, "speech_before": 4 * SR, "speech_after": 4 * SR + 3200, "end": 9 * SR, "context_s": 4, "squeeze_s": 0.2},
]


def context_audio_vector(case: dict) -> dict:
    audio, junction = context_audio(np.arange(case["samples"], dtype=np.float32), case["speech_before"],
                                    case["speech_after"], case["end"], case["context_s"], case["squeeze_s"])
    return {**case, "length": len(audio), "first": int(audio[0]), "last": int(audio[-1]), "junction_s": junction}


BUBBLE_SEQUENCES = {
    "slice_drops_preview_words": [
        ["preview", [["meet", 0.4], ["me", 0.9], ["at", 1.5], ["noon", 2.1], ["okay", 3.2]]],
        ["slice", 2.5, "Meet me at noon."],
        ["preview", [["meet", 0.4], ["me", 0.9], ["at", 1.5], ["noon", 2.1], ["okay", 3.2], ["thanks", 3.9]]],
    ],
    "chunk_replaces_its_sentences": [
        ["slice", 10.0, "First thing. Second thing. Third thing."],
        ["chunk", "First thing. Second thing.", "1. First\n2. Second"],
        ["slice", 20.0, "Fourth thing."],
        ["preview", [["fifth", 21.0]]],
    ],
    "empty_cleaned_chunk": [
        ["slice", 5.0, "Um. Hello there."],
        ["chunk", "Um.", ""],
    ],
}


def replay_bubble(events: list[list]) -> list[list[str]]:
    bubble, shown = Bubble(), []
    for kind, *args in events:
        {"preview": bubble.preview, "slice": bubble.slice_landed, "chunk": bubble.chunk_cleaned}[kind](*args)
        shown.append(list(bubble.text()))
    return shown


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
        "bubble": [{"name": n, "events": e, "after_each": replay_bubble(e)} for n, e in BUBBLE_SEQUENCES.items()],
        "live_chunks": chunks,
        "casual": [{"text": t, "casual": casual(t, cfg)} for t in CASUAL_TEXTS],
        "junction": [{**c, "split": list(split_at_junction(c["tokens"], c["times"], c["junction_s"]))} for c in JUNCTION_CASES],
        "context_audio": [context_audio_vector(c) for c in CONTEXT_AUDIO_CASES],
        "settled_mark": [{"slice": s, "context": c, "mark": settled_mark(s, c)}
                         for s in ("", ".", ",", "?", "!") for c in ("", ".", ",", "?", "!")],
        "spoken": [{"text": t, "spoken": spoken_punctuation(t)} for t in SPOKEN_TEXTS],
        "skips_cleaner": {
            "skip_max_words": cfg["skip_max_words"],
            "self_correction_cues": cfg["self_correction_cues"],
            "cases": [{"text": t, "skipped": skips_cleaner(t, cfg), "cue": matched_cue(t, cfg["self_correction_cues"])}
                      for t in SKIP_TEXTS],
        },
    }


if __name__ == "__main__":
    FIXTURE.write_text(json.dumps(vectors(), indent=1, ensure_ascii=False) + "\n")
    print(f"wrote {FIXTURE}")
