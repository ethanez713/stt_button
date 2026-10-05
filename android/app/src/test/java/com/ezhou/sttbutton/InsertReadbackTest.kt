package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertReadbackTest {
    private fun view(text: String, readable: Boolean = true) = InsertReadback.View(text, readable)

    @Test
    fun given_the_utterance_in_the_field_with_different_case_when_read_back_then_it_landed() {
        val verdict = InsertReadback.judge(view("Help me find some "), view("Help me find some especially"), "Especially")
        assertEquals(InsertReadback.Verdict.LANDED, verdict)
    }

    @Test
    fun given_an_unchanged_field_that_already_held_the_utterance_when_read_back_then_it_is_unchanged() {
        val before = view("Where we can click into to see the full shopping")
        assertEquals(InsertReadback.Verdict.UNCHANGED, InsertReadback.judge(before, before, "see"))
    }

    @Test
    fun given_the_utterance_a_second_time_in_the_field_when_read_back_then_it_landed() {
        val verdict = InsertReadback.judge(view("so that"), view("so that that"), "that")
        assertEquals(InsertReadback.Verdict.LANDED, verdict)
    }

    @Test
    fun given_a_field_that_changed_elsewhere_and_already_held_the_utterance_when_read_back_then_it_diverged() {
        val verdict = InsertReadback.judge(view("to see the list"), view("to see the list?"), "see")
        assertEquals(InsertReadback.Verdict.DIVERGED, verdict)
    }

    @Test
    fun given_a_readable_field_that_did_not_change_when_read_back_then_it_is_unchanged() {
        val before = view("Help me find some ")
        assertEquals(InsertReadback.Verdict.UNCHANGED, InsertReadback.judge(before, before, "laundry"))
    }

    @Test
    fun given_a_field_that_changed_without_the_utterance_when_read_back_then_it_diverged() {
        val verdict = InsertReadback.judge(view("Hello"), view("Hello there"), "laundry")
        assertEquals(InsertReadback.Verdict.DIVERGED, verdict)
    }

    @Test
    fun given_no_text_from_the_editor_when_read_back_then_it_is_blind() {
        val verdict = InsertReadback.judge(view("", readable = false), view("", readable = false), "laundry")
        assertEquals(InsertReadback.Verdict.BLIND, verdict)
    }

    @Test
    fun given_the_node_unchanged_and_the_caret_text_holding_the_utterance_when_combined_then_it_landed() {
        val node = InsertReadback.judge(view("Hello"), view("Hello"), "there")
        val caret = InsertReadback.judge(view("Hello"), view("Hello there"), "there")
        assertEquals(InsertReadback.Verdict.LANDED, InsertReadback.combine(node, caret))
    }

    @Test
    fun given_both_reads_unchanged_when_combined_then_the_write_did_not_land() {
        val one = InsertReadback.judge(view("Hello"), view("Hello"), "there")
        assertEquals(InsertReadback.Verdict.UNCHANGED, InsertReadback.combine(one, one))
    }

    @Test
    fun given_the_node_unchanged_and_the_caret_window_slid_when_combined_then_the_field_is_unchanged() {
        val node = InsertReadback.judge(view("Hello world"), view("Hello world"), "there")
        val caret = InsertReadback.judge(view("Hello world"), view("Hello"), "there")
        assertEquals(InsertReadback.Verdict.UNCHANGED, InsertReadback.combine(node, caret))
    }

    @Test
    fun given_an_unchanged_field_when_commit_was_tried_then_paste_is_next_and_then_set_text() {
        assertEquals("paste", InsertReadback.nextPath(InsertReadback.Verdict.UNCHANGED, false, true, false, false))
        assertEquals("set_text", InsertReadback.nextPath(InsertReadback.Verdict.UNCHANGED, false, true, true, false))
        assertNull(InsertReadback.nextPath(InsertReadback.Verdict.UNCHANGED, false, true, true, true))
    }

    @Test
    fun given_a_blind_read_after_commit_when_choosing_a_fallback_then_nothing_else_is_written() {
        assertNull(InsertReadback.nextPath(InsertReadback.Verdict.BLIND, commitMissing = false, allowSetText = true, false, false))
    }

    @Test
    fun given_no_connection_and_a_blind_field_when_choosing_a_fallback_then_only_paste_is_tried() {
        assertEquals("paste", InsertReadback.nextPath(InsertReadback.Verdict.BLIND, commitMissing = true, allowSetText = false, false, false))
        assertNull(InsertReadback.nextPath(InsertReadback.Verdict.BLIND, commitMissing = true, allowSetText = false, true, false))
    }

    @Test
    fun given_a_field_that_diverged_when_choosing_a_fallback_then_it_is_left_alone() {
        assertNull(InsertReadback.nextPath(InsertReadback.Verdict.DIVERGED, false, true, false, false))
    }

    @Test
    fun given_an_empty_node_and_caret_text_that_has_words_when_set_text_is_considered_then_it_is_refused() {
        assertFalse(InsertReadback.allowSetText(view(""), view("already here")))
    }

    @Test
    fun given_a_node_that_holds_the_field_text_when_set_text_is_considered_then_it_is_allowed() {
        assertTrue(InsertReadback.allowSetText(view("Hello"), view("")))
    }

    @Test
    fun given_both_reads_empty_when_set_text_is_considered_then_it_is_allowed() {
        assertTrue(InsertReadback.allowSetText(view(""), view("")))
    }

    @Test
    fun given_the_node_gained_the_utterance_twice_when_counted_then_the_count_is_2() {
        val count = InsertReadback.landedCount(view("go"), view("go go go"), view("go"), view("go go"), "go")
        assertEquals(2, count)
    }

    @Test
    fun given_no_text_from_either_view_when_counted_then_there_is_no_count() {
        val blind = view("", readable = false)
        assertNull(InsertReadback.landedCount(blind, blind, blind, blind, "go"))
    }

    @Test
    fun given_a_blind_node_and_a_caret_that_gained_the_utterance_when_counted_then_the_count_is_the_carets() {
        val blind = view("", readable = false)
        assertEquals(1, InsertReadback.landedCount(blind, blind, view(""), view("go"), "go"))
    }

    @Test
    fun given_a_long_utterance_found_by_its_tail_when_read_back_then_it_landed() {
        val needle = "H".repeat(101) + "T".repeat(400)
        assertEquals(501, needle.length)
        val before = view("")
        val after = view("T".repeat(400))
        assertEquals(InsertReadback.Verdict.LANDED, InsertReadback.judge(before, after, needle))
        assertEquals(1, InsertReadback.landedCount(before, after, view("", readable = false), view("", readable = false), needle))
    }

    @Test
    fun given_a_long_utterance_found_by_its_head_when_read_back_then_it_landed() {
        val needle = "H".repeat(400) + "M".repeat(101)
        assertEquals(501, needle.length)
        assertEquals(InsertReadback.Verdict.LANDED, InsertReadback.judge(view(""), view("H".repeat(400)), needle))
    }

    @Test
    fun given_an_utterance_of_500_characters_found_only_by_its_tail_when_read_back_then_it_did_not_land() {
        val needle = "H".repeat(100) + "T".repeat(400)
        assertEquals(500, needle.length)
        assertEquals(InsertReadback.Verdict.DIVERGED, InsertReadback.judge(view(""), view("T".repeat(400)), needle))
    }

    @Test
    fun given_a_long_utterance_whose_tail_the_field_already_held_when_read_back_then_paste_is_still_next() {
        val needle = "H".repeat(101) + "T".repeat(400)
        val field = view("T".repeat(400))
        val verdict = InsertReadback.judge(field, field, needle)
        assertEquals(InsertReadback.Verdict.UNCHANGED, verdict)
        assertEquals("paste", InsertReadback.nextPath(verdict, commitMissing = false, allowSetText = true, triedPaste = false, triedSetText = false))
    }
}
