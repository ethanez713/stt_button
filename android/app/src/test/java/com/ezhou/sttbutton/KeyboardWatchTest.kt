package com.ezhou.sttbutton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardWatchTest {
    @Test
    fun given_the_keyboard_gone_for_the_grace_period_when_a_take_is_open_then_a_stop_is_due() {
        val watch = KeyboardWatch(700)
        assertFalse(watch.due(imePresent = false, takeOpen = true, nowMs = 1000))
        assertFalse(watch.due(imePresent = false, takeOpen = true, nowMs = 1699))
        assertTrue(watch.due(imePresent = false, takeOpen = true, nowMs = 1700))
    }

    @Test
    fun given_the_keyboard_back_within_the_grace_period_when_it_goes_again_then_the_clock_restarts() {
        val watch = KeyboardWatch(700)
        watch.due(imePresent = false, takeOpen = true, nowMs = 1000)
        assertFalse(watch.due(imePresent = true, takeOpen = true, nowMs = 1500))
        assertFalse(watch.due(imePresent = false, takeOpen = true, nowMs = 1600))
        assertFalse(watch.due(imePresent = false, takeOpen = true, nowMs = 2200))
        assertTrue(watch.due(imePresent = false, takeOpen = true, nowMs = 2300))
    }

    @Test
    fun given_no_open_take_when_the_keyboard_is_gone_then_nothing_is_due() {
        val watch = KeyboardWatch(700)
        assertFalse(watch.due(imePresent = false, takeOpen = false, nowMs = 1000))
        assertFalse(watch.due(imePresent = false, takeOpen = false, nowMs = 5000))
        assertFalse(watch.due(imePresent = false, takeOpen = true, nowMs = 5100))
    }
}
