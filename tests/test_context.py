import numpy as np

from stt_button.asr import SAMPLE_RATE, Slicer, context_audio, continue_fragment, drop_overlap, settled_mark, split_at_junction


def test_given_context_and_slice_words_when_split_at_the_junction_then_the_context_mark_and_the_slice_text_come_back():
    tokens = [" update", " the", " plan", ".", " have", " some", " monitoring"]
    times = [0.1, 0.4, 0.7, 0.9, 2.3, 2.6, 2.9]

    assert split_at_junction(tokens, times, 2.0) == (".", "have some monitoring")


def test_given_a_context_that_ends_without_punctuation_when_split_then_the_mark_is_empty():
    assert split_at_junction([" a", " b", " c"], [0.1, 0.5, 2.5], 2.0) == ("", "c")


def test_given_no_words_after_the_junction_when_split_then_the_text_is_empty():
    assert split_at_junction([" a", " b"], [0.1, 0.5], 2.0) == ("", "")


def test_given_a_slice_when_context_audio_is_taken_then_it_is_the_unbroken_run_back_through_context_s():
    audio = np.arange(10 * SAMPLE_RATE, dtype=np.float32)
    start, end = 4 * SAMPLE_RATE, 10 * SAMPLE_RATE

    taken, junction = context_audio(audio, start, end, 2)

    assert len(taken) == 8 * SAMPLE_RATE
    assert junction == 2
    assert taken[0] == audio[2 * SAMPLE_RATE] and taken[-1] == audio[end - 1]
    assert taken[int(junction * SAMPLE_RATE)] == audio[start]


def test_given_a_slice_near_the_start_when_context_audio_is_taken_then_it_begins_at_the_first_sample():
    audio = np.arange(10 * SAMPLE_RATE, dtype=np.float32)
    start, end = SAMPLE_RATE, 6 * SAMPLE_RATE

    taken, junction = context_audio(audio, start, end, 4)

    assert len(taken) == end and junction == 1
    assert taken[0] == 0 and taken[-1] == audio[end - 1]


def test_given_a_pause_when_the_slicer_is_asked_then_it_finds_the_speech_around_it():
    rng = np.random.default_rng(0)

    def speech(seconds):
        return rng.integers(-8000, 8000, int(seconds * SAMPLE_RATE)).astype(np.int16)

    silence = lambda seconds: np.zeros(int(seconds * SAMPLE_RATE), dtype=np.int16)  # noqa: E731
    pcm = np.concatenate([silence(1.5), speech(3), silence(2.0), speech(2)])
    slicer = Slicer(30, 5, 0.8)
    slicer.feed(pcm.astype(np.float32) / 32768)

    assert slicer.speech_before(int(5.0 * SAMPLE_RATE)) == int(4.5 * SAMPLE_RATE)
    assert slicer.speech_after(int(4.5 * SAMPLE_RATE)) == int(6.5 * SAMPLE_RATE)
    assert slicer.speech_before(int(1.0 * SAMPLE_RATE)) is None
    assert slicer.speech_after(int(8.6 * SAMPLE_RATE)) is None


class ContextRecognizer:
    def __init__(self, first, *after):
        self.first, self.after = first, list(after)
        self.plain_calls, self.context_calls = 0, []

    def recognize(self, samples):
        self.plain_calls += 1
        return self.first if self.plain_calls == 1 else "plain fallback"

    def recognize_after(self, audio, junction_s):
        self.context_calls.append((len(audio), junction_s))
        return self.after.pop(0)


def two_slice_take(recognizer):
    from stt_button import config
    from stt_button.live import LiveTake

    rng = np.random.default_rng(1)
    speech = lambda s: rng.integers(-8000, 8000, int(s * SAMPLE_RATE)).astype(np.int16)  # noqa: E731
    silence = lambda s: np.zeros(int(s * SAMPLE_RATE), dtype=np.int16)  # noqa: E731
    pcm = np.concatenate([silence(1.0), speech(4), silence(3.0), speech(4)])
    take = LiveTake(recognizer, lambda: None, {**config.load(), "skip_max_words": 99})
    take.feed(pcm.astype(np.float32) / 32768)
    return take


def test_given_a_pause_when_the_second_slice_is_decoded_then_it_gets_context_and_the_first_slice_takes_the_context_mark():
    recognizer = ContextRecognizer("Update the plan in this project to.", ("", "have some monitoring"))
    take = two_slice_take(recognizer)
    take.finish()

    assert recognizer.plain_calls == 1 and len(recognizer.context_calls) == 1
    assert take.raw == "Update the plan in this project to have some monitoring"
    assert (take.context_decodes, take.context_fallbacks) == (1, 0)


def test_given_a_context_decode_that_ends_a_sentence_when_joined_then_the_period_stays():
    take = two_slice_take(ContextRecognizer("Update the plan.", (".", "Have some monitoring")))
    take.finish()

    assert take.raw == "Update the plan. Have some monitoring"


