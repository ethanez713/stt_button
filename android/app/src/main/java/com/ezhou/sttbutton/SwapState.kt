package com.ezhou.sttbutton

enum class SwapPhase { OFF, PENDING, LIVE, FAILED }

enum class ViewStart { LIVE, STAYED, STRANDED }

/**
 * One take's keyboard swap and every timing rule of it. [started] says what `switchToInputMethod`
 * returned; the time is passed in so nothing here reads a clock.
 */
class SwapState private constructor(
    phase: SwapPhase,
    private val startedAtMs: Long,
    private val switched: Boolean,
    private val switchAsked: Boolean,
) {
    var phase = phase
        private set
    private var returned = false
    private var viewFinishedAtMs: Long? = null
    private var viewStartedAtMs: Long? = null

    /** The default keyboard was changed by this take and not yet put back. */
    val returnOwed: Boolean get() = switched && !returned

    /** The take's own keyboard is up or on its way, so the button's absence means nothing. */
    val holdsTake: Boolean get() = phase == SwapPhase.PENDING || phase == SwapPhase.LIVE

    /** What the log says of this swap: `ok` once the keyboard came up, `failed` when it was asked for and did not, `off` when it was never asked for. */
    val logName: String get() = when {
        viewStartedAtMs != null -> "ok"
        switchAsked -> "failed"
        else -> "off"
    }

    /** Milliseconds from the request to the keyboard coming up, or null unless `ok`. */
    val swapMs: Long? get() = viewStartedAtMs?.let { it - startedAtMs }

    /** True once, when the wait for the keyboard has run out. */
    fun poll(nowMs: Long): Boolean {
        if (phase != SwapPhase.PENDING || nowMs - startedAtMs < PENDING_TIMEOUT_MS) return false
        phase = SwapPhase.FAILED
        return true
    }

    /** Arms the stop that follows the keyboard's view closing; true when it was armed. */
    fun onViewFinish(nowMs: Long): Boolean {
        if (phase != SwapPhase.LIVE) return false
        viewFinishedAtMs = nowMs
        return true
    }

    /** Whether the view has stayed closed for [KEYBOARD_STOP_MS]. */
    fun keyboardStopDue(nowMs: Long): Boolean = viewFinishedAtMs?.let { nowMs - it >= KEYBOARD_STOP_MS } == true

    fun onViewStart(nowMs: Long): ViewStart {
        poll(nowMs)
        viewFinishedAtMs = null
        return when (phase) {
            SwapPhase.PENDING -> ViewStart.LIVE.also {
                phase = SwapPhase.LIVE
                viewStartedAtMs = nowMs
            }
            SwapPhase.LIVE -> ViewStart.STAYED
            SwapPhase.OFF, SwapPhase.FAILED -> ViewStart.STRANDED
        }
    }

    fun markReturned() {
        returned = true
    }

    companion object {
        const val PENDING_TIMEOUT_MS = 1_000L
        const val KEYBOARD_STOP_MS = 700L

        fun off() = SwapState(SwapPhase.OFF, 0, switched = false, switchAsked = false)

        fun started(switchReturned: Boolean, nowMs: Long) =
            SwapState(if (switchReturned) SwapPhase.PENDING else SwapPhase.FAILED, nowMs, switched = switchReturned, switchAsked = true)
    }
}
