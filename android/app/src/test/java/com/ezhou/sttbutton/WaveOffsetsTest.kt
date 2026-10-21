package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class WaveOffsetsTest {
    @Test
    fun given_24_points_when_a_quarter_cycle_is_sampled_then_it_runs_from_zero_to_the_amplitude() {
        val ys = DoubleArray(WAVE_POINT_COUNT)
        assertEquals(24, ys.size)
        fillWaveOffsets(ys, phase = 0.0, cycles = 0.25, amplitude = 4.0)
        assertEquals(0.0, ys[0], 1e-9)
        assertEquals(4.0, ys[ys.lastIndex], 1e-9)
        for (i in 1 until ys.size) assertTrue(ys[i] > ys[i - 1])
    }

    @Test
    fun given_cycles_across_the_width_when_sampled_then_the_last_point_closes_the_cycles() {
        val ys = DoubleArray(24)
        fillWaveOffsets(ys, phase = 0.0, cycles = 1.5, amplitude = 1.0)
        assertEquals(0.0, ys[0], 1e-9)
        assertEquals(0.0, ys[23], 1e-9)
        fillWaveOffsets(ys, phase = 0.0, cycles = 1.0, amplitude = 1.0)
        for (i in ys.indices) assertEquals(-ys[i], ys[ys.lastIndex - i], 1e-9)
    }

    @Test
    fun given_a_phase_or_amplitude_change_when_sampled_then_the_wave_shifts_and_scales() {
        val base = DoubleArray(24)
        val flipped = DoubleArray(24)
        val scaled = DoubleArray(24)
        fillWaveOffsets(base, phase = 0.4, cycles = 2.0, amplitude = 1.0)
        fillWaveOffsets(flipped, phase = 0.4 + PI, cycles = 2.0, amplitude = 1.0)
        fillWaveOffsets(scaled, phase = 0.4, cycles = 2.0, amplitude = 3.5)
        for (i in base.indices) {
            assertEquals(-base[i], flipped[i], 1e-9)
            assertEquals(base[i] * 3.5, scaled[i], 1e-9)
            assertTrue(abs(base[i]) <= 1.0 + 1e-9)
        }
    }

    @Test
    fun given_no_cycles_or_no_amplitude_when_sampled_then_the_line_is_flat() {
        val ys = DoubleArray(24)
        fillWaveOffsets(ys, phase = PI / 2, cycles = 0.0, amplitude = 1.25)
        for (y in ys) assertEquals(1.25, y, 1e-9)
        fillWaveOffsets(ys, phase = 1.2, cycles = 2.5, amplitude = 0.0)
        for (y in ys) assertEquals(0.0, y, 0.0)
    }
}
