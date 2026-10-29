"""Writes tests/fixtures/pipeline_vectors.json, which the Python and Kotlin test suites both read.

    pyrun python -m tests.make_vectors

Signals are int16 pseudo-noise from a Lehmer generator, so both languages produce the same samples.
Token counts are word counts.
"""

import json
from pathlib import Path

import numpy as np

from stt_button import asr, config
from stt_button.asr import context_audio, continuing, settled_mark, split_at_junction
from stt_button.bubble import Bubble
from stt_button.live import LiveTake
from stt_button.cleaner import (casual, fact_drift, join_cleaned, live_chunks, opens_with_cue, matched_cue, skips_cleaner,
                                strip_hidden, untrusted_clean, without_sign_off)
from stt_button.spoken import keep_spoken_ending, spoken_punctuation

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

OPENERS = ["Actually, no, make that Wednesday.", "Uh, or, I mean eight.", "No, five.", "Not apples, pears?",
           "Also, no need to wait.", "Nothing else.", "Or rather the green one.", "I'm not sure.", "Scratch that, navy.",
           "So, well, never mind.", "Make sure it works.", ""]

CORRECTION_TEXTS = {
    "correction_after_a_closed_chunk": sentence(12) + " " + sentence(12) + " Actually no, make that Wednesday. " + sentence(12),
    "cue_inside_a_sentence": sentence(6) + " This is not a correction at all. " + sentence(12) + " " + sentence(12),
    "fillers_then_cue": sentence(12) + " Uh, or, I mean the other one. " + sentence(25),
    "two_corrections_in_a_row": sentence(12) + " " + sentence(5) + " No wait, that is wrong. Sorry, never mind. " + sentence(22),
    "correction_first": "Actually this opens the take. " + sentence(22) + " Not that, this. " + sentence(3),
    "short_last_sentence": sentence(22) + " " + sentence(4) + " I",
}

JOIN_CLEANED_CASES = [
    (["a b.", "c d.", "e f. Actually g.", "h i."], ["A b.", "C d.", "E g.", "H i."]),
    (["a.", "e f. Actually g."], ["Pack:\n- X\n- Y", "E g."]),
    (["um.", "a b.", "c d."], ["", "A b.", "C d."]),
    (["e f. No, g.", "um."], ["E g.", ""]),
    ([], []),
]

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


CONTINUING_CASES = [
    ("buffer", "Text for way too long."), ("a whitelisted", "Configuration."), ("with.", "Activate it"),
    ("so", "I'm here"), ("got the", "iPhone"), ("the", "NASA launch"), ("asked?", "Then"), ("list:\n", "Pack"),
    ("- ", "Milk"), ("(", "Note"), ("said \u201chi\u201d", "Then"), ("", "Hello"), ("send", "\"Quote\" here"),
    ("costs", "92515 dollars"), ("ends here", ""),
]

CASUAL_TEXTS = ["Okay, I'm down.", "I'm down, I think I'm free tomorrow", "Is it the full release?", "It cost 1,000 dollars, I think.",
                "Wait...", "Okay. Wait, what?", "Hi, there!", "ok.  ", "a, " * 41, "", "Version 2.5."]


