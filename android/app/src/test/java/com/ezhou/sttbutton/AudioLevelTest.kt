package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    @Test
    fun given_no_completed_window_when_read_then_recent_dbfs_is_the_floor() {
        val level = AudioLevel()
        assertEquals(-120.0, level.recentDbfs, 0.0)
        level.add(ShortArray(1599), 1599)
        assertEquals(-120.0, level.recentDbfs, 0.0)
    }

    @Test
    fun given_20_ms_of_full_scale_when_read_then_recent_dbfs_moves_before_the_100_ms_stats() {
        val level = AudioLevel()
        level.add(ShortArray(320) { Short.MIN_VALUE }, 320)
        assertEquals(0.0, level.recentDbfs, 0.0)
        assertEquals(-120.0, level.peakDbfs, 0.0)
        assertEquals(0, level.zeroMs)
        assertEquals(20L, level.audioMs)
    }

    @Test
    fun given_a_loud_window_then_silence_when_read_then_recent_dbfs_is_the_latest_window() {
        val level = AudioLevel()
        level.add(ShortArray(1600) { Short.MIN_VALUE }, 1600)
        assertEquals(0.0, level.recentDbfs, 0.0)
        level.add(ShortArray(1600), 1600)
        assertEquals(-120.0, level.recentDbfs, 0.0)
        assertEquals(0.0, level.peakDbfs, 0.05)
        assertEquals(100, level.zeroMs)
    }

    @Test
    fun given_20_ms_of_silence_when_read_then_bands_publish_on_the_floor_and_the_old_levels_stay() {
        val level = AudioLevel()
        assertNull(level.recentBands)
        level.add(ShortArray(319), 319)
        assertNull(level.recentBands)
        assertEquals(-120.0, level.recentDbfs, 0.0)
        assertEquals(0, level.zeroMs)
        assertEquals(-120.0, level.peakDbfs, 0.0)

        level.add(ShortArray(1), 1)
        val bands = level.recentBands
        assertNotNull(bands)
        assertEquals(Spectrum.BANDS, bands!!.size)
        for (band in bands) assertEquals(-120.0, band, 0.0)
        assertEquals(-120.0, level.recentDbfs, 0.0)
        assertEquals(0, level.zeroMs)
        assertEquals(-120.0, level.peakDbfs, 0.0)
        assertEquals(20L, level.audioMs)
    }

    @Test
    fun given_a_published_band_array_when_the_next_window_arrives_then_the_old_array_is_unchanged() {
        val level = AudioLevel()
        val tone = sine(200.0, -20.0, 640)
        level.add(tone.copyOfRange(0, 320), 320)
        val first = level.recentBands
        assertNotNull(first)
        val kept = first!!.copyOf()
        assertEquals(-120.0, level.peakDbfs, 0.0)
        assertEquals(0, level.zeroMs)
        assertEquals(20L, level.audioMs)

        level.add(tone.copyOfRange(320, 640), 320)
        val second = level.recentBands
        assertTrue(second !== first)
        for (i in kept.indices) assertEquals(kept[i], first[i], 0.0)
        assertTrue(WaveLevel.target(second!![0]) > 0.8)
        assertEquals(-20.0, level.recentDbfs, 0.5)
        assertEquals(-120.0, level.peakDbfs, 0.0)
        assertEquals(0, level.zeroMs)
        assertEquals(40L, level.audioMs)
    }

    private fun sine(hz: Double, dbfs: Double, count: Int): ShortArray {
        val amplitude = 32768.0 * 10.0.pow(dbfs / 20.0) * sqrt(2.0)
        return ShortArray(count) { i ->
            (amplitude * sin(2.0 * PI * hz * i / Spectrum.SAMPLE_RATE)).roundToInt().toShort()
        }
    }
}
