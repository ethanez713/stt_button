import numpy as np

from stt_button.asr import SAMPLE_RATE, Slicer, context_audio, split_at_junction


def test_given_context_and_slice_words_when_split_at_the_junction_then_the_context_mark_and_the_slice_text_come_back():
    tokens = [" update", " the", " plan", ".", " have", " some", " monitoring"]
    times = [0.1, 0.4, 0.7, 0.9, 2.3, 2.6, 2.9]

    assert split_at_junction(tokens, times, 2.0) == (".", "have some monitoring")


def test_given_a_context_that_ends_without_punctuation_when_split_then_the_mark_is_empty():
    assert split_at_junction([" a", " b", " c"], [0.1, 0.5, 2.5], 2.0) == ("", "c")


def test_given_no_words_after_the_junction_when_split_then_the_text_is_empty():
    assert split_at_junction([" a", " b"], [0.1, 0.5], 2.0) == ("", "")


def test_given_a_long_pause_when_the_context_audio_is_squeezed_then_it_holds_context_gap_and_slice():
    audio = np.arange(10 * SAMPLE_RATE, dtype=np.float32)
    before, after, end = 4 * SAMPLE_RATE, 8 * SAMPLE_RATE, 10 * SAMPLE_RATE

    squeezed, junction = context_audio(audio, before, after, end, context_s=2, squeeze_s=0.2)
    kept, kept_junction = context_audio(audio, before, after, end, context_s=2, squeeze_s=None)

    lead = int(0.1 * SAMPLE_RATE)
    assert len(squeezed) == 2 * SAMPLE_RATE + int(0.2 * SAMPLE_RATE) + (end - (after - lead))
    assert junction == (2 * SAMPLE_RATE + int(0.2 * SAMPLE_RATE)) / SAMPLE_RATE
    assert squeezed[0] == audio[2 * SAMPLE_RATE] and squeezed[-1] == audio[end - 1]
    assert len(kept) == end - 2 * SAMPLE_RATE
    assert kept_junction == (after - lead - 2 * SAMPLE_RATE) / SAMPLE_RATE


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


def test_given_a_context_decode_with_no_words_after_the_junction_when_decoded_then_it_falls_back_to_a_plain_decode():
    recognizer = ContextRecognizer("One sentence.", ("", ""))
    take = two_slice_take(recognizer)
    take.finish()

    assert take.raw == "One sentence. plain fallback"
    assert (take.context_decodes, take.context_fallbacks) == (1, 1)


def test_given_a_closing_mark_rewritten_by_the_next_slice_when_chunked_then_the_early_chunks_are_still_a_prefix():
    from stt_button.cleaner import live_chunks

    count = lambda text: len(text.split())  # noqa: E731
    first = "One two three four. Five six seven eight. Nine ten eleven twelve."
    rewritten_join = "One two three four. Five six seven eight. Nine ten eleven twelve thirteen fourteen fifteen."

    early = live_chunks(first, count, 6, 100, final=False)
    final = live_chunks(rewritten_join, count, 6, 100, final=True)

    assert early and final[:len(early)] == early