SIGN_OFF_TEXTS = [
    "I need to know what last year's gifts were before I can decide. Okay.", "Even more so than they already were. Yeah.",
    "easy enough for us to hop to Um", "Is that okay?", "Okay.", "Okay", "it thinks I'll Yeah.", "Right. Okay, yeah.",
    "so, um.", "fine. Ok. Um", "Do it? Okay?", "it works. Yep!", "ends.\n\nOkay.", "Um", "",
]


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
    "open paren never closed",
    "things like quote unquote or open close parentheses? Those get inserted",
    "the quote unquote And operator",
    "She said open quote " + "word " * 35 + "end quote",
    "the end. Quote of the day question mark",
    "Question mark",
    "like little stars, not stars, white stars on top",
    "get the Titan Eco not eco economy plates",
    "the trip. We lay not lay, we land Monday",
    "sure that it's not a fraudulent Not fraudulent. Fraud attempt.",
    "simple, straightforward, low, not low, high certainty fixes",
    "book it Monday, not Monday, Tuesday at five",
    "like it or not, not everyone agrees",
    "that is simply not",
    "a very high markup slash premium and that",
    "in landscaping/slash botany?",
    "fold them into the slash autopilot skill",
    "Run it, slash compact now",
    "an on hyphen device test",
    "an on-hyphen device test",
    "a well-known hyphen",
    "wait dash no",
    "wait, dash no",
    "open parentheses all green closed parentheses, and then",
    "marks like slash, hyphen, and so on",
    "marks like slash hyphen and so on",
    "grab a dash of salt",
    "mount the dash cam",
    "a hyphen",
    "slash",
    "",
]


DRIFT_CASES = [
    ("Agents attach to my S20 Ultra today.", "Agents attach to my S25 Ultra today."),
    ("It is my S20 Ultra, not the S25 Ultra. Put it on the S20 Ultra.", "It is my S25 Ultra, not the S25 Ultra. Put it on the S25 Ultra."),
    ("I want AB Connect to have it.", "I want ADB Connect to have it."),
    ("So the plan is simple. Wake the phone first.", "So the plan is simple. wake the phone first."),
    ("meet at Twelfth at five", "meet at 12th at 5"),
    ("meet at 4. Actually no, make it 5.", "Meet at 5."),
    ("a letter See then", "a letter C then"),
    ("Then I'm sure I'll go", "Then I am sure I will go"),
    ("It costs $5, I think.", "It costs $5. I think."),
    ("", ""),
]

# Each case is checked by untrusted_clean on both sides; the hidden characters are vectors of their own.
UNTRUSTED_CASES = [
    ("hi", "Hi.<|im_end|>"),
    ("hi", "<think>Hi.</think>"),
    ("meet me at noon", "Meet me at noon. https://x.io/?q=meet+me+at+noon"),
    ("nice photo", "Nice photo ![](http://e.vil/a.png)"),
    ("go to paypal dot com", "Go to p\u0430ypal.com."),
    ("send it to john", "Send it to john@evil.io."),
    ("call me later", "Call me later. See x.io"),
    ("go to example dot com", "Go to example.com."),
    ("write to john at gmail dot com", "Write to john@gmail.com."),
    ("open w w w dot google dot com slash maps", "Open www.google.com/maps."),
    ("Go to example.com now.", "Go to example.com now."),
    ("the dot com era", "The dot.com era."),
    ("for example e g apples", "For example, e.g., apples."),
    ("it rose three point five percent", "It rose 3.5%."),
    ("it is done then we go", "It is done.Then we go."),
    ("Agents attach to my S20 Ultra today.", "Agents attach to my S25 Ultra today."),
    ("one two three", "- one\n- two\tthree"),
]

