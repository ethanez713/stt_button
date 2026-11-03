import json

from stt_button import config


def test_given_no_names_file_when_loaded_then_the_name_map_is_empty(monkeypatch, tmp_path):
    monkeypatch.setattr(config, "NAMES_JSON", tmp_path / "names.json")

    assert config.load()["name_map"] == []


def test_given_an_empty_names_file_when_loaded_then_the_name_map_is_empty(monkeypatch, tmp_path):
    path = tmp_path / "names.json"
    path.write_text("[]")
    monkeypatch.setattr(config, "NAMES_JSON", path)

    assert config.load()["name_map"] == []


def test_given_a_names_file_when_loaded_then_its_pairs_are_the_name_map(monkeypatch, tmp_path):
    path = tmp_path / "names.json"
    path.write_text(json.dumps([["acme park", "Acme Park"], ["zorbo", "Zorbo Labs"]]))
    monkeypatch.setattr(config, "NAMES_JSON", path)

    assert config.load()["name_map"] == [["acme park", "Acme Park"], ["zorbo", "Zorbo Labs"]]
