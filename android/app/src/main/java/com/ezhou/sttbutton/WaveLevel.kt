package com.ezhou.sttbutton

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Displayed wave height. The room on this mic sits near −58 dBFS, under the gate, so silence
 * is a flat line. Above that the height is the window's amplitude relative to a clear
 * syllable, and it follows the latest window at once, up or down.
 */
class WaveLevel {
    private var level = 0.0

    fun update(dbfs: Double?): Double {
        level = if (dbfs == null) 0.0 else target(dbfs)
        return level
    }

    fun reset() {
        level = 0.0
    }

    companion object {
        private const val QUIET_DBFS = -50.0
        private const val LOUD_DBFS = -32.0

        fun target(dbfs: Double): Double {
            if (dbfs <= QUIET_DBFS) return 0.0
            return 10.0.pow((dbfs - LOUD_DBFS) / 20.0).coerceAtMost(1.0)
        }
    }
}

/**
 * Displayed bar height. A louder target is taken at once. A quieter one decays toward it, stopping
 * at the target, so once a take's bars have fallen they follow the cleaning ripple.
 */
class BarSmoother {
    private val shown = DoubleArray(BARS)

    fun update(index: Int, target: Double): Double {
        val current = shown[index]
        val next = if (target >= current) target else max(target, current * FALL)
        shown[index] = next.coerceAtLeast(FLOOR)
        return shown[index]
    }

    fun level(index: Int): Double = shown[index]

    fun reset() {
        shown.fill(0.0)
    }

    private companion object {
        const val BARS = 7
        private const val FALL = 0.8
        private const val FLOOR = 0.06
    }
}

/** Cleaning bar height at [seconds], travelling toward higher [bar] indexes. */
fun cleaningRipple(seconds: Double, bar: Int): Double {
    val swing = (1.0 + sin(2.0 * PI * 0.8 * seconds - 0.9 * bar)) / 2.0
    return 0.18 + 0.14 * swing
}
