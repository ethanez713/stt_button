package com.ezhou.sttbutton

enum class TakeState { OPEN, STOPPING, INSERTED, DISCARDED }

enum class DiscardReason(val logName: String) {
    LANGUAGE("language"),
    PACKAGE("package"),
    NO_FIELD("no_field"),
    EMPTY("empty"),
    ERROR("error"),
}

/**
 * One take's lifecycle (design § Phone app): open from the start tap, stopping from the stop tap or the
 * cap until the text is inserted or the take resumes, then inserted or discarded. Every transition says
 * whether it applied, so a late event (a second stop, a language change after the insert) is ignored.
 */
class Take(val id: String, val pkg: String, val startedAtMs: Long) {
    var state = TakeState.OPEN
        private set
    var discardReason: DiscardReason? = null
        private set

    /** True while a stop is in progress, until the take resumes or finishes. */
    val busy: Boolean get() = state == TakeState.STOPPING

    val finished: Boolean get() = state == TakeState.INSERTED || state == TakeState.DISCARDED

    fun requestStop(): Boolean = move(TakeState.OPEN, TakeState.STOPPING)

    fun resume(): Boolean = move(TakeState.STOPPING, TakeState.OPEN)

    fun markInserted(): Boolean = move(TakeState.STOPPING, TakeState.INSERTED)

    fun discard(reason: DiscardReason): Boolean {
        if (finished) return false
        state = TakeState.DISCARDED
        discardReason = reason
        return true
    }

    private fun move(from: TakeState, to: TakeState): Boolean {
        if (state != from) return false
        state = to
        return true
    }
}
