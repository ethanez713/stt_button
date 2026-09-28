import pytest

from stt_button import cleaner, config


def words(text):
    return len(text.split())


def sentence(n_words, end="."):
    return " ".join(["word"] * (n_words - 1) + ["last" + end])


def test_s1_prompt_is_the_training_format():
    spec = config.load()["cleaners"]["s1-mini"]
    prompt = cleaner.render_prompt(spec["system_prompt"], cleaner.user_turn(spec["control_line"], "um so hi"))

    assert prompt == (
        "<|im_start|>system\n"
        "You are a text normalizer for speech-to-text transcripts. The input begins with a control line "
        "specifying the styling, structure, and context settings; clean the transcript to match those "
        "settings and output only the cleaned text.<|im_end|>\n"
        "<|im_start|>user\n"
        "[Styling: semi-formal] [Structure: lists] [Context: general]\n"
        "um so hi<|im_end|>\n"
        "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    )


def test_qwen_turn_has_no_control_line():
    spec = config.load()["cleaners"]["qwen3.5-2b"]
    assert cleaner.user_turn(spec["control_line"], "um so hi") == "um so hi"


def test_max_new_tokens_follows_model_card():
    assert cleaner.max_new_tokens(1000, 1.3, 32) == 1332
    assert cleaner.max_new_tokens(0, 1.3, 32) == 32


def test_configured_limit_boundary():
    limit = config.load()["chunk_max_tokens"]
    exactly = " ".join(sentence(100) for _ in range(limit // 100))
    assert cleaner.chunk_transcript(exactly, words, limit) == [exactly]

    one_over = exactly + " " + sentence(1)
    chunks = cleaner.chunk_transcript(one_over, words, limit)
    assert chunks == [exactly, sentence(1)]


def test_chunks_keep_order_and_every_word():
    text = " ".join(sentence(4, end=p) for p in ".?!.?!")
    chunks = cleaner.chunk_transcript(text, words, limit=9)

    assert all(words(c) <= 9 for c in chunks)
    assert " ".join(chunks) == text
    assert len(chunks) == 3


def test_overlong_sentence_splits_at_words():
    text = sentence(3) + " " + sentence(25) + " " + sentence(3)
    chunks = cleaner.chunk_transcript(text, words, limit=10)

    assert all(words(c) <= 10 for c in chunks)
    assert " ".join(chunks) == text


@pytest.mark.parametrize("text", ["", "   "])
def test_blank_transcript_has_no_chunks(text):
    assert cleaner.chunk_transcript(text, words, limit=10) == []
