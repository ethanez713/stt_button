package com.ezhou.sttbutton

import kotlin.math.pow

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
