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
 * cap until the text is inserted, then inserted or discarded. Every transition says whether it applied,
 * so a late event (a second tap, a language change after the insert) is ignored by the caller.
 */
class Take(val id: String, val pkg: String, val startedAtMs: Long) {
    var state = TakeState.OPEN
        private set
    var discardReason: DiscardReason? = null
        private set

    /** Segments of this take whose text has been inserted. */
    var segmentsInserted = 0
        private set

    /** The button ignores taps from the stop until the insert. */
    val busy: Boolean get() = state == TakeState.STOPPING

    val finished: Boolean get() = state == TakeState.INSERTED || state == TakeState.DISCARDED

    fun requestStop(): Boolean = move(TakeState.OPEN, TakeState.STOPPING)

    /** A pause inserted the text so far and the take stays open. */
    fun markSegmentInserted(): Boolean {
        if (state != TakeState.OPEN && state != TakeState.STOPPING) return false
        segmentsInserted++
        return true
    }

    fun markInserted(): Boolean = move(TakeState.STOPPING, TakeState.INSERTED).also { if (it) segmentsInserted++ }

    /** The stop found nothing new to insert after earlier segments were inserted. */
    fun finishWithoutText(): Boolean = segmentsInserted > 0 && move(TakeState.STOPPING, TakeState.INSERTED)

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
