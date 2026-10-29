package com.ezhou.sttbutton

import org.json.JSONArray

/** Where the text of a finished take can go, as the controller found the field when the engine finished. */
enum class Delivery { INSERT, BLANK, NO_FIELD, OTHER_PACKAGE }

sealed interface TakeEvent {
    data class Tap(val micGranted: Boolean) : TakeEvent
    data object RecorderStarted : TakeEvent
    data object RecorderStartFailed : TakeEvent
    class SwapAsked(val swap: SwapState) : TakeEvent
    data class ButtonShown(val shown: Boolean) : TakeEvent
    data object Cap : TakeEvent
    data object Silence : TakeEvent
    data object MicFailed : TakeEvent
    data object PackageChanged : TakeEvent
    data object LanguageLeft : TakeEvent
    data object ExcludedField : TakeEvent
    data object FieldGone : TakeEvent
    data object ViewStarted : TakeEvent
    data object ViewFinished : TakeEvent
    data object Error : TakeEvent
    data class EngineFinished(val delivery: Delivery) : TakeEvent

    /** Events a [TakeEffect.Schedule] asks to have sent back. */
    data class HiddenCheckDue(val token: Int) : TakeEvent
    data object SwapWaitDue : TakeEvent
    data object KeyboardStopDue : TakeEvent
    data object FlashEnded : TakeEvent
    data object ImeLineDue : TakeEvent
}

sealed interface TakeEffect {
    /** Load or reuse the models, start the engine and the recorder, and answer with RecorderStarted or RecorderStartFailed. */
    data object StartTake : TakeEffect
    data object AskSwap : TakeEffect
    data object StopRecorder : TakeEffect
    data object StopEngine : TakeEffect

    /** The recorder starts again on the take that is already open. */
    data object StartRecorder : TakeEffect

    /** The engine leaves the stop and keeps the same slicer, document and region. */
    data object ResumeEngine : TakeEffect
    data object CancelCleaner : TakeEffect
    data class ReturnKeyboard(val stranded: Boolean) : TakeEffect
    data object WriteImeLine : TakeEffect
    data object ShowBubble : TakeEffect
    data object HideBubble : TakeEffect
    data class PanelBusy(val busy: Boolean) : TakeEffect
    data class SetButton(val mode: ButtonMode) : TakeEffect
    data object Recheck : TakeEffect
    data class Insert(val trigger: String) : TakeEffect
    data class CopyUnplaced(val reason: DiscardReason, val trigger: String) : TakeEffect
    data class LogDiscard(val reason: DiscardReason, val trigger: String?) : TakeEffect

    /** Everything a take holds besides its models: the cost sampler, the keyboard hooks and the recording service. */
    data object Release : TakeEffect
    data object ParkModels : TakeEffect
    data class Schedule(val delayMs: Long, val event: TakeEvent) : TakeEffect
}

/**
 * One take's lifecycle, as a function of the events that reach it: every decision about starting,
 * stopping, discarding, the keyboard swap and the button's absence is here, and the controller turns
 * Android callbacks into events and carries out the effects. Every timed rule is an effect that schedules
 * an event, and an event that no longer applies is ignored, so nothing is cancelled.
 */
class TakeSession(val take: Take, private val capMs: Long, private val clock: () -> Long) {
    private enum class Phase { NEW, STARTING, OPEN, ENDED }

    private var phase = Phase.NEW
    private val hiddenWatch = HiddenWatch(HIDDEN_GRACE_MS)
    private var hiddenToken = 0
    private var bubbleShown = false
    private var flashing = false

    var swap: SwapState = SwapState.off()
        private set
    var stopTrigger: String = TRIGGER_STOP
        private set
    var stoppedAtMs = 0L
        private set
    private val stopTriggers = mutableListOf<String>()

    /** One entry per stop, in order. [stopTrigger] is the last. */
    val triggers: List<String> get() = stopTriggers
    var resumes = 0
        private set

    /** "done" once the engine finishes; "abandoned" when the take ends without that. */
    var cleanup = "abandoned"
        private set

    fun endingFields(trigger: String?): Array<Pair<String, Any?>> = arrayOf(
        "trigger" to trigger,
        "triggers" to JSONArray(triggers),
        "resumes" to resumes,
        "cleanup" to cleanup,
    )

    /** The take has ended and no flash is left on the button; a tap now starts another take. */
    val finished: Boolean get() = phase == Phase.ENDED && !flashing

    val ended: Boolean get() = phase == Phase.ENDED

