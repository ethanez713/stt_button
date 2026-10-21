package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveLevelTest {
    @Test
    fun given_dbfs_across_the_range_when_mapped_then_quiet_is_zero_and_loud_is_one() {
        assertEquals(0.0, WaveLevel.target(-55.0), 0.0)
        assertEquals(0.5, WaveLevel.target(-37.5), 0.0)
        assertEquals(1.0, WaveLevel.target(-20.0), 0.0)
        assertEquals(0.0, WaveLevel.target(-56.0), 0.0)
        assertEquals(0.0, WaveLevel.target(-120.0), 0.0)
        assertEquals(1.0, WaveLevel.target(-19.0), 0.0)
        assertEquals(1.0, WaveLevel.target(0.0), 0.0)
    }

    @Test
    fun given_a_louder_window_when_updated_then_the_level_rises_at_once() {
        val wave = WaveLevel()
        assertEquals(0.5, wave.update(-37.5), 0.0)
        assertEquals(1.0, wave.update(-20.0), 0.0)
    }

    @Test
    fun given_a_quieter_window_when_updated_then_the_level_falls_by_0_85_toward_it() {
        val wave = WaveLevel()
        wave.update(-20.0)
        assertEquals(0.925, wave.update(-37.5), 1e-9)
        assertEquals(0.86125, wave.update(-37.5), 1e-9)
        assertEquals(0.8070625, wave.update(-37.5), 1e-9)
        assertEquals(0.761003125, wave.update(-37.5), 1e-9)
    }

    @Test
    fun given_silence_when_updated_then_the_level_falls_to_the_floor_and_stays() {
        assertEquals(0.06, WaveLevel().update(null), 0.0)

        val wave = WaveLevel()
        wave.update(-20.0)
        var shown = wave.update(null)
        assertEquals(0.85, shown, 1e-9)
        repeat(16) {
            shown = wave.update(-120.0)
            assertTrue(shown > 0.06)
        }
        assertEquals(0.06, wave.update(-55.0), 0.0)
        assertEquals(0.06, wave.update(null), 0.0)
    }

    @Test
    fun given_a_raised_level_when_reset_then_it_is_back_at_the_floor() {
        val wave = WaveLevel()
        wave.update(-20.0)
        wave.reset()
        assertEquals(0.06, wave.update(-55.0), 0.0)
        assertEquals(1.0, wave.update(-20.0), 0.0)
    }
}
