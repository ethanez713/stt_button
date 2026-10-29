from stt_button import config
from stt_button.asr import SAMPLE_RATE
from stt_button.live import LiveTake, only_tail_fillers
from tests.make_vectors import LEAD, SPEECH, TAKE_CFG, ScriptedRecognizer, pcm_signal, run_take_script, to_float


def _script(name):
    from tests import make_vectors

    return next(script for script in make_vectors.TAKE_SCRIPTS if script["name"] == name)


def _feed(take, segments):
    audio = to_float(pcm_signal(segments))
    for at in range(0, len(audio), SAMPLE_RATE // 10):
        take.feed(audio[at:at + SAMPLE_RATE // 10])


def test_given_a_yeah_tail_after_speech_when_the_take_stops_then_it_is_dropped_and_the_earlier_mark_stays():
    assert run_take_script(_script("yeah_tail_after_speech_is_dropped"))["raw"] == "Meet me at noon."


def test_given_a_short_whole_take_okay_when_the_take_stops_then_the_reply_is_kept():
    assert run_take_script(_script("short_whole_take_okay_is_kept"))["raw"] == "Okay."


def test_given_a_short_tail_with_real_words_when_the_take_stops_then_the_words_are_kept():
    assert run_take_script(_script("short_tail_with_real_words_is_kept"))["raw"] == "Meet me at noon. See you tomorrow."


def test_given_a_two_second_yeah_tail_when_the_take_stops_then_it_is_kept():
    assert run_take_script(_script("yeah_tail_of_two_seconds_is_kept"))["raw"] == "Meet me at noon. Yeah."


def test_given_a_resumed_take_when_the_second_stop_decodes_okay_then_it_is_dropped():
    recognizer = ScriptedRecognizer([["Meet me at noon.", "."], ["Okay.", "?"]])
    take = LiveTake(recognizer, lambda: None, {**config.load(), **TAKE_CFG})
    _feed(take, [LEAD, SPEECH])
    take.stop()

    assert take.raw == "Meet me at noon."

    _feed(take, [["noise", 0.3, 8000]])
    take.finish()

    assert take.raw == "Meet me at noon."
    assert not recognizer.queue


def test_given_filler_words_with_punctuation_when_checked_then_only_the_list_matches():
    words = config.load()["tail_filler_words"]
    assert only_tail_fillers("Yeah.", words)
    assert only_tail_fillers("Mm-hmm.", words)
    assert only_tail_fillers("Uh-huh,", words)
    assert only_tail_fillers("OK!", words)
    assert only_tail_fillers("Yeah, okay.", words)
    assert not only_tail_fillers("Yeah, sure.", words)
    assert not only_tail_fillers("...", words)
    assert not only_tail_fillers("", words)
