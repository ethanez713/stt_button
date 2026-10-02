package com.ezhou.sttbutton

/**
 * Decides when a take whose button left the screen should stop. Gboard drops its window for a
 * moment when it switches panels, so only an absence that lasts [graceMs] counts.
 */
class HiddenWatch(private val graceMs: Long) {
    private var hiddenSince: Long? = null

    /** Milliseconds until the take should stop, 0 once it is due, or null while nothing is pending. */
    fun stopInMs(shown: Boolean, recording: Boolean, nowMs: Long): Long? {
        if (shown || !recording) {
            hiddenSince = null
            return null
        }
        val since = hiddenSince ?: nowMs.also { hiddenSince = it }
        return (since + graceMs - nowMs).coerceAtLeast(0)
    }
}
