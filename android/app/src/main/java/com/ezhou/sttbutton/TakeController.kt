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
 * the text is inserted once at the stop. Everything here is called on the main thread except the
 * worker's own loop, which hands its result back through the handler.
 */
class TakeController(private val service: DictationService, private val overlay: Overlay) {
    private val tag = DictationService.TAG
    private val handler = Handler(Looper.getMainLooper())
    private val cfg = PipelineConfig(org.json.JSONObject(service.assets.open("pipeline.json").bufferedReader().readText()))
    private val log = EditLog(File(service.filesDir, "logs/edits.jsonl"))
    private val inserter = Inserter(service)
    private val takesDir = File(service.filesDir, "takes")
    private var run: Run? = null
    private var tracker: FieldTracker? = null
    private val capRunnable = Runnable { run?.let(::stop) }

    private class Run(
        val take: Take, val pcm: File, val models: Models, val live: LiveTake, val recorder: Recorder,
        val cost: TakeCost, val sampler: CostSampler,
    ) {
        @Volatile var stopRequested = false
        @Volatile var aborted = false
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
        val live = LiveTake(models.recognizer, models::cleaner, cfg, prepareCleaner = models::prepareCleaner)
        val pcm = File(takesDir, "${take.id}.pcm")
        val recorder = Recorder(pcm) { samples, length -> live.addSamples(samples, length) }
        val cost = TakeCost(AndroidCostProbe(service)).also { it.start() }
        val current = Run(take, pcm, models, live, recorder, cost, CostSampler(cost))
        try {
            recorder.start()
        } catch (e: Exception) {
            Log.e(tag, "recording failed to start", e)
            run = current
            discard(DiscardReason.ERROR)
            return
        }
        run = current
        thread(name = "take-worker") { work(current) }
        handler.postDelayed(capRunnable, cfg.takeCapS * 1000L)
        overlay.setMode(ButtonMode.RECORDING)
    }

    private fun stop(current: Run) {
        if (!current.take.requestStop()) return
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
                if (!current.live.step()) Thread.sleep(IDLE_POLL_MS)
            }
            if (!current.aborted) {
                current.live.markStop()
                val result = current.live.finish()
                handler.post { insert(current, result) }
            }
        } catch (e: Throwable) {
            Log.e(tag, "take failed", e)
            handler.post { if (run === current) discard(DiscardReason.ERROR) }
        } finally {
            current.models.close()
            current.pcm.delete()
        }
    }

    private fun insert(current: Run, result: LiveResult) {
        val take = current.take
        if (take.state != TakeState.STOPPING) return
        val field = service.focusedField()
        val reason = when {
            result.cleaned.isBlank() -> DiscardReason.EMPTY
            field == null -> DiscardReason.NO_FIELD
            take.pkg != UNKNOWN_PACKAGE && field.packageName?.toString() != take.pkg -> DiscardReason.PACKAGE
            else -> null
        }
        if (reason != null || field == null) {
            discard(reason ?: DiscardReason.NO_FIELD)
            return
        }
        val outcome = inserter.insert(field, result.cleaned)
        take.markInserted()
        current.sampler.stop()
        log.write("insert", take.id,
            "pkg" to take.pkg, "raw" to result.raw, "cleaned" to result.cleaned, "text_before" to outcome.textBefore,
            "insert_path" to outcome.path, "landed" to outcome.landed,
            "asr_ms" to result.asrMs, "clean_ms" to result.cleanMs, "asr_backend" to "cpu", "clean_backend" to "cpu",
            "audio_ms" to current.live.slicer.sampleCount / (Slicer.SAMPLE_RATE / 1000),
            "in_tokens" to result.inTokens, "out_tokens" to result.outTokens,
            "clean_mode" to result.cleanMode, "chunks" to result.chunks,
            "idle_cleans" to result.idleCleans, "idle_hits" to result.idleHits, "cue" to result.cue,
            "asr_ms_after_stop" to result.asrMsAfterStop, "clean_ms_after_stop" to result.cleanMsAfterStop,
            "stop_to_insert_ms" to SystemClock.elapsedRealtime() - current.stoppedAtMs,
            *current.cost.lap().toTypedArray())
        tracker = FieldTracker(take.id, field, inserter.textOf(field))
        release(current)
    }

    private fun release(current: Run) {
        current.sampler.stop()
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
    private inner class FieldTracker(private val takeId: String, private val field: AccessibilityNodeInfo, initial: String) {
        private var latest = initial
        private val pkg = field.packageName?.toString()

        init {
            service.watchTextChanges(true)
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
    }
}
