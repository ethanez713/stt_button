from stt_button import cleaner, config


def test_given_a_control_line_template_when_the_prompt_is_split_then_the_default_style_is_rendered():
    template = "[Styling: {styling}] [Structure: {structure}] [Context: general]"

    head, chunk, _ = cleaner.prompt_parts("system", template, "um so hi")

    assert chunk == "um so hi"
    assert head.endswith("[Styling: semi-formal] [Structure: lists] [Context: general]\n")
    assert cleaner.user_turn(template, "um so hi") == "[Styling: semi-formal] [Structure: lists] [Context: general]\num so hi"


def test_given_a_rendered_control_line_when_the_user_turn_is_built_then_it_is_kept():
    line = "[Styling: semi-casual] [Structure: lists] [Context: general]"

    assert cleaner.user_turn(line, "hi") == line + "\nhi"
    assert config.load()["cleaners"]["s1-mini"]["control_line"] == "[Styling: semi-formal] [Structure: lists] [Context: general]"
    assert config.load()["cleaners"]["qwen3.5-2b"]["control_line"] is None
