package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretWatchTest {
    @Test
    fun given_a_surrounding_text_window_when_the_selection_is_inside_it_then_the_caret_is_absolute() {
        assertEquals(Caret(12, 12), CaretWatch.absolute(10, 2, 2))
    }

    @Test
    fun given_an_unknown_selection_when_made_absolute_then_it_stays_unknown() {
        assertEquals(Caret.UNKNOWN, CaretWatch.absolute(0, -1, -1))
    }

    @Test
    fun given_a_caret_after_the_insert_when_text_is_inserted_then_it_shifts_by_the_text() {
        assertEquals(17, CaretWatch.restored(12, 10, 5))
    }

    @Test
    fun given_a_caret_at_or_before_the_insert_when_text_is_inserted_then_it_stays() {
        assertEquals(8, CaretWatch.restored(8, 10, 5))
        assertEquals(10, CaretWatch.restored(10, 10, 5))
    }

    @Test
    fun given_the_caret_still_where_the_take_writes_when_checked_then_the_user_has_not_moved_it() {
        val accepted = Caret(10, 10)
        assertFalse(CaretWatch.userMoved(accepted, accepted, emptySet(), inGrace = false))
    }

    @Test
    fun given_a_different_caret_when_checked_then_the_user_moved_it() {
        assertTrue(CaretWatch.userMoved(Caret(10, 10), Caret(14, 14), emptySet(), inGrace = false))
    }

    @Test
    fun given_our_own_caret_during_the_grace_when_checked_then_it_is_not_the_user() {
        val ignored = setOf(10, 15)
        assertFalse(CaretWatch.userMoved(Caret(15, 15), Caret(10, 10), ignored, inGrace = true))
    }

    @Test
    fun given_a_third_caret_during_the_grace_when_checked_then_the_user_moved_it() {
        val ignored = setOf(10, 15)
        assertTrue(CaretWatch.userMoved(Caret(15, 15), Caret(22, 22), ignored, inGrace = true))
    }

    @Test
    fun given_a_selection_range_when_checked_then_the_user_took_the_field() {
        assertTrue(CaretWatch.userMoved(Caret(10, 10), Caret(3, 7), emptySet(), inGrace = false))
    }

    @Test
    fun given_the_same_selection_the_take_started_with_when_checked_then_it_is_not_a_move() {
        val range = Caret(5, 10)
        assertFalse(CaretWatch.userMoved(range, range, emptySet(), inGrace = false))
    }

    @Test
    fun given_two_editors_in_one_window_when_their_view_ids_differ_then_they_are_different_boxes() {
        val a = EditorId(1, "composer", "EditText")
        val b = EditorId(1, "title", "EditText")
        assertTrue(CaretWatch.differentBox(a, b))
        assertFalse(CaretWatch.sameEditor(a, b))
    }

    @Test
    fun given_a_refreshed_node_of_the_same_editor_when_compared_then_it_is_the_same_box() {
        val a = EditorId(3, null, "android.widget.EditText")
        val b = EditorId(3, null, "android.widget.EditText")
        assertFalse(CaretWatch.differentBox(a, b))
        assertTrue(CaretWatch.sameEditor(a, b))
    }

    @Test
    fun given_focus_in_another_window_when_compared_then_it_is_a_different_box() {
        val a = EditorId(1, "composer", "EditText")
        val b = EditorId(2, "composer", "EditText")
        assertTrue(CaretWatch.differentBox(a, b))
    }
}
