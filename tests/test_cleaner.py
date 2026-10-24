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
        self.backend = "llama"
        self.generated = []

    def count_tokens(self, text):
        return words(text)

    def _generate(self, parts, max_tokens, cancelled=None):
        chunk = parts[1]
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


@pytest.mark.parametrize("text, expected", [
    ("See you at five.", "See you at five"),
    ("See you at five!", "See you at five"),
    ("See you at five?", "See you at five?"),
    ("See you at five...", "See you at five..."),
    ("See you at five,", "See you at five"),
    ("hello!.", "hello"),
    ("Wait...", "Wait..."),
])
def test_given_a_message_when_its_end_is_trimmed_then_only_the_trailing_punctuation_goes(text, expected):
    assert cleaner.trim_message_end(text) == expected


@pytest.mark.parametrize("text, expected", [
    ("I need to decide. Okay.", "I need to decide."),
    ("Even more so than they already were. Yeah.", "Even more so than they already were."),
    ("easy enough for us to hop to Um", "easy enough for us to hop to"),
    ("Right. Okay, yeah.", "Right."),
    ("so, um.", "so."),
    ("fine. Ok. Um", "fine."),
    ("ends.\n\nOkay.", "ends."),
    ("Is that okay?", "Is that okay?"),
    ("Do it? Okay?", "Do it? Okay?"),
    ("it thinks I'll Yeah.", "it thinks I'll"),
    ("I added about 1.25 cups of percarbonate powder Yeah.", "I added about 1.25 cups of percarbonate powder"),
    ("The impact will be. Mm.", "The impact will be."),
    ("that's okay", "that's okay"),
    ("Okay.", "Okay."),
    ("Um", "Um"),
])
def test_given_a_take_that_trails_off_when_signed_off_then_only_the_trailing_okay_or_um_goes(text, expected):
    assert cleaner.without_sign_off(text) == expected


CUES = ["actually", "i mean", "no", "not"]


def test_given_a_sentence_that_opens_with_a_cue_when_chunked_then_it_and_the_sentence_before_form_their_own_chunk():
    text = "a b c d. e f g h. Actually no, make that i. j k l m. n o p q."

    assert cleaner.live_chunks(text, lambda t: len(t.split()), 8, 100, final=True, cues=CUES) == [
        "a b c d.", "e f g h. Actually no, make that i.", "j k l m. n o p q."]


def test_given_a_cue_inside_a_sentence_when_chunked_then_no_unit_is_cut():
    text = "a b c d. e is not f g. h i j k."

    assert cleaner.live_chunks(text, lambda t: len(t.split()), 8, 100, final=True, cues=CUES) == [
        "a b c d. e is not f g.", "h i j k."]


def test_given_fillers_before_the_cue_when_checked_then_the_sentence_still_opens_with_it():
    assert cleaner.opens_with_cue("Uh, or, I mean eight.", CUES)
    assert cleaner.opens_with_cue("No, five.", CUES)
    assert not cleaner.opens_with_cue("Also, no need to wait.", CUES)
    assert not cleaner.opens_with_cue("Nothing else.", CUES)


def test_given_two_corrections_in_a_row_when_chunked_then_they_share_one_unit():
    text = "a b c d. Actually e. No, f. g h."

    assert cleaner.live_chunks(text, lambda t: len(t.split()), 3, 100, final=True, cues=CUES) == [
        "a b c d. Actually e. No, f.", "g h."]


def test_given_a_withheld_sentence_that_opens_with_a_cue_when_not_final_then_the_sentence_before_waits_for_it():
    count = lambda t: len(t.split())

    assert cleaner.live_chunks("a b c d. e f g h. Actually no make", count, 4, 100, final=False, cues=CUES) == ["a b c d."]
    assert cleaner.live_chunks("a b c d. e f g h. And then we go", count, 4, 100, final=False, cues=CUES) == ["a b c d.", "e f g h."]
    assert cleaner.live_chunks("a b c d. e f g h. I", count, 4, 100, final=False, cues=CUES) == ["a b c d."]


@pytest.mark.parametrize("text", [
    "a b c d. e f g h. Actually no, make that i. j k l m. n o p q. I mean r. s t u v w x y z.",
    "No, a b. c d e f g. h i. Not j, k. l m n o p q r s t.",
])
def test_given_cues_when_text_grows_word_by_word_then_early_chunks_are_a_prefix_of_the_final_ones(text):
    count = lambda t: len(t.split())
    words = text.split()
    full = cleaner.live_chunks(text, count, 6, 100, final=True, cues=CUES)
    for n in range(1, len(words) + 1):
        partial = cleaner.live_chunks(" ".join(words[:n]), count, 6, 100, final=False, cues=CUES)
        assert partial == full[:len(partial)], (n, partial)


def test_given_a_correction_unit_when_joined_then_it_sits_inline_and_other_seams_are_paragraph_breaks():
    chunks = ["a b.", "c d.", "e f. Actually g.", "h i."]

    assert cleaner.join_cleaned(chunks, ["A b.", "C d.", "E g.", "H i."], CUES) == "A b.\n\nC d. E g. H i."


def test_given_a_list_before_a_correction_unit_when_joined_then_a_blank_line_keeps_them_apart():
    assert cleaner.join_cleaned(["a.", "e f. Actually g."], ["Pack:\n- X\n- Y", "E g."], CUES) == "Pack:\n- X\n- Y\n\nE g."


def test_given_an_empty_cleaned_chunk_when_joined_then_it_leaves_no_seam():
    assert cleaner.join_cleaned(["um.", "a b."], ["", "A b."], CUES) == "A b."


def test_given_a_correction_the_cleaner_left_standing_when_counted_then_it_is_reported_as_kept():
    chunks = ["a b.", "Order two. Actually make it three.", "Meet at four. No, five."]
    outputs = ["A b.", "Order two. Actually, make it three.", "Meet at 5."]

    assert cleaner.unresolved_corrections(chunks, outputs, CUES) == (2, 1)
