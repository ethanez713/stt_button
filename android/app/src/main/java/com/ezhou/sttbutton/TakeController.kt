package com.ezhou.sttbutton

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Runs takes for the accessibility service: the tap starts one, the next tap or the cap stops it, and
 * the text is inserted at the stop and after each pause of `insert_pause_s`. Everything here is called on the main thread except the
 * worker's own loop, which hands its result back through the handler.
 */
class TakeController(private val service: DictationService, private val overlay: Overlay, private val bubble: BubbleOverlay) {
    private val tag = DictationService.TAG
    private val handler = Handler(Looper.getMainLooper())
    private val cfg = PipelineConfig(org.json.JSONObject(service.assets.open("pipeline.json").bufferedReader().readText()))
    private val log = EditLog(File(service.filesDir, "logs/edits.jsonl"))
    private val inserter = Inserter(service)
    private val takesDir = File(service.filesDir, "takes")
    private var run: Run? = null
    private val bubbleQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var tracker: FieldTracker? = null
    /** Accessibility events this process has handled, so the log shows the traffic between takes. */
    private var eventsSeen = 0L
    private val capRunnable = Runnable { run?.let(::stop) }
    private var parked: Models? = null
    private var shutDown = false
    private val releaseParked = Runnable { releaseParkedNow() }
    private val hiddenWatch = HiddenWatch(HIDDEN_GRACE_MS)
    private val hiddenCheck = Runnable { service.recheck() }
    private val caretPoll = Runnable { pollCaret() }

    private class Run(
        val take: Take, val pcm: File, val models: Models, val live: LiveTake, val recorder: Recorder,
        val cost: TakeCost, val sampler: CostSampler, val bubbleText: BubbleText, val preview: PreviewRecognizer,
    ) {
        @Volatile var stopRequested = false
        @Volatile var aborted = false
        @Volatile var stopTrigger = TRIGGER_STOP
        var stoppedAtMs = 0L

        /** The field the take was started in, and where its text goes. */
        var field: AccessibilityNodeInfo? = null
        var anchor = -1
        var accepted = Caret.UNKNOWN
        var sawConnection = false
        var userCaret = -1
        var caretLeftField = false
        var caretSource: String? = null
        var graceUntilMs = 0L
        var ignoredCarets: Set<Int> = emptySet()
        var unreadPolls = 0

        /** Text of segments that found no field to go into, kept for the clipboard at the end of the take. */
        val unplaced = StringBuilder()
        var unplacedReason: DiscardReason? = null
    }

    /** A take that exited with the process still owns a PCM file nobody will read. */
    fun deleteLeftovers() {
        takesDir.listFiles()?.forEach { it.delete() }
    }

    /** The open take's state for the trace log, NONE between takes. */
    val stateName: String get() = run?.take?.state?.name ?: "NONE"

    fun onTap() {
        val current = run
        when {
            current == null -> start()
            current.take.state == TakeState.OPEN -> stop(current)
            else -> Unit
        }
    }

    /**
     * Called with every placement. A take records only while its button is on screen: the bubble
     * leaves with the button, and a button that stays away for [HIDDEN_GRACE_MS] stops the take
     * as a tap would.
     */
    fun onButtonShown(shown: Boolean) {
        handler.removeCallbacks(hiddenCheck)
        if (!shown) bubble.hide()
        val current = run?.takeIf { it.take.state == TakeState.OPEN }
        val wait = hiddenWatch.stopInMs(shown, current != null, SystemClock.uptimeMillis())
        if (current == null) return
        when (wait) {
            null -> if (!bubble.isShowing) {
                bubble.show()
                scheduleBubble()
            }
            0L -> stop(current, TRIGGER_KEYBOARD)
            else -> handler.postDelayed(hiddenCheck, wait)
        }
    }

