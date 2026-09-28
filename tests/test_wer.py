from experiments import wer


def test_normalize_drops_case_and_punctuation_keeps_apostrophes():
    assert wer.normalize("Hey, you'd rather stay-in? OK!") == ["hey", "you'd", "rather", "stay", "in", "ok"]


def test_quotes_are_not_words():
    assert wer.normalize("'quoted' ' word") == ["quoted", "word"]


def test_numbers_removed_both_ways():
    spoken = wer.normalize("Meet at seven thirty, suite three hundred.")
    written = wer.normalize("Meet at 7:30, suite 300.")
    assert wer.without_numbers(spoken) == wer.without_numbers(written) == ["meet", "at", "suite"]


def test_edit_operations_each_cost_one():
    ref = "the cat sat down".split()
    assert wer.word_errors(ref, ref) == 0
    assert wer.word_errors(ref, "the cat sat right down".split()) == 1  # insertion
    assert wer.word_errors(ref, "the sat down".split()) == 1  # deletion
    assert wer.word_errors(ref, "the dog sat down".split()) == 1  # substitution


def test_empty_sides():
    assert wer.word_errors("a b c".split(), []) == 3
    assert wer.word_errors([], "a b".split()) == 2
    assert wer.word_errors([], []) == 0
