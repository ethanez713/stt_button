import json

from stt_button.bubble import Bubble, SharedBubble, words_from_tokens


def test_given_preview_words_when_a_slice_lands_then_words_inside_it_are_dropped_and_later_ones_survive():
    bubble = Bubble()
    bubble.preview([("meet", 0.4), ("me", 0.9), ("at", 1.5), ("noon", 2.1), ("okay", 3.2)])
    assert bubble.text() == ("", "meet me at noon okay")

    bubble.slice_landed(2.5, "Meet me at noon.")

    assert bubble.text() == ("Meet me at noon.", "okay")


def test_given_a_cleaned_chunk_when_announced_then_it_replaces_exactly_its_raw_sentences():
    bubble = Bubble()
    bubble.slice_landed(10.0, "First thing. Second thing. Third thing.")
    bubble.chunk_cleaned("First thing. Second thing.", "1. First\n2. Second")

    assert bubble.text()[0] == "1. First\n2. Second\n\nThird thing."


def test_given_a_later_slice_when_it_lands_then_raw_text_follows_the_cleaned_chunks():
    bubble = Bubble()
    bubble.slice_landed(10.0, "First thing. Second thing.")
    bubble.chunk_cleaned("First thing. Second thing.", "Cleaned.")
    bubble.slice_landed(20.0, "Third thing.")

    assert bubble.text()[0] == "Cleaned.\n\nThird thing."


def test_given_streaming_tokens_when_grouped_then_a_leading_space_starts_a_word():
    words = words_from_tokens([" the", " re", "min", "d", "er", " for"], [1.0, 1.2, 1.3, 1.4, 1.5, 1.7])
    assert words == [("the", 1.0), ("reminder", 1.2), ("for", 1.7)]


def test_given_updates_when_shared_then_the_file_holds_the_latest_text(tmp_path):
    path = tmp_path / "t.bubble.json"
    bubble = SharedBubble(path, min_interval_s=0)
    bubble.preview([("hello", 0.5)])
    bubble.slice_landed(1.0, "Hello.")
    bubble.preview([("hello", 0.5), ("there", 1.5)])

    assert json.loads(path.read_text()) == {"settled": "Hello.", "preview": "there"}
