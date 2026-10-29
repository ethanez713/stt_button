package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeTest {
    private fun take() = Take("id", "com.example", 0)

    @Test
    fun given_an_open_take_when_stopped_then_it_is_busy_until_inserted() {
        val take = take()
        assertFalse(take.busy)
        assertTrue(take.requestStop())
        assertTrue(take.busy)
        assertTrue(take.markInserted())
        assertFalse(take.busy)
        assertEquals(TakeState.INSERTED, take.state)
    }

    @Test
    fun given_a_stopping_take_when_resumed_then_it_is_open_and_can_stop_again() {
        val take = take()
        take.requestStop()
        assertTrue(take.resume())
        assertEquals(TakeState.OPEN, take.state)
        assertFalse(take.busy)
        assertFalse(take.resume())
        assertTrue(take.requestStop())
        assertTrue(take.resume())
        assertTrue(take.requestStop())
        take.markInserted()
        assertFalse(take.resume())
    }

    @Test
    fun given_a_stopping_take_when_stopped_again_then_the_second_tap_is_ignored() {
        val take = take()
        take.requestStop()
        assertFalse(take.requestStop())
    }

    @Test
    fun given_an_open_take_when_the_language_changes_then_it_is_discarded_and_cannot_insert() {
        val take = take()
        assertTrue(take.discard(DiscardReason.LANGUAGE))
        assertFalse(take.requestStop())
        assertFalse(take.markInserted())
        assertEquals("language", take.discardReason?.logName)
    }

    @Test
    fun given_a_stopping_take_when_the_package_changes_then_it_is_discarded() {
        val take = take()
        take.requestStop()
        assertTrue(take.discard(DiscardReason.PACKAGE))
        assertEquals(TakeState.DISCARDED, take.state)
    }

    @Test
    fun given_an_inserted_take_when_a_late_discard_arrives_then_it_is_ignored() {
        val take = take()
        take.requestStop()
        take.markInserted()
        assertFalse(take.discard(DiscardReason.LANGUAGE))
        assertEquals(TakeState.INSERTED, take.state)
    }

    @Test
    fun given_an_open_take_when_inserted_without_a_stop_then_it_does_not_apply() {
        assertFalse(take().markInserted())
    }
}
