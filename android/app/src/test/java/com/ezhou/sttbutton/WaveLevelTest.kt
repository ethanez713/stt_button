package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class WaveLevelTest {
    @Test
    fun given_dbfs_across_the_range_when_mapped_then_quiet_is_flat_and_a_syllable_is_full() {
        assertEquals(0.0, WaveLevel.target(-50.0), 0.0)
        assertEquals(0.0, WaveLevel.target(-58.0), 0.0)
        assertEquals(0.0, WaveLevel.target(-120.0), 0.0)
        assertEquals(1.0, WaveLevel.target(-32.0), 1e-12)
        assertEquals(1.0, WaveLevel.target(-20.0), 0.0)
        assertEquals(1.0, WaveLevel.target(0.0), 0.0)
        // Ten decibels under a full syllable is a tenth of the amplitude, not a tenth of the dB span.
        assertEquals(10.0.pow(-0.5), WaveLevel.target(-42.0), 1e-12)
        assertEquals(WaveLevel.target(-42.0) * 10.0.pow(0.2), WaveLevel.target(-38.0), 1e-12)
    }

    @Test
    fun given_a_louder_window_when_updated_then_the_level_rises_at_once() {
        val wave = WaveLevel()
        assertEquals(10.0.pow(-0.5), wave.update(-42.0), 1e-12)
        assertEquals(1.0, wave.update(-32.0), 1e-12)
    }

    @Test
    fun given_a_quieter_window_when_updated_then_the_level_falls_at_once() {
        val wave = WaveLevel()
        wave.update(-32.0)
        assertEquals(10.0.pow(-0.5), wave.update(-42.0), 1e-12)
        assertEquals(0.0, wave.update(-50.0), 0.0)
        assertEquals(0.0, wave.update(-120.0), 0.0)
    }

    @Test
    fun given_silence_when_updated_then_the_level_is_flat() {
        assertEquals(0.0, WaveLevel().update(null), 0.0)

        val wave = WaveLevel()
        wave.update(-32.0)
        assertEquals(0.0, wave.update(null), 0.0)
        assertEquals(0.0, wave.update(-58.0), 0.0)
    }

    @Test
    fun given_a_raised_level_when_reset_then_it_is_back_at_flat() {
        val wave = WaveLevel()
        wave.update(-32.0)
        wave.reset()
        assertEquals(0.0, wave.update(-50.0), 0.0)
        assertEquals(1.0, wave.update(-32.0), 1e-12)
    }
}