    /** The take's own keyboard is up or coming, so a keyboard setting change is not the user leaving English. */
    val holdsTake: Boolean get() = phase != Phase.ENDED && swap.holdsTake

    val buttonMode: ButtonMode get() = when {
        flashing -> ButtonMode.FAILED
        phase == Phase.OPEN && take.state == TakeState.OPEN -> ButtonMode.RECORDING
        phase == Phase.OPEN -> ButtonMode.CLEANING
        else -> ButtonMode.IDLE
    }

    fun on(event: TakeEvent): List<TakeEffect> = buildList { handle(event, this) }

    private fun handle(event: TakeEvent, out: MutableList<TakeEffect>) {
        when (event) {
            is TakeEvent.Tap -> onTap(event, out)
            TakeEvent.RecorderStarted -> if (phase == Phase.STARTING) opened(out)
            TakeEvent.RecorderStartFailed -> if (phase == Phase.STARTING) discard(DiscardReason.ERROR, null, out)
            is TakeEvent.SwapAsked -> if (isOpen) {
                swap = event.swap
                if (swap.phase == SwapPhase.PENDING) out += TakeEffect.Schedule(SwapState.PENDING_TIMEOUT_MS, TakeEvent.SwapWaitDue)
            }
            is TakeEvent.ButtonShown -> if (isOpen) onButtonShown(event.shown, out)
            is TakeEvent.HiddenCheckDue -> if (isOpen && event.token == hiddenToken) out += TakeEffect.Recheck
            TakeEvent.SwapWaitDue -> if (isOpen && swap.poll(clock())) returnKeyboard(out)
            TakeEvent.Cap -> stop(TRIGGER_CAP, out)
            TakeEvent.Silence -> stop(TRIGGER_SILENCE, out)
            TakeEvent.MicFailed -> stop(TRIGGER_MIC, out)
            TakeEvent.PackageChanged -> stop(TRIGGER_PACKAGE, out)
            TakeEvent.FieldGone -> stop(TRIGGER_FIELD, out)
            TakeEvent.ExcludedField -> if (swap.holdsTake) stop(TRIGGER_KEYBOARD, out)
            TakeEvent.ViewStarted -> onViewStarted(out)
            TakeEvent.ViewFinished -> if (isOpen && swap.onViewFinish(clock())) {
                out += TakeEffect.Schedule(SwapState.KEYBOARD_STOP_MS, TakeEvent.KeyboardStopDue)
            }
            TakeEvent.KeyboardStopDue -> if (isOpen && swap.keyboardStopDue(clock())) stop(TRIGGER_KEYBOARD, out)
            TakeEvent.LanguageLeft -> if (isOpen && !swap.holdsTake) discard(DiscardReason.LANGUAGE, null, out)
            TakeEvent.Error -> if (isOpen) discard(DiscardReason.ERROR, null, out)
            is TakeEvent.EngineFinished -> onEngineFinished(event.delivery, out)
            TakeEvent.FlashEnded -> if (flashing) {
                flashing = false
                out += TakeEffect.SetButton(buttonMode)
            }
            TakeEvent.ImeLineDue -> out += TakeEffect.WriteImeLine
        }
    }

    private val isOpen: Boolean get() = phase == Phase.OPEN && !take.finished

    private fun onTap(tap: TakeEvent.Tap, out: MutableList<TakeEffect>) {
        when {
            flashing -> Unit
            phase == Phase.NEW && !tap.micGranted -> {
                phase = Phase.ENDED
                flash(out)
            }
            phase == Phase.NEW -> {
                phase = Phase.STARTING
                out += TakeEffect.StartTake
            }
            isOpen && take.state == TakeState.OPEN -> stop(TRIGGER_STOP, out)
            isOpen && take.state == TakeState.STOPPING && stopTrigger != TRIGGER_CAP && tap.micGranted -> resume(out)
        }
    }

    private fun opened(out: MutableList<TakeEffect>) {
        phase = Phase.OPEN
        out += TakeEffect.AskSwap
        bubbleShown = true
        out += TakeEffect.ShowBubble
        out += TakeEffect.Schedule(capMs, TakeEvent.Cap)
        out += TakeEffect.SetButton(buttonMode)
    }

    private fun onButtonShown(shown: Boolean, out: MutableList<TakeEffect>) {
        hiddenToken++
        if (swap.holdsTake) {
            hiddenWatch.stopInMs(shown = true, recording = false, clock())
            return
        }
        if (!shown && bubbleShown) {
            bubbleShown = false
            out += TakeEffect.HideBubble
        }
        val recording = take.state == TakeState.OPEN
        when (val wait = hiddenWatch.stopInMs(shown, recording, clock())) {
            null -> if (recording && !bubbleShown) {
                bubbleShown = true
                out += TakeEffect.ShowBubble
            }
            0L -> stop(TRIGGER_KEYBOARD, out)
            else -> if (recording) out += TakeEffect.Schedule(wait, TakeEvent.HiddenCheckDue(hiddenToken))
        }
    }

