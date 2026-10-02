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
