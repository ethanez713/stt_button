package com.ezhou.sttbutton

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Loudness of the samples a recorder hands a take, in 100 ms windows. No thread of its own:
 * [add] runs on the caller. Digital silence is −∞ dBFS, which JSON cannot store; [DBFS_FLOOR]
 * is the same stand-in the host recorder uses.
 */
class AudioLevel {
    private val pending = ShortArray(WINDOW)
    private var filled = 0
    private var samples = 0L
    private var zeroWindows = 0
    private var peak = DBFS_FLOOR

    fun add(buffer: ShortArray, length: Int) {
        synchronized(this) {
            val n = length.coerceIn(0, buffer.size)
            var i = 0
            while (i < n) {
                val take = minOf(WINDOW - filled, n - i)
                System.arraycopy(buffer, i, pending, filled, take)
                filled += take
                i += take
                samples += take
                if (filled == WINDOW) {
                    score()
                    filled = 0
                }
            }
        }
    }

    /** Milliseconds of completed windows whose samples were all zero. */
    val zeroMs: Int get() = synchronized(this) { zeroWindows * WINDOW_MS }

    /** Loudest completed window, or [DBFS_FLOOR] when every window was silent or none has completed. */
    val peakDbfs: Double get() = synchronized(this) { round(peak * 10.0) / 10.0 }

    /** Samples received so far, including a trailing partial window, in milliseconds. */
    val audioMs: Long get() = synchronized(this) { samples * 1000L / RATE }

    /** dBFS of the latest completed window, or [DBFS_FLOOR] before one, read without the lock [add] holds. */
    @Volatile var recentDbfs: Double = DBFS_FLOOR
        private set

    private fun score() {
        var sum = 0L
        for (sample in pending) {
            val value = sample.toLong()
            sum += value * value
        }
        val level = if (sum == 0L) {
            zeroWindows++
            DBFS_FLOOR
        } else {
            val rms = sqrt(sum.toDouble() / WINDOW)
            max(DBFS_FLOOR, 20.0 * log10(rms / FULL_SCALE))
        }
        if (level > peak) peak = level
        recentDbfs = level
    }

    private companion object {
        const val RATE = 16000
        const val WINDOW_MS = 100
        const val WINDOW = RATE * WINDOW_MS / 1000
        const val FULL_SCALE = 32768.0
        const val DBFS_FLOOR = -120.0
    }
}