HIDDEN_CODE_POINTS = [
    0x0A, 0x09, 0x20, 0x41, 0xE9, 0xA0, 0x4E2D, 0x1F600, 0x0430, 0x2014,
    0x00, 0x1B, 0x7F, 0x85, 0xAD, 0x061C, 0x200B, 0x200D, 0x200E, 0x202E, 0x2028, 0x2029, 0x2066, 0xFEFF,
    0xE000, 0xF8FF, 0xFE00, 0xFE0F, 0xE0001, 0xE0049, 0xE007F, 0xE0100, 0xE01EF, 0xF0000,
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


def labelled(prefix: str, count: int) -> str:
    return " ".join(" ".join([f"{prefix}{i}"] * 8) + f" {prefix}{i}." for i in range(1, count + 1))


TAKE_CFG = {"chunk_min_tokens": 15, "chunk_max_tokens": 1000, "skip_max_words": 5, "pause_s": 0.8, "slice_min_s": 2}
SPEECH = ["noise", 3, 8000]
PAUSE_1S = ["silence", 1.2]
LEAD = ["silence", 0.3]

# A take script drives one take. `steps` run in order and the take then stops. ["audio", segments] feeds
# `pcm_signal(segments)` in 100 ms pieces; ["idle", segments] runs one pause-time clean step, during which
# the take reads `pcm_signal(segments)` as the audio that arrived meanwhile. `slices` is what the recognizer
# returns, in the order the slicer cuts: [text, mark], the mark being what a decode with the speech before it
# adds. The cleaner returns the chunk upper-cased with each [find, replace] of `replace` applied, and gives
# up when speech arrives during a pause-time clean. `cfg` overrides the pipeline's configuration.
TAKE_SCRIPTS = [
    {"name": "one_slice_under_the_skip_limit", "steps": [["audio", [LEAD, SPEECH]]],
     "slices": [["Meet me at noon.", "."]]},
    {"name": "skip_limit_take_with_a_correction_cue", "steps": [["audio", [LEAD, SPEECH]]],
     "slices": [["Meet Tuesday, actually Wednesday.", "."]]},
    {"name": "mark_change_at_the_junction", "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, SPEECH]]],
     "slices": [["Update the plan.", "."], ["have some monitoring now", "?"]]},
    {"name": "no_mark_at_the_junction_lowercases_the_slice", "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, SPEECH]]],
     "slices": [["It seems to buffer.", "."], ["Text for way too long.", ""]]},
    {"name": "period_at_the_junction_keeps_the_capital", "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, SPEECH]]],
     "slices": [["Update the plan.", "."], ["Have some monitoring.", "."]]},
    {"name": "two_chunks_close_before_the_stop", "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, SPEECH, PAUSE_1S, SPEECH]]],
     "slices": [[labelled("a", 3), "."], [labelled("b", 3), "."], [labelled("c", 1), "."]]},
    {"name": "pause_clean_interrupted_by_speech",
     "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, SPEECH, PAUSE_1S]], ["idle", [["noise", 0.5, 8000]]], ["audio", [SPEECH]]],
     "slices": [[labelled("a", 1) + " " + labelled("b", 1), "."], [labelled("c", 2), "."], ["tail end here.", "."]]},
    {"name": "pause_clean_completes_and_is_reused",
     "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, SPEECH, PAUSE_1S]], ["idle", []]],
     "slices": [[labelled("a", 1) + " " + labelled("b", 1), "."], [labelled("c", 2), "."], ["", "."]]},
    {"name": "yeah_tail_after_speech_is_dropped",
     "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, ["noise", 0.3, 8000]]]],
     "slices": [["Meet me at noon.", "."], ["Yeah.", "?"]]},
    {"name": "short_whole_take_okay_is_kept",
     "steps": [["audio", [["silence", 0.2], ["noise", 1.0, 8000]]]],
     "slices": [["Okay.", "."]]},
    {"name": "short_tail_with_real_words_is_kept",
     "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, ["noise", 0.3, 8000]]]],
     "slices": [["Meet me at noon.", "."], ["See you tomorrow.", "."]]},
    {"name": "yeah_tail_of_two_seconds_is_kept",
     "steps": [["audio", [LEAD, SPEECH, PAUSE_1S, ["noise", 0.9, 8000]]]],
     "slices": [["Meet me at noon.", "."], ["Yeah.", "."]]},
    {"name": "pause_clean_completes_before_more_speech",
     "steps": [["audio", [LEAD, SPEECH, PAUSE_1S]], ["idle", []], ["audio", [SPEECH, PAUSE_1S, SPEECH]]],
     "slices": [["one nine word sentence sits right here.", "."], [labelled("b", 2), "."], [labelled("c", 2), "."]]},
    {"name": "fact_drift_falls_back_to_the_chunk_as_heard", "steps": [["audio", [LEAD, SPEECH]]],
     "cleaner": [["S20", "S25"]], "slices": [["Put it on the S20 Ultra please now today.", "."]]},
    {"name": "a_clean_that_adds_a_link_falls_back_to_the_chunk_as_heard", "steps": [["audio", [LEAD, SPEECH]]],
     "cleaner": [["TODAY.", "TODAY. SEE X.IO"]], "slices": [["Send the plan to the team please now today.", "."]]},
    {"name": "spoken_question_mark_at_a_chunks_end", "steps": [["audio", [LEAD, SPEECH]]],
     "cleaner": [[" QUESTION MARK", "."]], "slices": [["then ask if anyone wants it sooner question mark", "."]]},
]


class ScriptedRecognizer:
    def __init__(self, slices: list[list[str]]):
        self.queue = [list(s) for s in slices]

    def recognize(self, samples):
        return self.queue.pop(0)[0]

    def recognize_after(self, audio, junction_s):
        text, mark = self.queue.pop(0)
        return mark, text


class MarkingCleaner:
    def __init__(self, replacements: list[list[str]]):
        self.replacements = replacements

    def count_tokens(self, text: str) -> int:
        return words(text)

    def clean_chunk(self, chunk: str, cancelled=None):
        if cancelled and cancelled():
            return None
        out = chunk.upper()
        for find, replace in self.replacements:
            out = out.replace(find, replace)
        return out, 1


def run_take_script(script: dict, with_idle: bool = True) -> dict:
    cfg = {**config.load(), **TAKE_CFG, **script.get("cfg", {})}
    recognizer = ScriptedRecognizer(script["slices"])
    take = LiveTake(recognizer, lambda: MarkingCleaner(script.get("cleaner", [])), cfg)
    piece = SR // 10
    for kind, segments in script["steps"]:
        audio = to_float(pcm_signal(segments)) if segments else np.zeros(0, dtype=np.float32)
        if kind == "audio":
            for at in range(0, len(audio), piece):
                take.feed(audio[at:at + piece])
        elif with_idle:
            arrived = [audio]
            take.idle_step(lambda: arrived.pop() if arrived else np.zeros(0, dtype=np.float32))
    take.mark_stop()
    result = take.finish()
    assert not recognizer.queue, f"{script['name']}: {len(recognizer.queue)} scripted slices were never cut"
    return {"raw": result.raw, "cleaned": result.cleaned, "clean_mode": result.clean_mode, "chunks": result.chunks,
            "idle_cleans": result.idle_cleans, "idle_hits": result.idle_hits}


def take_vectors() -> list[dict]:
    out = []
    for script in TAKE_SCRIPTS:
        result = run_take_script(script)
        if any(kind == "idle" for kind, _ in script["steps"]):
            plain = run_take_script(script, with_idle=False)
            assert {k: result[k] for k in ("raw", "cleaned", "clean_mode", "chunks")} == \
                {k: plain[k] for k in ("raw", "cleaned", "clean_mode", "chunks")}, f"{script['name']}: idle cleaning changed the text"
        if script["name"] == "pause_clean_completes_and_is_reused":
            assert result["idle_hits"] >= 1, "the pause-time clean was never reused"
        if script["name"] in ("pause_clean_interrupted_by_speech", "pause_clean_completes_before_more_speech"):
            assert result["idle_cleans"] >= 1 and result["idle_hits"] == 0, f"{script['name']}: {result}"
        out.append({**script, "cfg": {**TAKE_CFG, **script.get("cfg", {})},
                    "result": {k: result[k] for k in ("raw", "cleaned", "clean_mode", "chunks")}})
    by_name = {v["name"]: v for v in out}
    assert by_name["one_slice_under_the_skip_limit"]["result"]["clean_mode"] == "raw"
    assert by_name["skip_limit_take_with_a_correction_cue"]["result"]["clean_mode"] == "chunks"
    assert by_name["mark_change_at_the_junction"]["result"]["raw"] == "Update the plan? have some monitoring now"
    assert by_name["two_chunks_close_before_the_stop"]["result"]["chunks"] >= 3
    assert by_name["fact_drift_falls_back_to_the_chunk_as_heard"]["result"]["cleaned"].startswith("Put it on the S20")
    assert by_name["spoken_question_mark_at_a_chunks_end"]["result"]["cleaned"].endswith("?")
    assert "X.IO" not in by_name["a_clean_that_adds_a_link_falls_back_to_the_chunk_as_heard"]["result"]["cleaned"]
    assert by_name["yeah_tail_after_speech_is_dropped"]["result"]["raw"] == "Meet me at noon."
    assert by_name["short_whole_take_okay_is_kept"]["result"]["raw"] == "Okay."
    assert by_name["short_tail_with_real_words_is_kept"]["result"]["raw"] == "Meet me at noon. See you tomorrow."
    assert by_name["yeah_tail_of_two_seconds_is_kept"]["result"]["raw"] == "Meet me at noon. Yeah."
    return out


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
        "takes": take_vectors(),
        "correction_chunks": [
            {"name": n, "text": " ".join(t.split()[:k]), "min_tokens": 20, "max_tokens": 100, "final": final,
             "cues": cfg["self_correction_cues"],
             "chunks": live_chunks(" ".join(t.split()[:k]), words, 20, 100, final, cues=cfg["self_correction_cues"])}
            for n, t in CORRECTION_TEXTS.items() for k in range(1, len(t.split()) + 1) for final in (False, True)
            if final is False or k == len(t.split())],
        "opens_with_cue": [{"sentence": s, "opens": opens_with_cue(s, cfg["self_correction_cues"])} for s in OPENERS],
        "join_cleaned": [{"chunks": c, "outputs": o, "cues": cfg["self_correction_cues"],
                          "joined": join_cleaned(c, o, cfg["self_correction_cues"])} for c, o in JOIN_CLEANED_CASES],
        "sign_off": [{"text": t, "without": without_sign_off(t)} for t in SIGN_OFF_TEXTS],
        "casual": [{"text": t, "casual": casual(t, cfg)} for t in CASUAL_TEXTS],
        "continuing": [{"before": b, "body": t, "continued": continuing(t, b)} for b, t in CONTINUING_CASES],
        "junction": [{**c, "split": list(split_at_junction(c["tokens"], c["times"], c["junction_s"]))} for c in JUNCTION_CASES],
        "context_audio": [context_audio_vector(c) for c in CONTEXT_AUDIO_CASES],
        "settled_mark": [{"slice": s, "context": c, "mark": settled_mark(s, c)}
                         for s in ("", ".", ",", "?", "!") for c in ("", ".", ",", "?", "!")],
        "fact_drift": [{"raw": r, "cleaned": c, "drift": [list(p) for p in fact_drift(r, c)]} for r, c in DRIFT_CASES],
        "untrusted_clean": [{"raw": r, "cleaned": c, "reason": untrusted_clean(r, c)} for r, c in UNTRUSTED_CASES],
        "strip_hidden": [{"code_point": cp, "hidden": strip_hidden(chr(cp)) == ""} for cp in HIDDEN_CODE_POINTS],
        "spoken_ending": [{"chunk": c, "cleaned": o, "kept": keep_spoken_ending(c, o)} for c, o in [
            ("then ask if anyone wants it sooner question mark", "Then ask if anyone wants it sooner."),
            ("wow exclamation point", "Wow."),
            ("is it ready question mark yes it is", "Is it ready? Yes, it is."),
            ("Is it ready?", "Is it ready."),
            ("it is ready", "It is ready."),
            ("sooner question mark", "Sooner?"),
            ("", ""),
        ]],
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