def test_given_text_already_when_the_stop_closes_a_silent_tail_then_the_tail_is_decoded_with_context():
    recognizer = ContextRecognizer("Update the plan.", ("", "and more"))
    rng = np.random.default_rng(1)
    speech = lambda s: rng.integers(-8000, 8000, int(s * SAMPLE_RATE)).astype(np.int16)  # noqa: E731
    silence = lambda s: np.zeros(int(s * SAMPLE_RATE), dtype=np.int16)  # noqa: E731
    pcm = np.concatenate([silence(0.3), speech(3), silence(3.0)])
    from stt_button import config
    from stt_button.live import LiveTake

    take = LiveTake(recognizer, lambda: None, {**config.load(), "skip_max_words": 99})
    take.feed(pcm.astype(np.float32) / 32768)
    take.finish()

    assert recognizer.plain_calls == 1 and len(recognizer.context_calls) == 1
    assert take.raw == "Update the plan and more"


def test_given_a_slice_that_repeats_the_take_when_the_context_decode_settles_the_mark_then_it_adds_nothing():
    take = two_slice_take(ContextRecognizer("Hello world.", ("", "Hello world.")))
    take.finish()

    assert take.raw == "Hello world"
    assert take.texts == ["Hello world", ""]


def test_given_words_repeated_across_the_junction_when_the_context_slice_joins_then_the_run_is_dropped():
    take = two_slice_take(ContextRecognizer("of using.", ("", "of using an MIT")))
    take.finish()

    assert take.raw == "of using an MIT"


def test_given_a_context_decode_with_no_words_after_the_junction_when_decoded_then_it_falls_back_to_a_plain_decode():
    recognizer = ContextRecognizer("One sentence.", ("", ""))
    take = two_slice_take(recognizer)
    take.finish()

    assert take.raw == "One sentence plain fallback"
    assert (take.context_decodes, take.context_fallbacks) == (1, 1)


def test_given_words_the_take_already_ends_with_when_the_next_slice_opens_with_them_then_the_run_is_dropped():
    assert drop_overlap("of using", "using an MIT") == "an MIT"
    assert drop_overlap("from my", "from my work") == "work"
    assert drop_overlap("call", "Call both of them") == "both of them"
    assert drop_overlap("hello world", "something else") == "something else"
    assert drop_overlap("one two three four five", "one two three four five rest") == "five rest"
    assert drop_overlap("the license?", "License terms") == "terms"
    assert continue_fragment("work", "from my work today", "from my work") == ("work", "today")


def test_given_a_slice_decoded_on_its_own_when_joined_then_the_boundary_is_not_a_new_sentence():
    assert continue_fragment("store it in.", "Mom and dad's") == ("store it in", "mom and dad's")
    assert continue_fragment("seems to buffer.", "Text for way too long.") == ("seems to buffer", "text for way too long.")
    assert continue_fragment("so.", "I'm late") == ("so", "I'm late")
    assert continue_fragment("bought an.", "iPhone") == ("bought an", "iPhone")
    assert continue_fragment("the world?", "Where do we put it") == ("the world?", "Where do we put it")
    assert continue_fragment("call", "Call both of them") == ("call", "both of them")
    assert continue_fragment("call.", "Call me") == ("call", "me")
    assert continue_fragment("See you.", "And then we left") == ("See you", "and then we left")
    assert continue_fragment("Wait...", "And more") == ("Wait...", "And more")
    assert continue_fragment("hello.", "hello") == ("hello.", "")
    assert continue_fragment("of using", "using an MIT") == ("of using", "an MIT")
    assert continue_fragment("from my.", "from my work") == ("from my", "work")
    assert continue_fragment("one two three four five.", "One two three four five rest") == (
        "one two three four five", "five rest")
    assert continue_fragment("a b c d.", "A b c d") == ("a b c d.", "")


def test_given_a_closing_mark_rewritten_by_the_next_slice_when_chunked_then_the_early_chunks_are_still_a_prefix():
    from stt_button.cleaner import live_chunks

    count = lambda text: len(text.split())  # noqa: E731
    first = "One two three four. Five six seven eight. Nine ten eleven twelve."
    rewritten_join = "One two three four. Five six seven eight. Nine ten eleven twelve thirteen fourteen fifteen."

    early = live_chunks(first, count, 6, 100, final=False)
    final = live_chunks(rewritten_join, count, 6, 100, final=True)

    assert early and final[:len(early)] == early


def test_given_a_question_mark_from_the_slice_when_the_context_decode_ends_the_sentence_then_the_question_mark_stays():
    assert settled_mark("?", ".") == "?"
    assert settled_mark("!", ".") == "!"


def test_given_a_question_mark_from_the_slice_when_the_context_decode_continues_the_sentence_then_it_goes():
    assert settled_mark("?", "") == ""
    assert settled_mark("?", ",") == ","


def test_given_a_full_stop_from_the_slice_when_the_context_decode_hears_a_question_then_the_question_mark_wins():
    assert settled_mark(".", "?") == "?"
    assert settled_mark("", ".") == "."
