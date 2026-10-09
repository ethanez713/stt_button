import pytest

from stt_button import config
from stt_button.cleaner import Cleaner
from stt_button.spoken import spoken_punctuation
from tests.test_dictate import ScriptedRecognizer, live_cfg, speech_like


@pytest.mark.parametrize("spoken, written", [
    ("He said quote hello world, unquote and left", 'He said "hello world" and left'),
    ("He said quote unquote hello, and left", 'He said "hello", and left'),
    ("the architecture parentheses, which is decompiled close parentheses To see", "the architecture (which is decompiled) To see"),
    ("set x open parenthesis y close parenthesis.", "set x (y)."),
    ("Is it ready, question mark yes it is", "Is it ready? Yes it is"),
    ("viewing, question mark.", "viewing?"),
    ("wow exclamation point that is great", "wow! That is great"),
    ("one new line two new paragraph three", "one\ntwo\n\nThree"),
    ("open quote only here", "open quote only here"),
    ("a quote from the contractor", "a quote from the contractor"),
    ("a period of time", "a period of time"),
    ("the end. Quote of the day", "the end. Quote of the day"),
    ("close paren stray", "close paren stray"),
    ("open paren never closed", "open paren never closed"),
    ("things like quote unquote or open close parentheses? Those get inserted",
     "things like quote unquote or open close parentheses? Those get inserted"),
    ("the quote unquote And operator", "the quote unquote And operator"),
])
def test_given_spoken_commands_when_converted_then_symbols_replace_them_and_plain_text_is_untouched(spoken, written):
    assert spoken_punctuation(spoken) == written


@pytest.mark.parametrize("spoken, written", [
    ("like little stars, not stars, white stars on top", "like little white stars on top"),
    ("get the Titan Eco not eco economy plates", "get the Titan economy plates"),
    ("the trip. We lay not lay, we land Monday", "the trip. We land Monday"),
    ("sure that it's not a fraudulent Not fraudulent. Fraud attempt.", "sure that it's not a fraud attempt."),
    ("try to recreate, not recreate, reproduce this bug", "try to reproduce this bug"),
    ("run on-device texts, not texts, tests to measure", "run on-device tests to measure"),
    ("simple, straightforward, low, not low, high certainty fixes", "simple, straightforward, high certainty fixes"),
    ("book it Monday, not Monday, Tuesday at five", "book it Tuesday at five"),
    ("like it or not, not everyone agrees", "like it or not, not everyone agrees"),
    ("that is simply not", "that is simply not"),
    ("it is not not working", "it is not not working"),
    ("it is x, not x", "it is x, not x"),
])
def test_given_a_phrase_restated_after_not_when_converted_then_the_later_phrase_replaces_it(spoken, written):
    assert spoken_punctuation(spoken) == written


@pytest.mark.parametrize("spoken, written", [
    ("a very high markup slash premium and that", "a very high markup/premium and that"),
    ("in landscaping/slash botany?", "in landscaping/botany?"),
    ("the mythos slash fable class", "the mythos/fable class"),
    ("fold them into the slash autopilot skill", "fold them into the /autopilot skill"),
    ("Run it, slash compact now", "Run it, /compact now"),
    ("an on hyphen device test", "an on-device test"),
    ("wait dash no", "wait -- no"),
    ("wait, dash no", "wait -- no"),
    ("open parentheses all green closed parentheses, and then", "(all green), and then"),
    ("say closed quote hello", "say closed quote hello"),
    ("punctuation marks like slash, hyphen, and so on", "punctuation marks like slash, hyphen, and so on"),
    ("marks like slash hyphen and so on", "marks like slash hyphen and so on"),
    ("grab a dash of salt", "grab a dash of salt"),
    ("mount the dash cam", "mount the dash cam"),
    ("put a hyphen in it", "put a hyphen in it"),
    ("a hyphen", "a hyphen"),
    ("it ends with slash", "it ends with slash"),
    ("slash", "slash"),
])
def test_given_spoken_joining_marks_when_converted_then_they_join_only_where_a_mark_is_meant(spoken, written):
    assert spoken_punctuation(spoken) == written


def test_given_a_closer_beyond_thirty_words_when_converted_then_the_quote_stays_words():
    text = "she said quote " + "word " * 31 + "unquote"
    assert spoken_punctuation(text) == text


def test_given_text_with_no_command_when_converted_then_it_comes_back_unchanged():
    text = "two  spaces\tand a tab"
    assert spoken_punctuation(text) is text


def test_given_a_short_take_with_a_command_when_finished_then_the_raw_path_inserts_the_symbol():
    from stt_button.live import LiveTake

    take = LiveTake(ScriptedRecognizer("Is it ready question mark"), lambda: None, live_cfg())
    take.feed(speech_like(3))

    assert take.finish().cleaned == "Is it ready?"


def test_given_a_chunk_with_a_command_when_cleaned_then_the_model_sees_the_symbol():
    cleaner = Cleaner.__new__(Cleaner)
    cleaner.spec = config.load()["cleaners"]["s1-mini"]
    cleaner.count_tokens = lambda text: len(text.split())
    prompts = []
    cleaner._generate = lambda prompt, limit, cancelled: (prompts.append(prompt), ("ok", 1))[1]

    cleaner.clean_chunk("He said quote hi unquote and left")

    assert 'He said "hi" and left' in prompts[0]


def test_given_a_dictated_question_mark_at_the_end_when_the_cleaner_writes_a_full_stop_then_the_mark_comes_back():
    from stt_button.spoken import keep_spoken_ending

    assert keep_spoken_ending("ask if anyone wants it sooner question mark", "Ask if anyone wants it sooner.") == "Ask if anyone wants it sooner?"
    assert keep_spoken_ending("Is it ready?", "Is it ready.") == "Is it ready."
    assert keep_spoken_ending("is it ready question mark yes it is", "Is it ready? Yes, it is.") == "Is it ready? Yes, it is."
