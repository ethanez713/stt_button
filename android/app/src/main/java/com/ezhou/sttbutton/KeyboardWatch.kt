package com.ezhou.sttbutton

/**
 * Decides when a take whose keyboard went away should stop. Gboard drops its window for a moment
 * when it switches panels, so only an absence that lasts [graceMs] counts.
 */
class KeyboardWatch(private val graceMs: Long) {
    private var goneSince: Long? = null

    fun due(imePresent: Boolean, takeOpen: Boolean, nowMs: Long): Boolean {
        if (imePresent || !takeOpen) {
            goneSince = null
            return false
        }
        val since = goneSince ?: nowMs.also { goneSince = it }
        return nowMs - since >= graceMs
    }
}
