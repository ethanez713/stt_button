from stt_button.cleaner import fact_drift


def test_given_a_model_name_swapped_for_a_near_one_when_compared_then_the_pair_is_reported():
    assert fact_drift("Agents attach to my S20 Ultra today.", "Agents attach to my S25 Ultra today.") == [("s20", "s25")]


def test_given_a_swap_among_names_the_raw_text_already_held_when_compared_then_the_counts_show_it():
    raw = "It is my S20 Ultra, not the S25 Ultra. Put it on the S20 Ultra."

    assert fact_drift(raw, "It is my S25 Ultra, not the S25 Ultra. Put it on the S25 Ultra.") == [("s20", "s25")]


def test_given_a_name_respelled_when_compared_then_it_is_drift():
    assert fact_drift("I want AB Connect to have it.", "I want ADB Connect to have it.") == [("ab", "adb")]


def test_given_a_capital_that_only_opens_a_sentence_when_lowercased_then_it_is_not_drift():
    assert fact_drift("So the plan is simple. Wake the phone first.", "So the plan is simple. wake the phone first.") == []


def test_given_a_stray_mid_sentence_capital_when_lowercased_then_it_is_not_drift():
    assert fact_drift("I see a Wake permission here.", "I see a wake permission here.") == []


def test_given_a_spoken_number_when_written_as_digits_then_it_is_not_drift():
    assert fact_drift("meet at Twelfth at five", "meet at 12th at 5") == []


def test_given_a_correction_between_digits_the_raw_text_holds_when_resolved_then_it_is_not_drift():
    assert fact_drift("meet at 4. Actually no, make it 5.", "Meet at 5.") == []


def test_given_a_dropped_name_with_nothing_close_gained_when_compared_then_it_is_not_drift():
    assert fact_drift("a letter See then", "a letter C then") == []


def test_given_first_person_forms_when_expanded_then_they_are_not_facts():
    assert fact_drift("Then I'm sure I'll go", "Then I am sure I will go") == []


def test_given_edge_punctuation_when_compared_then_it_does_not_change_the_token():
    assert fact_drift("It costs $5, I think.", "It costs $5. I think.") == []
