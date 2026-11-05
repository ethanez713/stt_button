package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumTest {
    @Test
    fun given_a_low_quiet_sine_when_measured_then_the_bottom_band_opens_and_the_top_stays_shut() {
        val bands = measure(200.0, -20.0)
        val bottom = WaveLevel.target(bands[0])
        val top = WaveLevel.target(bands[Spectrum.BANDS - 1])
        assertTrue("band 0 target $bottom from ${bands[0]} dBFS", bottom > 0.8)
        assertTrue("band 6 target $top from ${bands[Spectrum.BANDS - 1]} dBFS", top < 0.2)
    }

    @Test
    fun given_a_high_sine_when_measured_then_the_top_band_is_highest_and_reads_its_rms_plus_tilt() {
        val samples = sine(2500.0, -20.0)
        val bands = DoubleArray(Spectrum.BANDS)
        Spectrum().bands(samples, bands)
        var loudest = 0
        for (i in 1 until bands.size) if (bands[i] > bands[loudest]) loudest = i
        assertEquals(Spectrum.BANDS - 1, loudest)
        assertEquals(rmsDbfs(samples) + 9.0, bands[Spectrum.BANDS - 1], 0.05)
    }

    @Test
    fun given_a_tone_in_each_band_when_measured_then_that_band_is_the_loudest() {
        val tones = doubleArrayOf(155.0, 280.0, 440.0, 700.0, 1150.0, 1900.0, 3000.0)
        for (band in tones.indices) {
            val bands = measure(tones[band], -20.0)
            var loudest = 0
            for (i in 1 until bands.size) if (bands[i] > bands[loudest]) loudest = i
            assertEquals("tone ${tones[band]} Hz", band, loudest)
        }
    }

    @Test
    fun given_silence_when_measured_then_every_band_is_on_the_floor_and_maps_to_zero() {
        val bands = DoubleArray(Spectrum.BANDS) { 5.0 }
        Spectrum().bands(ShortArray(Spectrum.N), bands)
        for (band in bands) {
            assertEquals(Spectrum.DBFS_FLOOR, band, 0.0)
            assertEquals(0.0, WaveLevel.target(band), 0.0)
        }
    }

    private fun measure(hz: Double, dbfs: Double): DoubleArray {
        val bands = DoubleArray(Spectrum.BANDS)
        Spectrum().bands(sine(hz, dbfs), bands)
        return bands
    }

    private fun sine(hz: Double, dbfs: Double): ShortArray {
        val amplitude = 32768.0 * 10.0.pow(dbfs / 20.0) * sqrt(2.0)
        return ShortArray(Spectrum.N) { i ->
            (amplitude * sin(2.0 * PI * hz * i / Spectrum.SAMPLE_RATE)).roundToInt().toShort()
        }
    }

    private fun rmsDbfs(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return 20.0 * log10(sqrt(sum / samples.size) / 32768.0)
    }
}
