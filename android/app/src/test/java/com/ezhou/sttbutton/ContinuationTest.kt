package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationTest {
    private fun after(before: String, body: String) = Pipeline.continuing(body, before, before.length)

    @Test
    fun given_text_ending_mid_sentence_when_a_take_follows_then_its_opening_capital_drops() {
        assertEquals("monthly services for these", after("Help me find some", "Monthly services for these"))
    }

    @Test
    fun given_text_ending_a_sentence_when_a_take_follows_then_its_capital_stays() {
        assertEquals("Archive the rest.", after("Reply to the aws email (a) and star it.", "Archive the rest."))
        assertEquals("Archive the rest.", after("Is that all?", "Archive the rest."))
    }

    @Test
    fun given_a_full_stop_inside_a_closing_quote_when_a_take_follows_then_its_capital_stays() {
        assertEquals("Then we left.", after("He said \"okay.\" ", "Then we left."))
    }

    @Test
    fun given_a_line_break_before_the_caret_when_a_take_follows_then_its_capital_stays() {
        assertEquals("Next item", after("first item\n", "Next item"))
    }

    @Test
    fun given_an_empty_field_or_a_bare_bullet_when_a_take_follows_then_its_capital_stays() {
        assertEquals("Hello there", after("", "Hello there"))
        assertEquals("Hello there", after("- ", "Hello there"))
    }

    @Test
    fun given_an_opening_bracket_before_the_caret_when_a_take_follows_then_its_capital_stays() {
        assertEquals("Probably this", after("the app (", "Probably this"))
    }

    @Test
    fun given_a_first_person_or_inner_capital_word_when_a_take_continues_then_it_keeps_its_capital() {
        assertEquals("I think so", after("and then", "I think so"))
        assertEquals("I'm sure", after("and then", "I'm sure"))
        assertEquals("LLMs people give shell access to", after("a world of", "LLMs people give shell access to"))
        assertEquals("iPhone is fine", after("my", "iPhone is fine"))
    }

    @Test
    fun given_a_caret_inside_the_text_when_a_take_follows_then_only_the_text_before_it_counts() {
        assertEquals("Monthly services", Pipeline.continuing("Monthly services", "Done. then later", 5))
        assertEquals("monthly services", Pipeline.continuing("Monthly services", "Done. then later", 14))
    }

    @Test
    fun given_a_quote_before_a_lead_when_a_take_continues_then_the_letter_after_the_quote_drops() {
        assertEquals("\"quoted\" words", after("he said that", "\"Quoted\" words"))
    }

    @Test
    fun given_mid_sentence_and_sentence_end_text_when_checked_then_continues_matches() {
        assertTrue(Pipeline.continuesSentence("help me find some", 17))
        assertFalse(Pipeline.continuesSentence("It is done. ", 12))
    }
}
