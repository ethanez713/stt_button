package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun given_a_bar_when_the_target_moves_then_it_rises_at_once_falls_by_over_a_third_and_rests_on_the_floor() {
        val bars = BarSmoother()
        assertEquals(0.2, bars.update(0, 0.2), 0.0)
        assertEquals(1.0, bars.update(0, 1.0), 0.0)
        var expected = 1.0
        while (expected * 0.64 >= 0.06) {
            expected *= 0.64
            assertEquals(expected, bars.update(0, 0.0), 0.0)
        }
        assertEquals(0.06, bars.update(0, 0.0), 0.0)
        assertEquals(0.06, bars.update(0, 0.0), 0.0)
        assertEquals(0.5, bars.update(0, 0.5), 0.0)
        bars.update(1, 1.0)
        assertEquals(0.5, bars.level(0), 0.0)
        assertEquals(0.64, bars.update(1, 0.0), 0.0)
    }

    @Test
    fun given_the_cleaning_ripple_when_time_passes_then_each_bar_peaks_after_the_one_before_it() {
        var previous = 0.0
        var after = 0.0
        for (bar in 0 until 7) {
            val peak = peakAfter(bar, after)
            assertTrue("bar $bar peaks at $peak after $previous", peak > previous)
            assertEquals(0.32, cleaningRipple(peak, bar), 1e-4)
            previous = peak
            after = peak
        }
    }

    private fun peakAfter(bar: Int, after: Double): Double {
        var bestT = after
        var best = Double.NEGATIVE_INFINITY
        var t = after
        val end = after + 1.0 / 0.8
        while (t <= end) {
            val level = cleaningRipple(t, bar)
            if (level > best) {
                best = level
                bestT = t
            }
            t += 0.0002
        }
        return bestT
    }
}
