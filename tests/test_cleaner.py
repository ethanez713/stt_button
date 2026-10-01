import itertools

import pytest

from experiments import paths
from stt_button import cleaner, config
from stt_button.asr import join_slices


def words(text):
    return len(text.split())


def sentence(n_words, end="."):
    return " ".join(["word"] * (n_words - 1) + ["last" + end])


def test_s1_prompt_is_the_training_format():
    spec = config.load()["cleaners"]["s1-mini"]
    prompt = cleaner.render_prompt(spec["system_prompt"], cleaner.user_turn(spec["control_line"], "um so hi"))

    assert prompt == (
        "<|im_start|>system\n"
        "You are a text normalizer for speech-to-text transcripts. The input begins with a control line "
        "specifying the styling, structure, and context settings; clean the transcript to match those "
        "settings and output only the cleaned text.<|im_end|>\n"
        "<|im_start|>user\n"
        "[Styling: semi-formal] [Structure: lists] [Context: general]\n"
        "um so hi<|im_end|>\n"
        "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    )


def test_qwen_turn_has_no_control_line():
    spec = config.load()["cleaners"]["qwen3.5-2b"]
    assert cleaner.user_turn(spec["control_line"], "um so hi") == "um so hi"


def test_max_new_tokens_follows_model_card():
    assert cleaner.max_new_tokens(1000, 1.3, 32) == 1332
    assert cleaner.max_new_tokens(0, 1.3, 32) == 32


CFG = config.load()
MIN, MAX = CFG["chunk_min_tokens"], CFG["chunk_max_tokens"]


def chunks(text, final, lo=MIN, hi=MAX):
    return cleaner.live_chunks(text, words, lo, hi, final)


def test_chunk_closes_at_min_tokens():
    text = " ".join(sentence(60) for _ in range(5))
    assert chunks(text, final=True) == [" ".join([sentence(60)] * 3), " ".join([sentence(60)] * 2)]


def test_sentence_over_max_splits_at_words():
    text = sentence(3) + " " + sentence(25) + " " + sentence(3)
    result = chunks(text, final=True, lo=10, hi=10)

    assert all(words(c) < 20 for c in result)
    assert len(result) >= 3
    assert " ".join(result) == text


@pytest.mark.parametrize("text", ["", "   "])
def test_blank_transcript_has_no_chunks(text):
    assert chunks(text, final=True) == []
    assert chunks(text, final=False) == []


def test_unfinal_chunks_withhold_the_last_sentence():
    text = " ".join(sentence(70) for _ in range(3))
    assert chunks(text, final=False) == [" ".join([sentence(70)] * 2)]
    assert chunks(text, final=True) == [" ".join([sentence(70)] * 2), sentence(70)]


TEXTS = [
    " ".join(sentence(n, end=e) for n, e in [(7, "."), (31, "?"), (12, "."), (45, "!"), (9, "."), (28, "."), (60, ".")]),
    " ".join(sentence(n) for n in [100, 3, 150, 20, 5, 80, 130, 4]),
    "so um " + sentence(40) + " and then " + " ".join(sentence(n) for n in [33, 71, 12, 90]),
]


@pytest.mark.parametrize("text", TEXTS)
def test_live_chunks_are_a_prefix_of_final_for_every_slice_split(text):
    pieces = text.split()
    full = chunks(text, final=True, lo=40)
    for cuts in itertools.chain(((c,) for c in range(1, len(pieces))),
                                itertools.combinations(range(1, len(pieces), 17), 2)):
        bounds = (0, *cuts, len(pieces))
        slices = [" ".join(pieces[a:b]) for a, b in zip(bounds, bounds[1:])]
        for i in range(1, len(slices) + 1):
            partial = chunks(join_slices(slices[:i]), final=False, lo=40)
            assert partial == full[:len(partial)]
        assert chunks(join_slices(slices), final=True, lo=40) == full


@pytest.mark.parametrize("text, skipped", [
    ("", True),
    ("Yes.", True),
    ("meet Tuesday, actually no, Wednesday", False),
    ("Dinner is at seven I mean eight", False),
    ("a " * 41, False),
    ("a " * 40, True),
    ("this is a cannot be rather odd", False),
    ("the knot was nothing", True),
    ("Okay. Wait, what?", False),
])
def test_skip_rule(text, skipped):
    assert cleaner.skips_cleaner(text, CFG) is skipped


@pytest.mark.skipif(not paths.RECORDINGS.is_dir(), reason="no recordings")
def test_correction_takes_reach_the_cleaner():
    raws = sorted((paths.ASR / CFG["recognizer"]).glob("s2[5-9]-*.txt")) + \
        sorted((paths.ASR / CFG["recognizer"]).glob("s30-*.txt"))
    if len(raws) != 6:
        pytest.skip("s25-s30 not recognized yet")
    for raw in raws:
        assert not cleaner.skips_cleaner(raw.read_text(), CFG), raw.name


class StubCleaner(cleaner.Cleaner):
    def __init__(self):
        self.cfg = {**CFG, "chunk_min_tokens": 5, "skip_max_words": 2}
        self.spec = CFG["cleaners"]["s1-mini"]
        self.generated = []

    def count_tokens(self, text):
        return words(text)

    def _generate(self, prompt, max_tokens, cancelled=None):
        chunk = prompt.split("\n")[-6]
        self.generated.append(chunk)
        return chunk.upper(), 1


def test_chunk_outputs_join_with_a_blank_line():
    result = StubCleaner().clean(sentence(6) + " " + sentence(6))
    assert result.chunks == 2
    assert result.text.count("\n\n") == 1


def test_skipped_take_is_returned_as_recognized_without_generating():
    stub = StubCleaner()
    result = stub.clean("Hi there")
    assert (result.text, result.chunks, stub.generated) == ("Hi there", 0, [])


@pytest.mark.parametrize("text, expected", [
    ("Okay, I'm down.", "Okay I'm down"),
    ("Is it out?", "Is it out?"),
    ("It cost 1,000 dollars, I think.", "It cost 1,000 dollars I think"),
    ("Wait...", "Wait..."),
    ("Okay. Wait, what?", "Okay. Wait what?"),
    ("a, " * 41, "a, " * 41),
])
def test_casual_rule(text, expected):
    assert cleaner.casual(text, CFG) == expected
