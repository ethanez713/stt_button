package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPauseTest {
    private class Port(private val grant: Boolean = true) : FocusPort {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean {
            requests++
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    @Test
    fun given_focus_held_when_paused_again_then_it_is_requested_once() {
        val port = Port()
        val media = MediaPause(port)
        assertTrue(media.pause())
        assertTrue(media.pause())
        assertEquals(1, port.requests)
    }

    @Test
    fun given_focus_held_when_resumed_then_it_is_abandoned_once() {
        val port = Port()
        val media = MediaPause(port)
        media.pause()
        media.resume()
        media.resume()
        assertEquals(1, port.abandons)
    }

    @Test
    fun given_no_hold_when_resumed_then_nothing_is_abandoned() {
        val port = Port()
        MediaPause(port).resume()
        assertEquals(0, port.abandons)
    }

    @Test
    fun given_a_refused_request_when_paused_then_it_reports_failure_and_resume_does_nothing() {
        val port = Port(grant = false)
        val media = MediaPause(port)
        assertFalse(media.pause())
        media.resume()
        assertEquals(0, port.abandons)
    }

    @Test
    fun given_a_resumed_hold_when_paused_again_then_focus_is_requested_again() {
        val port = Port()
        val media = MediaPause(port)
        media.pause()
        media.resume()
        media.pause()
        assertEquals(2, port.requests)
    }
}
