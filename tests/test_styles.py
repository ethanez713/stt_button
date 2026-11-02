from stt_button import config


def test_given_the_pipeline_config_when_loaded_then_the_default_style_is_rendered_into_the_control_line():
    assert config.load()["cleaners"]["s1-mini"]["control_line"] == "[Styling: semi-formal] [Structure: lists] [Context: general]"
    assert config.load()["cleaners"]["qwen3.5-2b"]["control_line"] is None
