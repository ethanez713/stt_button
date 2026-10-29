package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeRegionTest {
    private fun region() = TakeRegion(start = 10, cleanEnd = 20, end = 30)

    private fun offsets(r: TakeRegion) = Triple(r.start, r.cleanEnd, r.end)

    @Test
    fun given_an_insert_before_the_region_when_applied_then_every_offset_moves() {
        val r = region().also { it.apply(Edit(0, 0, 3)) }
        assertEquals(Triple(13, 23, 33), offsets(r))
    }

    @Test
    fun given_an_insert_inside_the_cleaned_stretch_when_applied_then_the_clean_end_and_end_move() {
        val r = region().also { it.apply(Edit(15, 0, 3)) }
        assertEquals(Triple(10, 23, 33), offsets(r))
    }

    @Test
    fun given_an_insert_inside_the_tail_when_applied_then_only_the_end_moves() {
        val r = region().also { it.apply(Edit(25, 0, 3)) }
        assertEquals(Triple(10, 20, 33), offsets(r))
    }

    @Test
    fun given_an_insert_exactly_at_the_start_when_applied_then_it_stays_outside() {
        val r = region().also { it.apply(Edit(10, 0, 3)) }
        assertEquals(Triple(13, 23, 33), offsets(r))
    }

    @Test
    fun given_an_insert_exactly_at_the_end_when_applied_then_the_region_extends_over_it() {
        val r = region().also { it.apply(Edit(30, 0, 3)) }
        assertEquals(Triple(10, 20, 33), offsets(r))
    }

    @Test
    fun given_an_insert_at_the_clean_end_when_applied_then_it_joins_the_tail() {
        val r = region().also { it.apply(Edit(20, 0, 3)) }
        assertEquals(Triple(10, 20, 33), offsets(r))
    }

    @Test
    fun given_a_delete_spanning_the_clean_end_when_applied_then_the_clean_end_lands_at_the_delete() {
        val r = region().also { it.apply(Edit(18, 6, 0)) }
        assertEquals(Triple(10, 18, 24), offsets(r))
    }

    @Test
    fun given_a_delete_swallowing_the_region_when_applied_then_it_collapses() {
        val r = region().also { it.apply(Edit(5, 40, 0)) }
        assertEquals(Triple(5, 5, 5), offsets(r))
    }

    @Test
    fun given_edits_around_a_flight_when_applied_then_it_is_dirty_only_when_one_touches_it() {
        fun after(edit: Edit) = region().also { it.flightOver(20, 28); it.apply(edit) }.flight!!
        assertFalse(after(Edit(0, 0, 2)).dirty)
        assertTrue(after(Edit(20, 0, 2)).dirty)
        assertFalse(after(Edit(28, 0, 2)).dirty)
        assertFalse(after(Edit(15, 5, 0)).dirty)
        assertFalse(after(Edit(28, 2, 0)).dirty)
        assertTrue(after(Edit(24, 0, 2)).dirty)
        assertTrue(after(Edit(27, 3, 0)).dirty)
        assertTrue(after(Edit(18, 4, 1)).dirty)
    }

    @Test
    fun given_a_flight_when_text_is_inserted_before_it_then_it_moves_with_the_text() {
        val r = region().also { it.flightOver(20, 28); it.apply(Edit(2, 0, 5)) }
        assertEquals(25, r.flight!!.from)
        assertEquals(33, r.flight!!.to)
    }

    @Test
    fun given_a_flight_replaced_by_cleaned_text_when_recorded_then_the_clean_end_follows_it() {
        val r = region().also { it.flightOver(20, 28); it.flightReplaced(5) }
        assertEquals(Triple(10, 25, 27), offsets(r))
        assertNull(r.flight)
    }

    @Test
    fun given_a_closed_region_when_text_is_inserted_at_the_end_then_it_stays_outside_until_reopened() {
        val r = region()
        r.close()
        r.apply(Edit(30, 0, 3))
        assertEquals(Triple(10, 20, 30), offsets(r))
        r.reopen()
        r.apply(Edit(30, 0, 3))
        assertEquals(Triple(10, 20, 33), offsets(r))
    }

    @Test
    fun given_a_flight_when_abandoned_and_then_edited_then_both_marks_stay() {
        val r = region().also { it.flightOver(20, 28); it.abandonFlight() }
        assertTrue(r.flight!!.abandoned)
        r.apply(Edit(24, 0, 2))
        assertTrue(r.flight!!.abandoned)
        assertTrue(r.flight!!.dirty)
    }

    @Test
    fun given_text_appended_by_the_take_when_recorded_then_the_end_grows() {
        val r = region().also { it.appended(4) }
        assertEquals(Triple(10, 20, 34), offsets(r))
    }
}
