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
 * Runs takes for the accessibility service. The tap, the cap, silence, the button leaving the screen,
 * or a package change stops one, and its text is inserted once then. Everything here is called on the
 * main thread except the worker's own loop, which hands its result back through the handler.
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
    private val capRunnable = Runnable { run?.let { stop(it, TRIGGER_CAP) } }
    private var parked: Models? = null
    private var shutDown = false
    private val releaseParked = Runnable { releaseParkedNow() }
    private val hiddenWatch = HiddenWatch(HIDDEN_GRACE_MS)
    private val hiddenCheck = Runnable { service.recheck() }
    private var failedUntilMs = 0L
    private val endFlash = Runnable { applyButtonMode() }
    private val swapCheck = Runnable {
        val current = run ?: return@Runnable
        if (current.swap.poll(SystemClock.uptimeMillis())) returnKeyboard(current)
    }

    private val keyboardStopCheck = Runnable {
        val current = run ?: return@Runnable
        if (current.swap.keyboardStopDue(SystemClock.uptimeMillis())) stop(current, TRIGGER_KEYBOARD)
    }

    /** True while the take's own keyboard is up or coming, so a keyboard setting change is not the user leaving English. */
    val swapHoldsTake: Boolean get() = run?.swap?.holdsTake == true

    private class Run(
        val take: Take, val pcm: File, val models: Models, val live: LiveTake, val recorder: Recorder,
        val cost: TakeCost, val sampler: CostSampler, val bubbleText: BubbleText, val preview: PreviewRecognizer,
        val level: AudioLevel,
    ) {
        var swap: SwapState = SwapState.off()
        @Volatile var stopRequested = false
        @Volatile var aborted = false
        @Volatile var stopTrigger = TRIGGER_STOP
        var stoppedAtMs = 0L

        /** The field the take was started in. Text goes to the focused field, or to this one when nothing is. */
        var field: AccessibilityNodeInfo? = null
    }

    /** The open take's state for the trace log, NONE between takes. */
    val stateName: String get() = run?.take?.state?.name ?: "NONE"

    fun onTap() {
        if (SystemClock.uptimeMillis() < failedUntilMs) return
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
        if (run?.swap?.holdsTake == true) {
            hiddenWatch.stopInMs(shown = true, recording = false, SystemClock.uptimeMillis())
            return
        }
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
            flashFailed()
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
        val level = AudioLevel()
        val recorder = Recorder(pcm, onFailure = { e ->
            Log.e(tag, "recording failed", e)
            handler.post { run?.takeIf { it.pcm == pcm }?.let { stop(it, TRIGGER_MIC) } }
        }) { samples, length ->
            level.add(samples, length)
            live.addSamples(samples, length)
            preview.offer(samples, length, live.slicer.paused)
        }
        val cost = TakeCost(AndroidCostProbe(service)).also { it.start() }
        val current = Run(take, pcm, models, live, recorder, cost, CostSampler(cost), bubbleText, preview, level)
        try {
            recorder.start()
        } catch (e: Exception) {
            Log.e(tag, "recording failed to start", e)
            run = current
            discard(DiscardReason.ERROR)
            park(models)
            return
        }
        current.field = field
        pruneTakeAudio(pcm.name)
        run = current
        startSwap(current)
        Log.i(tag, "take start pkg=$pkg")
        bubble.show()
        thread(name = "take-worker") { work(current) }
        handler.postDelayed(capRunnable, cfg.takeCapS * 1000L)
        applyButtonMode()
    }

    private fun startSwap(current: Run) {
        ImeLink.stranded = false
        ImeLink.busy = false
        ImeLink.panelHeightPx = service.rememberedImeHeight() ?: 0
        ImeLink.stopKey = StopKeyPlacement.inPanel(overlay.lastShown, overlay.screen().bottom, panelHeightPx())
        ImeLink.onStop = { run?.takeIf { it === current }?.let(::stop) }
        ImeLink.onViewFinish = { onViewFinished(current) }
        ImeLink.onExcludedField = { if (run === current && current.swap.holdsTake) stop(current, TRIGGER_KEYBOARD) }
        current.swap = service.swapper.begin(SystemClock.uptimeMillis())
        ImeLink.swap = current.swap
        if (current.swap.phase == SwapPhase.PENDING) handler.postDelayed(swapCheck, SwapState.PENDING_TIMEOUT_MS)
    }

    private fun onViewFinished(current: Run) {
        if (run === current && current.swap.onViewFinish(SystemClock.uptimeMillis())) {
            handler.postDelayed(keyboardStopCheck, SwapState.KEYBOARD_STOP_MS)
        }
    }

    private fun panelHeightPx(): Int =
        ImeLink.panelHeightPx.takeIf { it > 0 } ?: (TakePanel.DEFAULT_HEIGHT_DP * service.resources.displayMetrics.density).toInt()

    /** Puts the user's keyboard back, once per take. */
    private fun returnKeyboard(current: Run): String? {
        if (!current.swap.returnOwed) return null
        current.swap.markReturned()
        val method = service.swapper.returnKeyboard()
        handler.postDelayed({ writeImeLine(current.take.id, method) }, IME_LINE_DELAY_MS)
        return method
    }

    private fun writeImeLine(takeId: String, method: String?) {
        val (returnMs, subtypeOk) = service.swapper.returnReport()
        log.write("ime", takeId, "ime_return" to (method ?: "failed"), "ime_return_ms" to returnMs,
            "ime_subtype_ok" to subtypeOk, "ime_height_delta_px" to service.imeHeightDelta())
    }

    private fun swapFields(current: Run): Array<Pair<String, Any?>> =
        arrayOf("ime_swap" to current.swap.logName, "ime_swap_ms" to current.swap.swapMs)

    private fun stop(current: Run, trigger: String = TRIGGER_STOP) {
        if (!current.take.requestStop()) return
        current.stopTrigger = trigger
        Log.i(tag, "take stop trigger=$trigger")
        handler.removeCallbacks(capRunnable)
        if (trigger == TRIGGER_MIC) flashFailed() else applyButtonMode()
        current.stoppedAtMs = SystemClock.elapsedRealtime()
        ImeLink.busy = true
        ImeLink.current?.refreshPanel()
        current.recorder.stop()
        current.stopRequested = true
        if (trigger == TRIGGER_KEYBOARD || trigger == TRIGGER_PACKAGE) bubble.hide()
        service.recheck()
    }

    /** [trigger] is the stop that found nothing to insert, or null when no stop led here. */
    fun discard(reason: DiscardReason, trigger: String? = null) {
        val current = run ?: return
        if (!current.take.discard(reason)) return
        current.aborted = true
        current.stopRequested = true
        handler.removeCallbacks(capRunnable)
        current.recorder.stop()
        log.write(
            "discard", current.take.id, "reason" to reason.logName, "trigger" to trigger,
            "audio_ms" to current.level.audioMs, "zero_ms" to current.level.zeroMs, "peak_dbfs" to current.level.peakDbfs,
            *swapFields(current), *current.cost.lap().toTypedArray(),
        )
        if (reason == DiscardReason.ERROR) flashFailed()
        current.models.cleaner().cancel()
        release(current)
    }

    private fun work(current: Run) {
        try {
            while (!current.stopRequested) {
                if (current.live.step()) continue
                if (cfg.stopSilenceS?.let { current.live.silenceS() >= it } == true) {
                    handler.post { stop(current, TRIGGER_SILENCE) }
                    while (!current.stopRequested) Thread.sleep(IDLE_POLL_MS)
                    break
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
            handler.post { park(current.models) }
        }
    }

    private fun insert(current: Run, result: LiveResult, trigger: String, triggerAtMs: Long) {
        val take = current.take
        if (take.finished || take.state != TakeState.STOPPING) return
        val field = targetField(current)
        val otherPackage = field != null && take.pkg != UNKNOWN_PACKAGE && field.packageName?.toString() != take.pkg
        val fieldText = if (field != null && !otherPackage) inserter.textOf(field) else null
        val body = if (fieldText != null && fieldText.isEmpty()) Pipeline.casual(result.cleaned, cfg) else result.cleaned
        if (body.isBlank()) {
            discard(DiscardReason.EMPTY, trigger)
            return
        }
        if (field == null || fieldText == null) {
            copyUnplaced(current, result, body, trigger, if (otherPackage) DiscardReason.PACKAGE else DiscardReason.NO_FIELD)
            return
        }
        val caret = inserter.readCaret(field)
        val caretBefore = if (caret.known) caret.end else field.textSelectionEnd
        val written = Pipeline.takeOpener(fieldText, caretBefore) + body
        val outcome = inserter.insert(field, written)
        if (outcome.copied && outcome.readback == "unchanged") {
            Toast.makeText(service, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
        }
        take.markInserted()
        current.sampler.stop()
        val caretAfter = inserter.readCaret(field)
        log.write("insert", take.id,
            "pkg" to take.pkg, "raw" to result.raw, "cleaned" to body, "text_before" to outcome.textBefore,
            "insert_path" to outcome.path, "landed" to outcome.landed,
            "readback" to outcome.readback, "landed_count" to outcome.landedCount,
            "attempts" to outcome.attempts, "copied" to outcome.copied,
            "sel_before" to caretBefore, "sel_after" to caretAfter.end,
            "asr_ms" to result.asrMs, "clean_ms" to result.cleanMs, "asr_backend" to "cpu", "clean_backend" to "cpu",
            "audio_ms" to result.audioMs, "zero_ms" to current.level.zeroMs, "peak_dbfs" to current.level.peakDbfs,
            "in_tokens" to result.inTokens, "out_tokens" to result.outTokens,
            "clean_mode" to result.cleanMode, "chunks" to result.chunks,
            "idle_cleans" to result.idleCleans, "idle_hits" to result.idleHits, "cue" to result.cue,
            "context_decodes" to result.contextDecodes, "context_fallbacks" to result.contextFallbacks, "drifts" to result.drifts, "corrections" to result.corrections, "corrections_kept" to result.correctionsKept,
            "trigger" to trigger, *swapFields(current), "a11y_conn" to service.a11yConnection(),
            "asr_ms_after_stop" to result.asrMsAfterStop, "clean_ms_after_stop" to result.cleanMsAfterStop,
            "stop_to_insert_ms" to SystemClock.elapsedRealtime() - triggerAtMs,
            *current.cost.lap().toTypedArray())
        track(take, field, if (outcome.readback == "landed") written else null, caretAfter.end.takeIf { caretAfter.known })
        release(current)
    }

    /**
     * The field the take was for is gone or belongs to another app, so the text has nowhere to go:
     * it is put on the clipboard and the user is told, instead of losing what they dictated.
     */
    private fun copyUnplaced(current: Run, result: LiveResult, text: String, trigger: String, reason: DiscardReason) {
        val clip = text.trim()
        val copied = try {
            service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", clip))
            true
        } catch (e: Exception) {
            Log.e(tag, "could not copy the dictation to the clipboard", e)
            false
        }
        if (copied) Toast.makeText(service, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
        log.write("clipboard", current.take.id,
            "pkg" to current.take.pkg, "reason" to reason.logName, "trigger" to trigger, "copied" to copied,
            "raw" to result.raw, "text" to clip, "clean_mode" to result.cleanMode, "audio_ms" to result.audioMs,
            "zero_ms" to current.level.zeroMs, "peak_dbfs" to current.level.peakDbfs,
            *swapFields(current), *current.cost.lap().toTypedArray())
        current.take.discard(reason)
        release(current)
    }

    private fun track(take: Take, field: AccessibilityNodeInfo, landed: String?, caretAfter: Int?) {
        val existing = tracker
        if (existing != null && existing.takeId == take.id) existing.refresh(inserter.textOf(field))
        else tracker = FieldTracker(take.id, field, inserter.textOf(field), landed, caretAfter)
    }

    private fun release(current: Run) {
        Log.i(tag, "take end state=${current.take.state} reason=${current.take.discardReason?.logName}")
        current.sampler.stop()
        bubble.hide()
        if (run === current) run = null
        handler.removeCallbacks(swapCheck)
        handler.removeCallbacks(keyboardStopCheck)
        ImeLink.swap = null
        ImeLink.onStop = null
        ImeLink.onViewFinish = null
        ImeLink.onExcludedField = null
        ImeLink.busy = false
        returnKeyboard(current)
        applyButtonMode()
        service.stopService(Intent(service, RecordingService::class.java))
        service.recheck()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        eventsSeen++
        run?.let { current ->
            val focused = service.focusedField()?.packageName?.toString()
            if (focused != null && current.take.pkg != UNKNOWN_PACKAGE && focused != current.take.pkg) stop(current, TRIGGER_PACKAGE)
        }
        tracker?.onEvent(event)
    }

    private fun targetField(current: Run): AccessibilityNodeInfo? = service.focusedField() ?: current.field?.takeIf { it.refresh() }

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
    private inner class FieldTracker(
        val takeId: String, private val field: AccessibilityNodeInfo, initial: String,
        private val inserted: String?, caretAfter: Int?,
    ) {
        private var latest = initial
        private val mirror = FieldMirror(initial)
        private var span = inserted?.let { SpanLocator.find(initial, it, caretAfter) }
        private val pkg = field.packageName?.toString()

        init {
            service.watchTextChanges(true)
        }

        fun refresh(text: String) = accept(text)

        private fun accept(text: String) {
            if (text.isEmpty()) return
            latest = text
            mirror.sync(text)?.let { edit -> span = span?.apply(edit) }
        }

        fun onEvent(event: AccessibilityEvent) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (event.source == field) {
                    event.source?.let(inserter::textOf)?.let(::accept)
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
            log.write("edit", takeId, "text_after" to latest, "span_start" to span?.start, "span_end" to span?.end)
            markCorrected()
        }

        private fun markCorrected() {
            val found = span ?: return
            val now = latest.substring(found.start.coerceAtMost(latest.length), found.end.coerceAtMost(latest.length))
            if (now == inserted) return
            if (File(takesDir, "$takeId.pcm").exists()) File(takesDir, "$takeId.corrected").createNewFile()
        }
    }

    /** Red-orange for [FAILED_HOLD_MS], then resting, or busy when this take is still inserting. */
    private fun flashFailed() {
        failedUntilMs = SystemClock.uptimeMillis() + FAILED_HOLD_MS
        handler.removeCallbacks(endFlash)
        handler.postDelayed(endFlash, FAILED_HOLD_MS)
        applyButtonMode()
    }

    private fun applyButtonMode() {
        val mode = when {
            SystemClock.uptimeMillis() < failedUntilMs -> ButtonMode.FAILED
            run?.take?.state == TakeState.OPEN -> ButtonMode.RECORDING
            run != null -> ButtonMode.BUSY
            else -> ButtonMode.IDLE
        }
        overlay.setMode(mode)
    }

    private fun pruneTakeAudio(recording: String) {
        val files = takesDir.listFiles()?.filter { it.isFile } ?: return
        val drop = TakeAudio.toDelete(files.map { TakeAudioFile(it.name, it.length(), it.lastModified()) }, inProgress = recording).toSet()
        for (file in files) if (file.name in drop) file.delete()
    }

    companion object {
        private const val UNKNOWN_PACKAGE = "unknown"
        private const val IDLE_POLL_MS = 25L
        private const val TRIGGER_STOP = "stop"
        private const val TRIGGER_CAP = "cap"
        private const val TRIGGER_KEYBOARD = "keyboard"
        private const val TRIGGER_PACKAGE = "package"
        private const val TRIGGER_SILENCE = "silence"
        private const val TRIGGER_MIC = "mic"
        private const val BUBBLE_MS = 100L
        private const val WARM_MS = 30_000L
        private const val HIDDEN_GRACE_MS = 700L
        private const val IME_LINE_DELAY_MS = 500L
        private const val FAILED_HOLD_MS = 1_500L
    }
}