    private fun start() {
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "tap ignored: the microphone permission is not granted")
            return
        }
        tracker?.end()
        val field = service.focusedField()
        val pkg = field?.packageName?.toString() ?: service.activePackage() ?: UNKNOWN_PACKAGE
        val take = Take(UUID.randomUUID().toString(), pkg, SystemClock.elapsedRealtime())
        val warm = parked
        parked = null
        handler.removeCallbacks(releaseParked)
        log.write("start", take.id, "pkg" to pkg, "models_warm" to (warm != null),
            "proc_cpu_ms" to Process.getElapsedCpuTime(),
            "proc_age_ms" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
            "proc_events" to eventsSeen)
        try {
            service.startForegroundService(Intent(service, RecordingService::class.java))
        } catch (e: Exception) {
            Log.e(tag, "microphone foreground service refused", e)
        }
        val models = warm ?: Models.forFiles(service.filesDir, cfg)
        val bubbleText = BubbleText().apply { onChange = ::scheduleBubble }
        val live = LiveTake(models.recognizer, models::cleaner, cfg, bubbleText, models::prepareCleaner)
        val preview = PreviewRecognizer(models::awaitPreview, bubbleText::preview)
        val pcm = File(takesDir, "${take.id}.pcm")
        val recorder = Recorder(pcm) { samples, length ->
            live.addSamples(samples, length)
            preview.offer(samples, length, live.slicer.paused)
        }
        val cost = TakeCost(AndroidCostProbe(service)).also { it.start() }
        val current = Run(take, pcm, models, live, recorder, cost, CostSampler(cost), bubbleText, preview)
        try {
            recorder.start()
        } catch (e: Exception) {
            Log.e(tag, "recording failed to start", e)
            run = current
            discard(DiscardReason.ERROR)
            return
        }
        current.field = field
        val initial = inserter.readCaret(field)
        current.sawConnection = initial.fromConnection
        current.accepted = initial.caret
        current.anchor = when {
            initial.caret.known -> initial.caret.end
            else -> field?.textSelectionEnd ?: -1
        }
        if (!current.accepted.known && current.anchor >= 0) current.accepted = Caret(current.anchor, current.anchor)
        service.watchSelectionChanges(true)
        run = current
        Log.i(tag, "take start pkg=$pkg anchor=${current.anchor} connection=${current.sawConnection}")
        bubble.show()
        handler.postDelayed(caretPoll, CARET_POLL_MS)
        thread(name = "take-worker") { work(current) }
        handler.postDelayed(capRunnable, cfg.takeCapS * 1000L)
        overlay.setMode(ButtonMode.RECORDING)
    }

    private fun stop(current: Run, trigger: String = TRIGGER_STOP) {
        if (!current.take.requestStop()) return
        current.stopTrigger = trigger
        Log.i(tag, "take stop trigger=$trigger")
        handler.removeCallbacks(capRunnable)
        handler.removeCallbacks(caretPoll)
        overlay.setMode(ButtonMode.BUSY)
        current.stoppedAtMs = SystemClock.elapsedRealtime()
        current.recorder.stop()
        current.stopRequested = true
        service.watchSelectionChanges(false)
        if (trigger == TRIGGER_KEYBOARD || trigger == TRIGGER_PACKAGE) bubble.hide()
        service.recheck()
    }

    /** [trigger] is the stop that found nothing to insert, or null when no stop led here. */
    fun discard(reason: DiscardReason, trigger: String? = null) {
        val current = run ?: return
        if (!current.take.discard(reason)) return
        log.write("discard", current.take.id, "reason" to reason.logName, "trigger" to trigger, *current.cost.lap().toTypedArray())
        current.aborted = true
        current.stopRequested = true
        handler.removeCallbacks(capRunnable)
        current.recorder.stop()
        current.models.cleaner().cancel()
        release(current)
    }

    private fun work(current: Run) {
        try {
            listening@ while (!current.stopRequested) {
                if (current.live.step()) continue
                if (cfg.stopSilenceS?.let { current.live.silenceS() >= it } == true) {
                    handler.post { stop(current, TRIGGER_SILENCE) }
                    while (!current.stopRequested) Thread.sleep(IDLE_POLL_MS)
                    break@listening
                }
                if (current.live.segmentDue()) {
                    val dueAtMs = SystemClock.elapsedRealtime()
                    val segment = current.live.finishSegment()
                    handler.post { insert(current, segment, TRIGGER_PAUSE, dueAtMs) }
                    continue
                }
                Thread.sleep(IDLE_POLL_MS)
            }
            if (!current.aborted) {
                current.live.markStop()
                val result = current.live.finish()
                handler.post { insert(current, result, current.stopTrigger, current.stoppedAtMs) }
            }
        } catch (e: Throwable) {
            Log.e(tag, "take failed", e)
            handler.post { if (run === current) discard(DiscardReason.ERROR) }
        } finally {
            current.preview.stop()
            current.pcm.delete()
            handler.post { park(current.models) }
        }
    }

    /** Inserts one segment: the pause's text while the take stays open, or the last one at the stop. */
    private fun insert(current: Run, result: LiveResult, trigger: String, triggerAtMs: Long) {
        val take = current.take
        val last = trigger != TRIGGER_PAUSE
        if (take.finished || (last && take.state != TakeState.STOPPING)) return
        val text = if (result.cleaned.isBlank()) result.prefix.trim() else result.prefix + result.cleaned
        if (text.isBlank()) {
            if (!last) return
            if (current.unplaced.isNotEmpty()) copyUnplaced(current, result, trigger)
            else if (take.finishWithoutText()) {
                log.write("end", take.id, "trigger" to trigger, "caret_source" to current.caretSource,
                    "audio_ms" to result.audioMs, *current.cost.lap().toTypedArray())
                release(current)
            } else discard(DiscardReason.EMPTY, trigger)
            return
        }
        noteFocus(current)
        val field = targetField(current)
        val reason = when {
            field == null -> DiscardReason.NO_FIELD
            take.pkg != UNKNOWN_PACKAGE && field.packageName?.toString() != take.pkg -> DiscardReason.PACKAGE
            else -> null
        }
        if (reason != null || field == null || current.unplaced.isNotEmpty()) {
            current.unplaced.append(text)
            current.unplacedReason = current.unplacedReason ?: reason ?: DiscardReason.NO_FIELD
            if (last) copyUnplaced(current, result, trigger) else stop(current, trigger = TRIGGER_FIELD_LOST)
            return
        }
        val segment = take.segmentsInserted
        // Once the user has taken the caret, the rest of the take writes where it was dictating.
        val redirected = current.userCaret >= 0 || current.caretLeftField
        val at = current.anchor.takeIf { redirected && it >= 0 }
        val live = inserter.readCaret(field)
        val caretBefore = at ?: if (live.caret.known) live.caret.end else field.textSelectionEnd
        val opener = if (segment == 0) Pipeline.takeOpener(inserter.textOf(field), caretBefore) else ""
        val placing = opener + text
        armCaretGrace(current, caretBefore, placing.length)
        val outcome = inserter.insert(
            field, placing, at, current.userCaret.takeIf { at != null && !current.caretLeftField },
            useConnection = !current.caretLeftField,
        )
        current.graceUntilMs = SystemClock.elapsedRealtime() + GRACE_MS
        if (outcome.landed && caretBefore >= 0) {
            current.anchor = caretBefore + placing.length
            current.accepted = if (outcome.restoredCaret >= 0) Caret(outcome.restoredCaret, outcome.restoredCaret)
            else Caret(current.anchor, current.anchor)
        } else {
            val after = inserter.readCaret(field)
            if (after.caret.known) current.accepted = after.caret
        }
        if (outcome.restoredCaret >= 0) current.userCaret = outcome.restoredCaret
        if (outcome.copied && outcome.readback == "unchanged") {
            Toast.makeText(service, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
        }
        if (last) {
            take.markInserted()
            current.sampler.stop()
        } else {
            take.markSegmentInserted()
        }
        log.write("insert", take.id,
            "pkg" to take.pkg, "raw" to result.raw, "cleaned" to result.cleaned, "text_before" to outcome.textBefore,
            "insert_path" to outcome.path, "landed" to outcome.landed,
            "readback" to outcome.readback, "attempts" to outcome.attempts, "copied" to outcome.copied,
            "sel_before" to caretBefore, "sel_after" to inserter.readCaret(field).caret.end,
            "caret_source" to current.caretSource,
            "asr_ms" to result.asrMs, "clean_ms" to result.cleanMs, "asr_backend" to "cpu", "clean_backend" to "cpu",
            "audio_ms" to result.audioMs,
            "in_tokens" to result.inTokens, "out_tokens" to result.outTokens,
            "clean_mode" to result.cleanMode, "chunks" to result.chunks,
            "idle_cleans" to result.idleCleans, "idle_hits" to result.idleHits, "cue" to result.cue,
            "context_decodes" to result.contextDecodes, "context_fallbacks" to result.contextFallbacks, "drifts" to result.drifts, "corrections" to result.corrections, "corrections_kept" to result.correctionsKept,
            "segment" to segment, "trigger" to trigger,
            "asr_ms_after_stop" to result.asrMsAfterStop, "clean_ms_after_stop" to result.cleanMsAfterStop,
            "stop_to_insert_ms" to SystemClock.elapsedRealtime() - triggerAtMs,
            *current.cost.lap().toTypedArray())
        track(take, field)
        if (last) release(current)
    }

    /**
     * The field the take was for is gone or belongs to another app, so the text has nowhere to go:
     * it is put on the clipboard and the user is told, instead of losing what they dictated.
     */
    private fun copyUnplaced(current: Run, result: LiveResult, trigger: String) {
        val text = current.unplaced.toString().trim()
        val reason = current.unplacedReason ?: DiscardReason.NO_FIELD
        val copied = try {
            service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", text))
            true
        } catch (e: Exception) {
            Log.e(tag, "could not copy the dictation to the clipboard", e)
            false
        }
        if (copied) Toast.makeText(service, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
        log.write("clipboard", current.take.id,
            "pkg" to current.take.pkg, "reason" to reason.logName, "trigger" to trigger, "copied" to copied,
            "raw" to result.raw, "text" to text, "clean_mode" to result.cleanMode, "audio_ms" to result.audioMs,
            *current.cost.lap().toTypedArray())
        current.take.discard(reason)
        release(current)
    }

    private fun track(take: Take, field: AccessibilityNodeInfo) {
        val existing = tracker
        if (existing != null && existing.takeId == take.id) existing.refresh(inserter.textOf(field))
        else tracker = FieldTracker(take.id, field, inserter.textOf(field))
    }

    private fun release(current: Run) {
        Log.i(tag, "take end state=${current.take.state} reason=${current.take.discardReason?.logName}")
        current.sampler.stop()
        bubble.hide()
        handler.removeCallbacks(caretPoll)
        if (run === current) run = null
        overlay.setMode(ButtonMode.IDLE)
        service.watchSelectionChanges(false)
        service.stopService(Intent(service, RecordingService::class.java))
        service.recheck()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        eventsSeen++
        run?.let { current ->
            val focused = service.focusedField()?.packageName?.toString()
            if (focused != null && current.take.pkg != UNKNOWN_PACKAGE && focused != current.take.pkg) stop(current, TRIGGER_PACKAGE)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) noteSelectionEvent(current, event)
        }
        tracker?.onEvent(event)
    }

    /** The input connection reported a new selection. Absolute indices, the same ones the keyboard gets. */
    fun onImeSelection(start: Int, end: Int) {
        val current = run ?: return
        noteCaret(current, Caret(start, end), fromConnection = true, source = "ime")
    }

    /**
     * While a take is open, re-read the caret. Not every editor delivers [onImeSelection] to an
     * accessibility service, and a selection event's node often is not the node focus returned.
     */
    private fun pollCaret() {
        val current = run ?: return
        if (current.take.state != TakeState.OPEN) return
        noteFocus(current)
        if (current.take.state != TakeState.OPEN) return
        val read = inserter.readCaret(current.field)
        noteCaret(current, read.caret, read.fromConnection, "poll")
        if (run === current && current.take.state == TakeState.OPEN) handler.postDelayed(caretPoll, CARET_POLL_MS)
    }

    private fun noteFocus(current: Run) {
        val field = current.field ?: return
        val focused = service.focusedField() ?: return
        if (!CaretWatch.differentBox(editorId(field), editorId(focused))) return
        current.caretLeftField = true
        if (current.caretSource == null) current.caretSource = "focus"
        if (current.take.state == TakeState.OPEN) stop(current, TRIGGER_CARET)
    }

    private fun noteSelectionEvent(current: Run, event: AccessibilityEvent) {
        if (current.take.state != TakeState.OPEN) return
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != current.take.pkg) return
        val source = event.source
        val field = current.field
        if (source != null && field != null && source != field &&
            !CaretWatch.sameEditor(editorId(field), editorId(source))) return
        noteCaret(current, Caret(event.fromIndex, event.toIndex), fromConnection = false, source = "event")
    }

    private fun noteCaret(current: Run, caret: Caret, fromConnection: Boolean, source: String) {
        if (current.take.state != TakeState.OPEN) return
        if (!caret.known) {
            if (source == "poll") {
                current.unreadPolls++
                if (current.unreadPolls == UNREAD_POLLS_LOG) Log.i(tag, "caret still unreadable")
            }
            return
        }
        if (!current.accepted.known) {
            current.accepted = caret
            current.anchor = caret.end
            current.sawConnection = fromConnection
            return
        }
        // The node and the input connection disagree until the connection has been read once.
        if (!current.sawConnection && fromConnection) {
            current.sawConnection = true
            if (caret.collapsed && caret != current.accepted) {
                current.accepted = caret
                current.anchor = caret.end
                return
            }
        }
        val inGrace = SystemClock.elapsedRealtime() < current.graceUntilMs
        if (!CaretWatch.userMoved(current.accepted, caret, current.ignoredCarets, inGrace)) return
        if (current.userCaret < 0) current.userCaret = caret.end
        if (current.caretSource == null) current.caretSource = source
        Log.i(tag, "caret moved source=$source anchor=${current.anchor} sel=${caret.start}..${caret.end}")
        stop(current, TRIGGER_CARET)
    }

    /** Carets this insert is about to produce. A different one in the same moment is the user. */
    private fun armCaretGrace(current: Run, caretBefore: Int, textLength: Int) {
        val expectedEnd = if (caretBefore >= 0) caretBefore + textLength else -1
        current.ignoredCarets = buildSet {
            if (current.anchor >= 0) add(current.anchor)
            if (current.accepted.known) {
                add(current.accepted.start)
                add(current.accepted.end)
            }
            if (caretBefore >= 0) add(caretBefore)
            if (expectedEnd >= 0) add(expectedEnd)
            if (current.userCaret >= 0 && caretBefore >= 0) add(CaretWatch.restored(current.userCaret, caretBefore, textLength))
        }
        current.graceUntilMs = SystemClock.elapsedRealtime() + GRACE_MS
    }

    /** The field the take started in, once the user has moved. Null when that field is gone. */
    private fun targetField(current: Run): AccessibilityNodeInfo? {
        val original = current.field
        if (current.userCaret >= 0 || current.caretLeftField) return original
        return service.focusedField() ?: original
    }

    private fun editorId(node: AccessibilityNodeInfo) =
        EditorId(node.windowId, node.viewIdResourceName, node.className?.toString())

    /** Redraws the bubble from the worker's and the preview thread's latest text, at most every [BUBBLE_MS]. */
    private fun scheduleBubble() {
        if (!bubbleQueued.compareAndSet(false, true)) return
        handler.postDelayed({
            bubbleQueued.set(false)
            run?.let { bubble.update(it.bubbleText.text()) }
        }, BUBBLE_MS)
    }

    fun onTrimMemory(level: Int) {
        run?.cost?.noteTrim(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseParkedNow()
    }

    /** Keeps a finished take's models for [WARM_MS] so a take that follows starts without loading them. */
    private fun park(models: Models) {
        if (shutDown || run != null || parked != null || !models.reusable) {
            closeInBackground(models)
            return
        }
        parked = models
        handler.postDelayed(releaseParked, WARM_MS)
    }

    private fun releaseParkedNow() {
        handler.removeCallbacks(releaseParked)
        parked?.let(::closeInBackground)
        parked = null
    }

    /** Closing joins the loader threads, so it must not run on the main thread. */
    private fun closeInBackground(models: Models) {
        thread(name = "models-close") { models.close() }
    }

    fun logProcessExits() = ExitReasons.record(service, log, File(service.filesDir, "logs/edits.jsonl"))

    fun shutdown() {
        shutDown = true
        releaseParkedNow()
        discard(DiscardReason.ERROR)
        tracker?.end()
    }

    /**
     * Follows the field a take was inserted into, to log what the user sent. The latest non-empty
     * text is kept because a field often clears on send, and it is written when focus leaves.
     */
    private inner class FieldTracker(val takeId: String, private val field: AccessibilityNodeInfo, initial: String) {
        private var latest = initial
        private val pkg = field.packageName?.toString()

        init {
            service.watchTextChanges(true)
        }

        fun refresh(text: String) {
            if (text.isNotEmpty()) latest = text
        }

        fun onEvent(event: AccessibilityEvent) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (event.source == field) {
                    event.source?.let(inserter::textOf)?.takeIf { it.isNotEmpty() }?.let { latest = it }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> if (event.source != field) end()
                AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                    if (service.focusedField()?.packageName?.toString() != pkg) end()
            }
        }

        fun end() {
            if (tracker !== this) return
            tracker = null
            service.watchTextChanges(false)
            log.write("edit", takeId, "text_after" to latest)
        }
    }

    companion object {
        private const val UNKNOWN_PACKAGE = "unknown"
        private const val IDLE_POLL_MS = 25L
        private const val TRIGGER_PAUSE = "pause"
        private const val TRIGGER_STOP = "stop"
        private const val TRIGGER_KEYBOARD = "keyboard"
        private const val TRIGGER_PACKAGE = "package"
        private const val TRIGGER_SILENCE = "silence"
        private const val TRIGGER_CARET = "caret"
        private const val GRACE_MS = 400L
        private const val CARET_POLL_MS = 100L
        private const val UNREAD_POLLS_LOG = 5
        private const val TRIGGER_FIELD_LOST = "field_lost"
        private const val BUBBLE_MS = 100L
        private const val WARM_MS = 30_000L
        private const val HIDDEN_GRACE_MS = 700L
    }
}