    private fun onViewStarted(out: MutableList<TakeEffect>) {
        if (!isOpen || swap.onViewStart(clock()) == ViewStart.STRANDED) out += TakeEffect.ReturnKeyboard(stranded = true)
    }

    private fun stop(trigger: String, out: MutableList<TakeEffect>) {
        if (!isOpen || !take.requestStop()) return
        stopTriggers += trigger
        stopTrigger = trigger
        if (trigger == TRIGGER_MIC) flash(out) else out += TakeEffect.SetButton(buttonMode)
        stoppedAtMs = clock()
        out += TakeEffect.PanelBusy(true)
        out += TakeEffect.StopRecorder
        out += TakeEffect.StopEngine
        if (trigger == TRIGGER_KEYBOARD || trigger == TRIGGER_PACKAGE) {
            bubbleShown = false
            out += TakeEffect.HideBubble
        }
        out += TakeEffect.Recheck
    }

    private fun resume(out: MutableList<TakeEffect>) {
        if (!take.resume()) return
        resumes++
        out += TakeEffect.CancelCleaner
        out += TakeEffect.ResumeEngine
        out += TakeEffect.StartRecorder
        out += TakeEffect.PanelBusy(false)
        if (!bubbleShown) {
            bubbleShown = true
            out += TakeEffect.ShowBubble
        }
        out += TakeEffect.SetButton(buttonMode)
    }

    private fun onEngineFinished(delivery: Delivery, out: MutableList<TakeEffect>) {
        if (!isOpen || take.state != TakeState.STOPPING) return
        cleanup = "done"
        when (delivery) {
            Delivery.BLANK -> discard(DiscardReason.EMPTY, stopTrigger, out)
            Delivery.NO_FIELD -> unplaced(DiscardReason.NO_FIELD, out)
            Delivery.OTHER_PACKAGE -> unplaced(DiscardReason.PACKAGE, out)
            Delivery.INSERT -> {
                out += TakeEffect.Insert(stopTrigger)
                take.markInserted()
                end(out)
            }
        }
    }

    private fun unplaced(reason: DiscardReason, out: MutableList<TakeEffect>) {
        out += TakeEffect.CopyUnplaced(reason, stopTrigger)
        take.discard(reason)
        end(out)
    }

    private fun discard(reason: DiscardReason, trigger: String?, out: MutableList<TakeEffect>) {
        if (!take.discard(reason)) return
        out += TakeEffect.StopRecorder
        out += TakeEffect.LogDiscard(reason, trigger)
        if (reason == DiscardReason.ERROR) flash(out)
        out += TakeEffect.CancelCleaner
        end(out)
    }

    private fun flash(out: MutableList<TakeEffect>) {
        flashing = true
        out += TakeEffect.SetButton(ButtonMode.FAILED)
        out += TakeEffect.Schedule(FAILED_HOLD_MS, TakeEvent.FlashEnded)
    }

    private fun end(out: MutableList<TakeEffect>) {
        phase = Phase.ENDED
        bubbleShown = false
        out += TakeEffect.Release
        returnKeyboard(out)
        out += TakeEffect.SetButton(buttonMode)
        out += TakeEffect.Recheck
        out += TakeEffect.ParkModels
    }

    /** Puts the user's keyboard back, once per take. */
    private fun returnKeyboard(out: MutableList<TakeEffect>) {
        if (!swap.returnOwed) return
        swap.markReturned()
        out += TakeEffect.ReturnKeyboard(stranded = false)
        out += TakeEffect.Schedule(IME_LINE_DELAY_MS, TakeEvent.ImeLineDue)
    }

    companion object {
        const val TRIGGER_STOP = "stop"
        const val TRIGGER_CAP = "cap"
        const val TRIGGER_KEYBOARD = "keyboard"
        const val TRIGGER_PACKAGE = "package"
        const val TRIGGER_FIELD = "field"
        const val TRIGGER_SILENCE = "silence"
        const val TRIGGER_MIC = "mic"
        const val HIDDEN_GRACE_MS = 700L
        const val IME_LINE_DELAY_MS = 500L
        const val FAILED_HOLD_MS = 1_500L
    }
}
