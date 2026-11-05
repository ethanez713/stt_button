package com.ezhou.sttbutton

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Loudness of the samples a recorder hands a take. Peak and silence stats use 100 ms windows.
 * No thread of its own: [add] runs on the caller. Digital silence is −∞ dBFS, which JSON cannot
 * store; [DBFS_FLOOR] is the same stand-in the host recorder uses.
 *
 * [recentDbfs] is a 20 ms window: a syllable is shorter than the 100 ms stats. [recentBands] is the
 * spectrum of the latest [Spectrum.N] samples, a new array every [BANDS_HOP] samples, 15 a second.
 */
class AudioLevel {
    private val pending = ShortArray(WINDOW)
    private var filled = 0
    private val recentPending = ShortArray(RECENT)
    private var recentFilled = 0
    private val ring = ShortArray(Spectrum.N)
    private val ordered = ShortArray(Spectrum.N)
    private val bandScratch = DoubleArray(Spectrum.BANDS)
    private var ringAt = 0
    private var sinceBands = 0
    private val spectrum = Spectrum()
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
                feedRecent(buffer, i, take)
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

    private fun feedRecent(buffer: ShortArray, offset: Int, length: Int) {
        var i = 0
        while (i < length) {
            val take = minOf(RECENT - recentFilled, length - i)
            System.arraycopy(buffer, offset + i, recentPending, recentFilled, take)
            appendRing(buffer, offset + i, take)
            recentFilled += take
            i += take
            if (recentFilled == RECENT) {
                recentDbfs = levelOf(recentPending)
                recentFilled = 0
            }
        }
    }

    /** Milliseconds of completed windows whose samples were all zero. */
    val zeroMs: Int get() = synchronized(this) { zeroWindows * WINDOW_MS }

    /** Loudest completed window, or [DBFS_FLOOR] when every window was silent or none has completed. */
    val peakDbfs: Double get() = synchronized(this) { round(peak * 10.0) / 10.0 }

    /** Samples received so far, including a trailing partial window, in milliseconds. */
    val audioMs: Long get() = synchronized(this) { samples * 1000L / RATE }

    /** dBFS of the latest completed 20 ms window, or [DBFS_FLOOR] before one, read without the lock [add] holds. */
    @Volatile var recentDbfs: Double = DBFS_FLOOR
        private set

    /** Seven dBFS bands of the latest [Spectrum.N] samples, a new array every [BANDS_HOP] samples, or null before the first. */
    @Volatile var recentBands: DoubleArray? = null
        private set

    private fun appendRing(buffer: ShortArray, offset: Int, length: Int) {
        var i = 0
        while (i < length) {
            ring[ringAt] = buffer[offset + i]
            ringAt++
            if (ringAt == ring.size) ringAt = 0
            sinceBands++
            if (sinceBands == BANDS_HOP) {
                publishBands()
                sinceBands = 0
            }
            i++
        }
    }

    private fun publishBands() {
        var position = ringAt
        var i = 0
        while (i < ordered.size) {
            ordered[i] = ring[position]
            position++
            if (position == ring.size) position = 0
            i++
        }
        spectrum.bands(ordered, bandScratch)
        recentBands = bandScratch.copyOf()
    }

    private fun score() {
        var sum = 0L
        for (sample in pending) {
            val value = sample.toLong()
            sum += value * value
        }
        if (sum == 0L) zeroWindows++
        val level = dbfs(sum.toDouble(), pending.size)
        if (level > peak) peak = level
    }

    companion object {
        const val BANDS_HOP = 1067
        private const val RATE = 16000
        private const val WINDOW_MS = 100
        private const val WINDOW = RATE * WINDOW_MS / 1000
        private const val RECENT_MS = 20
        private const val RECENT = RATE * RECENT_MS / 1000
        private const val FULL_SCALE = 32768.0
        private const val DBFS_FLOOR = -120.0

        fun levelOf(samples: ShortArray): Double {
            var sum = 0.0
            for (sample in samples) {
                val value = sample.toDouble()
                sum += value * value
            }
            return dbfs(sum, samples.size)
        }

        fun dbfs(sumSquares: Double, count: Int): Double {
            if (sumSquares == 0.0) return DBFS_FLOOR
            val rms = sqrt(sumSquares / count)
            return max(DBFS_FLOOR, 20.0 * log10(rms / FULL_SCALE))
        }
    }
}
