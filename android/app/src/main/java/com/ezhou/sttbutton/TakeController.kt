package com.ezhou.sttbutton

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Carries out takes for the accessibility service. Android's callbacks become [TakeEvent]s for a
 * [TakeSession], which decides; this class executes the [TakeEffect]s it returns and holds no decision of
 * its own. Everything here runs on the main thread except the recorder's, the models' and the worker's.
 */
class TakeController(
    private val service: DictationService,
    private val overlay: Overlay,
    private val bubble: BubbleOverlay,
) : KeyboardHost {
    private val tag = DictationService.TAG
    private val handler = Handler(Looper.getMainLooper())
    private val configBytes = service.assets.open("pipeline.json").use { it.readBytes() }
    private val cfg = PipelineConfig(org.json.JSONObject(String(configBytes)))
    private val configId by lazy {
        ConfigId.of(configBytes, ModelFiles.required.map { it to File(service.filesDir, it).length() })
    }
    private val log = EditLog(File(service.filesDir, "logs/edits.jsonl"))
    private val audio = service.getSystemService(AudioManager::class.java)
    private val media = MediaPause(AndroidFocusPort(audio))
    private val inserter = Inserter(service)
    private val takesDir = File(service.filesDir, "takes")

    /** The latest take's session: open, ending, or flashing its failure on the button. */
    private var session: TakeSession? = null
    private val runs = HashMap<TakeSession, Run>()
    private var fieldAtTap: AccessibilityNodeInfo? = null
    private val returnedBy = HashMap<String, String?>()
    private val bubbleQueued = AtomicBoolean(false)
    private var tracker: FieldTracker? = null

    /** Accessibility events this process has handled, so the log shows the traffic between takes. */
    private var eventsSeen = 0L
    private var parked: Models? = null
    private var shutDown = false
    private val releaseParked = Runnable { releaseParkedNow() }
    private var stopKey: Bounds? = null
    private var panelHeight = 0
    private var panelBusy = false

    private class Run(
        val take: Take, val models: Models, val engine: TakeEngine, val driver: TakeDriver, val recorder: Recorder,
        val cost: TakeCost, val sampler: CostSampler, val bubbleText: BubbleText, val preview: PreviewRecognizer,
        val level: AudioLevel, val executor: ExecutorService,
    ) {
        /** The field the take was started in. Text goes to the focused field, or to this one when nothing is. */
        var field: AccessibilityNodeInfo? = null
        var result: LiveResult? = null
        var placement: Placement? = null
        var stoppedAtMs = 0L
    }

    /** Where a finished take's text goes, as the field stood when the engine finished. */
    private class Placement(val field: AccessibilityNodeInfo?, val fieldText: String?, val body: String)

    private val open: TakeSession? get() = session?.takeIf { !it.ended }

    /** True while the take's own keyboard is up or coming, so a keyboard setting change is not the user leaving English. */
    val swapHoldsTake: Boolean get() = session?.holdsTake == true

    /** The open take's state for the trace log, NONE between takes. */
    val stateName: String get() = open?.take?.state?.name ?: "NONE"

    fun onTap() {
        val current = session?.takeIf { !it.finished }
        if (current != null) {
            dispatch(current, TakeEvent.Tap(micGranted()))
            return
        }
        val field = service.focusedField()
        val pkg = field?.packageName?.toString() ?: service.activePackage() ?: UNKNOWN_PACKAGE
        val fresh = TakeSession(Take(UUID.randomUUID().toString(), pkg, SystemClock.elapsedRealtime()), cfg.takeCapS * 1000L) {
            SystemClock.uptimeMillis()
        }
        session = fresh
        fieldAtTap = field
        dispatch(fresh, TakeEvent.Tap(micGranted()))
    }

    private fun micGranted(): Boolean {
        val granted = service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) Log.w(tag, "tap ignored: the microphone permission is not granted")
        return granted
    }

    /** Called with every placement: a take records only while its button is on screen. */
    fun onButtonShown(shown: Boolean) {
        val current = open
        if (current == null) {
            if (!shown) bubble.hide()
            return
        }
        dispatch(current, TakeEvent.ButtonShown(shown))
    }

    /** The keyboard setting changed to a language other than English. */
    fun onLanguageLeft() {
        open?.let { dispatch(it, TakeEvent.LanguageLeft) }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        eventsSeen++
        open?.let { current ->
            val focused = service.focusedField()?.packageName?.toString()
            if (focused != null && current.take.pkg != UNKNOWN_PACKAGE && focused != current.take.pkg) dispatch(current, TakeEvent.PackageChanged)
        }
        tracker?.onEvent(event)
    }

    override fun stopKeyTapped() {
        open?.let { dispatch(it, TakeEvent.Tap(micGranted())) }
    }

    override fun viewStarted() {
        val current = open
        if (current == null) service.swapper.returnKeyboard() else dispatch(current, TakeEvent.ViewStarted)
    }

    override fun viewFinished() {
        open?.let { dispatch(it, TakeEvent.ViewFinished) }
    }

    override fun inputStartedOnExcludedField() {
        open?.let { dispatch(it, TakeEvent.ExcludedField) }
    }

    override fun panel() = PanelState(stopKey, panelHeight, panelBusy, service.swapper.stranded)

    private fun dispatch(current: TakeSession, event: TakeEvent) {
        val run = runs[current]
        for (effect in current.on(event)) execute(current, run, effect)
    }

    private fun execute(current: TakeSession, run: Run?, effect: TakeEffect) {
        when (effect) {
            TakeEffect.StartTake -> startTake(current)
            TakeEffect.AskSwap -> askSwap(current)
            TakeEffect.StopRecorder -> run?.let(::stopRecorder)
            TakeEffect.StopEngine -> run?.let { stopEngine(current, it) }
            TakeEffect.CancelCleaner -> run?.models?.cleaner()?.cancel()
            is TakeEffect.ReturnKeyboard -> {
                val method = service.swapper.returnKeyboard()
                if (!effect.stranded) returnedBy[current.take.id] = method
            }
            TakeEffect.WriteImeLine -> writeImeLine(current.take.id, returnedBy.remove(current.take.id))
            TakeEffect.ShowBubble -> {
                bubble.show()
                scheduleBubble()
            }
            TakeEffect.HideBubble -> bubble.hide()
            is TakeEffect.PanelBusy -> {
                panelBusy = effect.busy
                ImeLink.current?.refreshPanel()
            }
            is TakeEffect.SetButton -> overlay.setMode(effect.mode)
            TakeEffect.Recheck -> service.recheck()
            is TakeEffect.Insert -> run?.let { insert(current, it, effect.trigger) }
            is TakeEffect.CopyUnplaced -> run?.let { copyUnplaced(current, it, effect.reason, effect.trigger) }
            is TakeEffect.LogDiscard -> run?.let { logDiscard(current, it, effect.reason, effect.trigger) }
            TakeEffect.Release -> run?.let { release(current, it) }
            TakeEffect.ParkModels -> run?.let(::settleAndPark)
            is TakeEffect.Schedule -> handler.postDelayed({ dispatch(current, effect.event) }, effect.delayMs)
        }
    }

    private fun startTake(current: TakeSession) {
        val take = current.take
        tracker?.end()
        val field = fieldAtTap
        val warm = parked
        parked = null
        handler.removeCallbacks(releaseParked)
        val (fieldRead, fieldOffset) = readField()
        val mediaActive = audio.isMusicActive
        val mediaFocus = if (media.pause()) "granted" else "failed"
        log.write("start", take.id, "pkg" to take.pkg, "models_warm" to (warm != null), "config_id" to configId,
            "field_read" to fieldRead, "field_offset" to fieldOffset,
            "proc_cpu_ms" to Process.getElapsedCpuTime(),
            "proc_age_ms" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
            "proc_events" to eventsSeen,
            "media_active" to mediaActive, "media_focus" to mediaFocus)
        try {
            service.startForegroundService(Intent(service, RecordingService::class.java))
        } catch (e: Exception) {
            Log.e(tag, "microphone foreground service refused", e)
        }
        val models = warm ?: Models.forFiles(service.filesDir, cfg)
        val bubbleText = BubbleText().apply { onChange = ::scheduleBubble }
        val slicer = Slicer.fromConfig(cfg)
        val engine = TakeEngine(
            BufferDocument(), slicer, models.recognizer, models.cleaner(), ChunkPolicy(cfg), cfg,
            jobs = { writeJob(take.id, it) }, prepareCleaner = models::prepareCleaner,
        ).also {
            it.onText = bubbleText::region
            it.onCovered = bubbleText::covered
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "take-worker") }
        val driver = TakeDriver(
            engine, worker = { executor.execute(it) },
            onDone = { result -> runs[current]?.let { onEngineDone(current, it, result) } },
            onError = { e ->
                Log.e(tag, "take failed", e)
                dispatch(current, TakeEvent.Error)
            },
            onTick = { runs[current]?.let { if (cfg.stopSilenceS?.let { limit -> engine.silenceS() >= limit } == true) dispatch(current, TakeEvent.Silence) } },
        )
        val preview = PreviewRecognizer(models::awaitPreview, bubbleText::preview)
        val pcm = File(takesDir, "${take.id}.pcm")
        val level = AudioLevel()
        val recorder = Recorder(pcm, onFailure = { e ->
            Log.e(tag, "recording failed", e)
            handler.post { dispatch(current, TakeEvent.MicFailed) }
        }) { samples, length ->
            level.add(samples, length)
            val slices = slicer.feed(samples, length)
            engine.onAudio()
            if (slices.isNotEmpty()) driver.wake()
            preview.offer(samples, length, slicer.paused)
        }
        val cost = TakeCost(AndroidCostProbe(service)).also { it.start() }
        val run = Run(take, models, engine, driver, recorder, cost, CostSampler(cost), bubbleText, preview, level, executor)
        run.field = field
        runs[current] = run
        try {
            recorder.start()
        } catch (e: Exception) {
            Log.e(tag, "recording failed to start", e)
            dispatch(current, TakeEvent.RecorderStartFailed)
            return
        }
        pruneTakeAudio(pcm.name)
        Log.i(tag, "take start pkg=${take.pkg}")
        driver.start()
        dispatch(current, TakeEvent.RecorderStarted)
    }

    /** How the focused field reads through the accessibility service's input connection, and the offset it reported. */
    private fun readField(): Pair<String, Int?> {
        val surrounding = try {
            service.inputMethod?.currentInputConnection?.getSurroundingText(FieldRead.WINDOW, FieldRead.WINDOW, 0)
        } catch (e: Exception) {
            null
        } ?: return FieldRead.NONE to null
        val text = surrounding.text?.toString().orEmpty()
        return FieldRead.classify(surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd, text.length) to surrounding.offset
    }

    private fun askSwap(current: TakeSession) {
        service.swapper.clearStranded()
        panelBusy = false
        panelHeight = service.rememberedImeHeight() ?: 0
        stopKey = StopKeyPlacement.inPanel(overlay.lastShown, overlay.screen().bottom, panelHeightPx())
        dispatch(current, TakeEvent.SwapAsked(service.swapper.begin(SystemClock.uptimeMillis())))
    }

    private fun panelHeightPx(): Int =
        panelHeight.takeIf { it > 0 } ?: (TakePanel.DEFAULT_HEIGHT_DP * service.resources.displayMetrics.density).toInt()

    private fun stopRecorder(run: Run) {
        media.resume()
        try {
            run.recorder.stop()
        } catch (e: Exception) {
            Log.w(tag, "recorder did not stop cleanly", e)
        }
    }

    private fun stopEngine(current: TakeSession, run: Run) {
        Log.i(tag, "take stop trigger=${current.stopTrigger}")
        run.stoppedAtMs = SystemClock.elapsedRealtime()
        run.driver.stop()
    }

    private fun onEngineDone(current: TakeSession, run: Run, result: LiveResult) {
        run.result = result
        val take = run.take
        val field = targetField(run)
        val otherPackage = field != null && take.pkg != UNKNOWN_PACKAGE && field.packageName?.toString() != take.pkg
        val fieldText = if (field != null && !otherPackage) inserter.textOf(field) else null
        val signedOff = Pipeline.withoutSignOff(result.cleaned)
        val cleaned = if (fieldText != null && fieldText.isEmpty()) Pipeline.casual(signedOff, cfg) else signedOff
        val body = if (field == null) cleaned else Pipeline.forField(cleaned, FieldPolicy.multiLine(field.isMultiLine, field.inputType))
        run.placement = Placement(field, fieldText, body)
        dispatch(current, TakeEvent.EngineFinished(when {
            body.isBlank() -> Delivery.BLANK
            field == null || fieldText == null -> if (otherPackage) Delivery.OTHER_PACKAGE else Delivery.NO_FIELD
            else -> Delivery.INSERT
        }))
    }

    private fun insert(current: TakeSession, run: Run, trigger: String) {
        val take = run.take
        val result = checkNotNull(run.result)
        val placement = checkNotNull(run.placement)
        val field = checkNotNull(placement.field)
        val fieldText = checkNotNull(placement.fieldText)
        val caret = inserter.readCaret(field)
        val caretBefore = if (caret.known) caret.end else field.textSelectionEnd
        val body = Pipeline.continuing(placement.body, fieldText, caretBefore)
        val written = Pipeline.takeOpener(fieldText, caretBefore) + body
        val outcome = inserter.insert(field, written)
        if (outcome.copied && outcome.readback == "unchanged") {
            Toast.makeText(service, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
        }
        run.sampler.stop()
        val caretAfter = inserter.readCaret(field)
        log.write("insert", take.id,
            "pkg" to take.pkg, "raw" to result.raw, "cleaned" to body, "text_before" to outcome.textBefore,
            "insert_path" to outcome.path, "landed" to outcome.landed,
            "readback" to outcome.readback, "landed_count" to outcome.landedCount,
            "attempts" to outcome.attempts, "copied" to outcome.copied,
            "sel_before" to caretBefore, "sel_after" to caretAfter.end,
            "asr_ms" to result.asrMs, "clean_ms" to result.cleanMs, "asr_backend" to "cpu", "clean_backend" to "cpu",
            "audio_ms" to result.audioMs, "zero_ms" to run.level.zeroMs, "peak_dbfs" to run.level.peakDbfs,
            "in_tokens" to result.inTokens, "out_tokens" to result.outTokens,
            "clean_mode" to result.cleanMode, "clean_policy" to run.engine.policyName, "chunks" to result.chunks,
            "idle_cleans" to result.idleCleans, "idle_hits" to result.idleHits, "cue" to result.cue,
            "context_decodes" to result.contextDecodes, "context_fallbacks" to result.contextFallbacks, "drifts" to result.drifts, "rejects" to result.rejects,
            "corrections" to result.corrections, "corrections_kept" to result.correctionsKept,
            "trigger" to trigger, *swapFields(current), "a11y_conn" to service.a11yConnection(),
            "asr_ms_after_stop" to result.asrMsAfterStop, "clean_ms_after_stop" to result.cleanMsAfterStop,
            "stop_to_insert_ms" to SystemClock.elapsedRealtime() - run.stoppedAtMs,
            *run.cost.lap().toTypedArray())
        track(take, field, if (outcome.readback == "landed") written else null, caretAfter.end.takeIf { caretAfter.known })
    }

    /**
     * The field the take was for is gone or belongs to another app, so the text has nowhere to go:
     * it is put on the clipboard and the user is told, instead of losing what they dictated.
     */
    private fun copyUnplaced(current: TakeSession, run: Run, reason: DiscardReason, trigger: String) {
        val result = checkNotNull(run.result)
        val clip = checkNotNull(run.placement).body.trim()
        val copied = try {
            service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", clip))
            true
        } catch (e: Exception) {
            Log.e(tag, "could not copy the dictation to the clipboard", e)
            false
        }
        if (copied) Toast.makeText(service, R.string.copied_to_clipboard, Toast.LENGTH_LONG).show()
        log.write("clipboard", run.take.id,
            "pkg" to run.take.pkg, "reason" to reason.logName, "trigger" to trigger, "copied" to copied,
            "raw" to result.raw, "text" to clip, "clean_mode" to result.cleanMode, "clean_policy" to run.engine.policyName,
            "audio_ms" to result.audioMs, "zero_ms" to run.level.zeroMs, "peak_dbfs" to run.level.peakDbfs,
            *swapFields(current), *run.cost.lap().toTypedArray())
    }

    private fun logDiscard(current: TakeSession, run: Run, reason: DiscardReason, trigger: String?) {
        log.write(
            "discard", run.take.id, "reason" to reason.logName, "trigger" to trigger, "clean_policy" to run.engine.policyName,
            "audio_ms" to run.level.audioMs, "zero_ms" to run.level.zeroMs, "peak_dbfs" to run.level.peakDbfs,
            *swapFields(current), *run.cost.lap().toTypedArray(),
        )
    }

    private fun writeJob(takeId: String, record: JobRecord) {
        log.write("job", takeId, "kind" to record.kind, "moment" to record.moment, "ms" to record.ms,
            "in" to record.input, "out" to record.output, "outcome" to record.outcome,
            "in_tokens" to record.inTokens, "out_tokens" to record.outTokens,
            "from_ms" to record.fromMs, "to_ms" to record.toMs, "context" to record.context, "scope" to record.scope,
            "reason" to record.reason)
    }

    private fun swapFields(current: TakeSession): Array<Pair<String, Any?>> =
        arrayOf("ime_swap" to current.swap.logName, "ime_swap_ms" to current.swap.swapMs)

    private fun writeImeLine(takeId: String, method: String?) {
        val (returnMs, subtypeOk) = service.swapper.returnReport()
        log.write("ime", takeId, "ime_return" to (method ?: "failed"), "ime_return_ms" to returnMs,
            "ime_subtype_ok" to subtypeOk, "ime_height_delta_px" to service.imeHeightDelta())
    }

    private fun track(take: Take, field: AccessibilityNodeInfo, landed: String?, caretAfter: Int?) {
        val existing = tracker
        if (existing != null && existing.takeId == take.id) existing.refresh(inserter.textOf(field))
        else tracker = FieldTracker(take.id, field, inserter.textOf(field), landed, caretAfter)
    }

    private fun release(current: TakeSession, run: Run) {
        Log.i(tag, "take end state=${run.take.state} reason=${run.take.discardReason?.logName}")
        run.sampler.stop()
        bubble.hide()
        runs.remove(current)
        media.resume()
        panelBusy = false
        ImeLink.current?.refreshPanel()
        service.stopService(Intent(service, RecordingService::class.java))
    }

    /** The preview and the models are let go once no job is using them. */
    private fun settleAndPark(run: Run) {
        run.executor.shutdown()
        run.driver.abandon {
            thread(name = "take-end") {
                run.preview.stop()
                handler.post { park(run.models) }
            }
        }
    }

    private fun targetField(run: Run): AccessibilityNodeInfo? = service.focusedField() ?: run.field?.takeIf { it.refresh() }

    /** Redraws the bubble from the engine's and the preview thread's latest text, at most every [BUBBLE_MS]. */
    private fun scheduleBubble() {
        if (!bubbleQueued.compareAndSet(false, true)) return
        handler.postDelayed({
            bubbleQueued.set(false)
            open?.let { runs[it] }?.let { bubble.update(it.bubbleText.text()) }
        }, BUBBLE_MS)
    }

    fun onTrimMemory(level: Int) {
        open?.let { runs[it] }?.cost?.noteTrim(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseParkedNow()
    }

    /** Keeps a finished take's models for [WARM_MS] so a take that follows starts without loading them. */
    private fun park(models: Models) {
        if (shutDown || open != null || parked != null || !models.reusable) {
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
        open?.let { dispatch(it, TakeEvent.Error) }
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

    private fun pruneTakeAudio(recording: String) {
        val files = takesDir.listFiles()?.filter { it.isFile } ?: return
        val drop = TakeAudio.toDelete(files.map { TakeAudioFile(it.name, it.length(), it.lastModified()) }, inProgress = recording).toSet()
        for (file in files) if (file.name in drop) file.delete()
    }

    companion object {
        private const val UNKNOWN_PACKAGE = "unknown"
        private const val BUBBLE_MS = 100L
        private const val WARM_MS = 30_000L
    }
}
