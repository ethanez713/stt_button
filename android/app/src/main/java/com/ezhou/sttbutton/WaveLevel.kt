package com.ezhou.sttbutton

/**
 * Displayed wave height. Silence stays a faint line, a louder window appears at once, and a
 * quieter one eases down.
 */
class WaveLevel {
    private var level = FLOOR

    fun update(dbfs: Double?): Double {
        val goal = if (dbfs == null) 0.0 else target(dbfs)
        val next = if (goal >= level) goal else goal + (level - goal) * FALL
        level = if (next < FLOOR) FLOOR else next
        return level
    }

    fun reset() {
        level = FLOOR
    }

    companion object {
        private const val QUIET_DBFS = -55.0
        private const val LOUD_DBFS = -20.0
        private const val FALL = 0.85
        private const val FLOOR = 0.06

        fun target(dbfs: Double): Double =
            ((dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0.0, 1.0)
    }
}
