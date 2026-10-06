package com.ezhou.sttbutton

enum class SwapPhase { OFF, PENDING, LIVE, FAILED }

enum class ViewStart { LIVE, STAYED, STRANDED }

/**
 * One take's keyboard swap and every timing rule of it. [started] says what `switchToInputMethod`
 * returned; the time is passed in so nothing here reads a clock.
 */
class SwapState private constructor(phase: SwapPhase, private val startedAtMs: Long, private val switched: Boolean) {
    var phase = phase
        private set
    private var returned = false

    /** The default keyboard was changed by this take and not yet put back. */
    val returnOwed: Boolean get() = switched && !returned

    /** The take's own keyboard is up or on its way, so the button's absence means nothing. */
    val holdsTake: Boolean get() = phase == SwapPhase.PENDING || phase == SwapPhase.LIVE

    /** True once, when the wait for the keyboard has run out. */
    fun poll(nowMs: Long): Boolean {
        if (phase != SwapPhase.PENDING || nowMs - startedAtMs < PENDING_TIMEOUT_MS) return false
        phase = SwapPhase.FAILED
        return true
    }

    fun onViewStart(nowMs: Long): ViewStart {
        poll(nowMs)
        return when (phase) {
            SwapPhase.PENDING -> ViewStart.LIVE.also { phase = SwapPhase.LIVE }
            SwapPhase.LIVE -> ViewStart.STAYED
            SwapPhase.OFF, SwapPhase.FAILED -> ViewStart.STRANDED
        }
    }

    fun markReturned() {
        returned = true
    }

    companion object {
        const val PENDING_TIMEOUT_MS = 1_000L

        fun off() = SwapState(SwapPhase.OFF, 0, switched = false)

        fun started(switchReturned: Boolean, nowMs: Long) =
            SwapState(if (switchReturned) SwapPhase.PENDING else SwapPhase.FAILED, nowMs, switched = switchReturned)
    }
}
