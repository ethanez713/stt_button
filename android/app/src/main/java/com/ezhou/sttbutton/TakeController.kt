package com.ezhou.sttbutton

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    private val capRunnable = Runnable { run?.let(::stop) }

    private class Run(
        val take: Take, val pcm: File, val models: Models, val live: LiveTake, val recorder: Recorder,
        val cost: TakeCost, val sampler: CostSampler, val bubbleText: BubbleText, val preview: PreviewRecognizer,
    ) {
        @Volatile var stopRequested = false
        @Volatile var aborted = false
        @Volatile var stopTrigger = TRIGGER_STOP
        var stoppedAtMs = 0L
    }

    /** A take that exited with the process still owns a PCM file nobody will read. */
    fun deleteLeftovers() {
        takesDir.listFiles()?.forEach { it.delete() }
    }

    val hasOpenTake: Boolean get() = run != null

    fun onTap() {
        val current = run
        when {
            current == null -> start()
            current.take.state == TakeState.OPEN -> stop(current)
            else -> Unit
        }
    }

    /** The keyboard has been gone long enough that the user is no longer dictating into it. */
    fun onKeyboardGone() {
        run?.takeIf { it.take.state == TakeState.OPEN }?.let { stop(it, TRIGGER_KEYBOARD) }
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
        log.write("start", take.id, "pkg" to pkg)
        try {
            service.startForegroundService(Intent(service, RecordingService::class.java))
        } catch (e: Exception) {
            Log.e(tag, "microphone foreground service refused", e)
        }
        val models = Models.forFiles(service.filesDir, cfg)
        val bubbleText = BubbleText().apply { onChange = ::scheduleBubble }
        val live = LiveTake(models.recognizer, models::cleaner, cfg, bubbleText, models::prepareCleaner)
        val preview = PreviewRecognizer(File(service.filesDir, ModelFiles.PREVIEW), models::awaitRecognizerLoaded, bubbleText::preview)
        val pcm = File(takesDir, "${take.id}.pcm")
        val recorder = Recorder(pcm) { samples, length ->
            live.addSamples(samples, length)
            preview.offer(samples, length)
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
        run = current
        bubble.show()
        thread(name = "take-worker") { work(current) }
        handler.postDelayed(capRunnable, cfg.takeCapS * 1000L)
        overlay.setMode(ButtonMode.RECORDING)
    }

    private fun stop(current: Run, trigger: String = TRIGGER_STOP) {
        if (!current.take.requestStop()) return
        current.stopTrigger = trigger
        handler.removeCallbacks(capRunnable)
        overlay.setMode(ButtonMode.BUSY)
        current.stoppedAtMs = SystemClock.elapsedRealtime()
        current.recorder.stop()
        current.stopRequested = true
    }

    fun discard(reason: DiscardReason) {
        val current = run ?: return
        if (!current.take.discard(reason)) return
        log.write("discard", current.take.id, "reason" to reason.logName)
        current.aborted = true
        current.stopRequested = true
        handler.removeCallbacks(capRunnable)
        current.recorder.stop()
        current.models.cleaner().cancel()
        release(current)
    }

    private fun work(current: Run) {
        try {
            while (!current.stopRequested) {
                if (current.live.step()) continue
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
            current.models.close()
            current.pcm.delete()
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
            if (take.finishWithoutText()) release(current) else discard(DiscardReason.EMPTY)
            return
        }
        val field = service.focusedField()
        val reason = when {
            field == null -> DiscardReason.NO_FIELD
            take.pkg != UNKNOWN_PACKAGE && field.packageName?.toString() != take.pkg -> DiscardReason.PACKAGE
            else -> null
        }
        if (reason != null || field == null) {
            discard(reason ?: DiscardReason.NO_FIELD)
            return
        }
        val segment = take.segmentsInserted
        val outcome = inserter.insert(field, text)
        if (last) {
            take.markInserted()
            current.sampler.stop()
        } else {
            take.markSegmentInserted()
        }
        log.write("insert", take.id,
            "pkg" to take.pkg, "raw" to result.raw, "cleaned" to result.cleaned, "text_before" to outcome.textBefore,
            "insert_path" to outcome.path, "landed" to outcome.landed,
            "asr_ms" to result.asrMs, "clean_ms" to result.cleanMs, "asr_backend" to "cpu", "clean_backend" to "cpu",
            "audio_ms" to result.audioMs,
            "in_tokens" to result.inTokens, "out_tokens" to result.outTokens,
            "clean_mode" to result.cleanMode, "chunks" to result.chunks,
            "idle_cleans" to result.idleCleans, "idle_hits" to result.idleHits, "cue" to result.cue,
            "context_decodes" to result.contextDecodes, "context_fallbacks" to result.contextFallbacks,
            "segment" to segment, "trigger" to trigger,
            "asr_ms_after_stop" to result.asrMsAfterStop, "clean_ms_after_stop" to result.cleanMsAfterStop,
            "stop_to_insert_ms" to SystemClock.elapsedRealtime() - triggerAtMs,
            *current.cost.lap().toTypedArray())
        track(take, field)
        if (last) release(current)
    }

    private fun track(take: Take, field: AccessibilityNodeInfo) {
        val existing = tracker
        if (existing != null && existing.takeId == take.id) existing.refresh(inserter.textOf(field))
        else tracker = FieldTracker(take.id, field, inserter.textOf(field))
    }

    private fun release(current: Run) {
        current.sampler.stop()
        bubble.hide()
        if (run === current) run = null
        overlay.setMode(ButtonMode.IDLE)
        service.stopService(Intent(service, RecordingService::class.java))
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        run?.let { current ->
            val focused = service.focusedField()?.packageName?.toString()
            if (focused != null && current.take.pkg != UNKNOWN_PACKAGE && focused != current.take.pkg) discard(DiscardReason.PACKAGE)
        }
        tracker?.onEvent(event)
    }

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
    }

    fun logProcessExits() = ExitReasons.record(service, log, File(service.filesDir, "logs/edits.jsonl"))

    fun shutdown() {
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
        private const val BUBBLE_MS = 100L
    }
}
