package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLevelTest {
    @Test
    fun given_an_all_zero_buffer_when_measured_then_the_window_is_silence() {
        val level = AudioLevel()
        level.add(ShortArray(1600), 1600)
        assertEquals(100, level.zeroMs)
        assertEquals(-120.0, level.peakDbfs, 0.0)
        assertEquals(100L, level.audioMs)
    }

    @Test
    fun given_a_short_all_zero_buffer_when_measured_then_it_is_not_yet_a_window() {
        val level = AudioLevel()
        level.add(ShortArray(1599), 1599)
        assertEquals(0, level.zeroMs)
        assertEquals(-120.0, level.peakDbfs, 0.0)
        assertEquals(99L, level.audioMs)
    }

    @Test
    fun given_a_loud_window_and_then_silence_when_measured_then_the_peak_is_the_loud_window() {
        val level = AudioLevel()
        level.add(ShortArray(1600) { Short.MIN_VALUE }, 1600)
        level.add(ShortArray(1600), 1600)
        assertEquals(0.0, level.peakDbfs, 0.05)
        assertEquals(100, level.zeroMs)
    }
}
