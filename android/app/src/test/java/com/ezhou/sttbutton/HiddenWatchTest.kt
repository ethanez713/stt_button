package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenWatchTest {
    @Test
    fun given_the_button_hidden_for_the_grace_period_when_a_take_records_then_the_stop_is_due() {
        val watch = HiddenWatch(700)
        assertEquals(700L, watch.stopInMs(shown = false, recording = true, nowMs = 1000))
        assertEquals(1L, watch.stopInMs(shown = false, recording = true, nowMs = 1699))
        assertEquals(0L, watch.stopInMs(shown = false, recording = true, nowMs = 1700))
        assertEquals(0L, watch.stopInMs(shown = false, recording = true, nowMs = 9000))
    }

    @Test
    fun given_the_button_back_within_the_grace_period_when_it_hides_again_then_the_clock_restarts() {
        val watch = HiddenWatch(700)
        watch.stopInMs(shown = false, recording = true, nowMs = 1000)
        assertNull(watch.stopInMs(shown = true, recording = true, nowMs = 1500))
        assertEquals(700L, watch.stopInMs(shown = false, recording = true, nowMs = 1600))
        assertEquals(100L, watch.stopInMs(shown = false, recording = true, nowMs = 2200))
        assertEquals(0L, watch.stopInMs(shown = false, recording = true, nowMs = 2300))
    }

    @Test
    fun given_no_recording_take_when_the_button_is_hidden_then_nothing_is_pending() {
        val watch = HiddenWatch(700)
        assertNull(watch.stopInMs(shown = false, recording = false, nowMs = 1000))
        assertNull(watch.stopInMs(shown = false, recording = false, nowMs = 5000))
        assertEquals(700L, watch.stopInMs(shown = false, recording = true, nowMs = 5100))
    }
}
