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
