package com.ezhou.sttbutton

import kotlin.math.abs
import kotlin.math.max

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** What a [MicPlacement.read] asks of the caller. */
data class Placement(
    val show: Bounds? = null,
    val persist: Bounds? = null,
    val reread: Boolean = false,
    val settle: Settle? = null,
)

data class Settle(val remembered: Boolean, val reads: Int, val settledMs: Long, val movedPx: Int)

/**
 * Keeps the button still while the keyboard slides in. On appearance the button takes the last
 * settled position; it moves to the microphone's current bounds only once they have stopped
 * changing, or after [capMs] if they never do.
 */
class MicPlacement(private val stableMs: Long = 100, private val capMs: Long = 1500) {
    private var shown: Bounds? = null
    private var last: Bounds? = null
    private var lastChangeAt = 0L
    private var episodeStart: Long? = null
    private var reads = 0
    private var appearedOnRemembered = false
    private var shownAtEpisodeStart: Bounds? = null

    fun reset() {
        shown = null
        last = null
        episodeStart = null
    }

    fun read(now: Long, bounds: Bounds, remembered: Bounds?): Placement {
        if (shown == null) return appear(now, bounds, remembered)
        reads++
        if (bounds != last) {
            last = bounds
            lastChangeAt = now
            if (episodeStart == null) beginEpisode(now)
        }
        val start = episodeStart ?: return Placement()
        if (now - lastChangeAt < stableMs && now - start < capMs) return Placement(reread = true)
        return settle(now, start, bounds)
    }

    private fun appear(now: Long, bounds: Bounds, remembered: Bounds?): Placement {
        shown = remembered ?: bounds
        last = bounds
        lastChangeAt = now
        appearedOnRemembered = remembered != null
        if (remembered != null && bounds == remembered) {
            episodeStart = null
            return Placement(show = shown)
        }
        beginEpisode(now)
        return Placement(show = shown, reread = true)
    }

    private fun beginEpisode(now: Long) {
        episodeStart = now
        reads = 1
        shownAtEpisodeStart = shown
    }

    private fun settle(now: Long, start: Long, bounds: Bounds): Placement {
        val from = shownAtEpisodeStart ?: bounds
        episodeStart = null
        val moved = bounds != shown
        shown = bounds
        val movedPx = max(abs(bounds.left - from.left), abs(bounds.top - from.top))
        return Placement(
            show = bounds.takeIf { moved },
            persist = bounds,
            settle = Settle(appearedOnRemembered, reads, now - start, movedPx),
        )
    }
}
