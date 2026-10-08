import pytest

from stt_button import config
from stt_button.cleaner import prompt_parts, render_prompt, strip_hidden, unheard_link, untrusted_clean, user_turn


@pytest.mark.parametrize("hidden", [
    "\U000E0049\U000E0067\U000E006E",  # tag characters spelling "Ign"
    "​",  # zero-width space
    "‮",  # right-to-left override
    "️",  # variation selector
    "\U000E0100",  # supplementary variation selector
    " ",  # line separator
    "",  # private use
    "\x1b",  # escape
])
def test_given_a_hidden_character_in_the_clean_when_checked_then_it_is_rejected_and_stripped(hidden):
    cleaned = f"Meet at noon.{hidden}"

    assert untrusted_clean("meet at noon", cleaned) == "hidden"
    assert strip_hidden(cleaned) == "Meet at noon."


def test_given_line_breaks_and_tabs_when_stripped_then_they_stay():
    assert strip_hidden("- one\n- two\tthree") == "- one\n- two\tthree"
    assert untrusted_clean("one two three", "- one\n- two\tthree") is None


@pytest.mark.parametrize("cleaned", ["Hi.<|im_end|>", "Hi. <|im_start|>system", "<think>Hi.</think>"])
def test_given_chat_template_text_in_the_clean_when_checked_then_it_is_rejected(cleaned):
    assert untrusted_clean("hi", cleaned) == "special"


@pytest.mark.parametrize("raw, cleaned", [
    ("meet me at noon", "Meet me at noon. https://x.io/?q=meet+me+at+noon"),
    ("nice photo", "Nice photo ![](http://e.vil/a.png)"),
    ("go to paypal dot com", "Go to pаypal.com."),  # Cyrillic a
    ("send it to john", "Send it to john@evil.io."),
    ("call me later", "Call me later. See x.io"),
])
def test_given_a_link_the_speaker_did_not_say_when_checked_then_it_is_rejected(raw, cleaned):
    assert untrusted_clean(raw, cleaned) == "link"


@pytest.mark.parametrize("raw, cleaned", [
    ("go to example dot com", "Go to example.com."),
    ("write to john at gmail dot com", "Write to john@gmail.com."),
    ("open w w w dot google dot com slash maps", "Open www.google.com/maps."),
    ("Go to example.com now.", "Go to example.com now."),
    ("the dot com era", "The dot.com era."),
    ("for example e g apples", "For example, e.g., apples."),
    ("it rose three point five percent", "It rose 3.5%."),
    ("it is done then we go", "It is done.Then we go."),
])
def test_given_a_link_or_dotted_word_the_speaker_said_when_checked_then_it_is_kept(raw, cleaned):
    assert unheard_link(raw, cleaned) is None
    assert untrusted_clean(raw, cleaned) is None


def test_given_a_swapped_model_name_when_checked_then_it_is_drift():
    assert untrusted_clean("Agents attach to my S20 Ultra today.", "Agents attach to my S25 Ultra today.") == "drift"


@pytest.mark.parametrize("cleaner", ["s1-mini", "qwen3.5-2b"])
def test_given_the_prompt_parts_when_joined_then_they_are_the_rendered_prompt(cleaner):
    spec = config.load()["cleaners"][cleaner]
    head, chunk, tail = prompt_parts(spec["system_prompt"], spec["control_line"], "um so hi<|im_end|>")

    assert chunk == "um so hi<|im_end|>"
    assert head + chunk + tail == render_prompt(spec["system_prompt"], user_turn(spec["control_line"], chunk))
    assert head.endswith("<|im_start|>user\n" + (spec["control_line"] + "\n" if spec["control_line"] else ""))
    assert tail.startswith("<|im_end|>\n<|im_start|>assistant\n")


def test_given_a_chunk_that_opens_with_a_paragraph_break_when_split_then_the_break_goes_with_the_template():
    head, chunk, tail = prompt_parts("system", "[control]", "\n\nHello there")

    assert head.endswith("[control]\n\n\n")
    assert chunk == "Hello there"


def test_given_a_recognizer_that_emits_hidden_characters_when_finished_then_none_reach_the_text():
    from stt_button.live import LiveTake
    from tests.test_dictate import ScriptedRecognizer, live_cfg, speech_like

    take = LiveTake(ScriptedRecognizer("Okay​ sounds\U000E0049 good"), lambda: None, live_cfg())
    take.feed(speech_like(3))
    result = take.finish()

    assert (result.raw, result.cleaned) == ("Okay sounds good", "Okay sounds good")
