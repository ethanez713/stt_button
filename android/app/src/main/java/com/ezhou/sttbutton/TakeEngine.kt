package com.ezhou.sttbutton

class LiveResult(
    val raw: String,
    /** Before the casual rule, which the caller applies where the text is inserted. */
    val cleaned: String,
    val chunks: Int,
    val cleanMode: String,
    val asrMs: Long,
    val cleanMs: Long,
    val asrMsAfterStop: Long,
    val cleanMsAfterStop: Long,
    val inTokens: Int,
    val outTokens: Int,
    val idleCleans: Int,
    val idleHits: Int,
    val cue: String?,
    val audioMs: Long,
    val contextDecodes: Int = 0,
    val contextFallbacks: Int = 0,
    val drifts: Int = 0,
    val corrections: Int = 0,
    val correctionsKept: Int = 0,
    /** Cleans `Pipeline.untrustedClean` rejected for anything but drift, each inserted as heard instead. */
    val rejects: Int = 0,
    /** The stop's whole-take decode: applied, same, edited, detached, or abandoned. Null when the take did not settle. */
    val settle: String? = null,
    val settleMs: Long = 0,
    val settleWindows: Int = 0,
)

/** One line of the job log. */
class JobRecord(
    val kind: String,
    val moment: String,
    val ms: Long,
    val input: String?,
    val output: String?,
    val outcome: String,
    val inTokens: Int? = null,
    val outTokens: Int? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val context: Boolean? = null,
    val scope: String? = null,
    /** Why a clean's output was not trusted, as `Pipeline.untrustedClean` names it. */
    val reason: String? = null,
    val waitMs: Long? = null,
    val lagMs: Long? = null,
)

sealed interface JobResult
class Decoded(
    val text: String,
    val mark: String?,
    val usedContext: Boolean,
    val fellBack: Boolean,
    val ms: Long,
    val waitMs: Long?,
) : JobResult
class Cleaned(val text: String?, val rejected: String?, val inTokens: Int, val outTokens: Int, val ms: Long) : JobResult
class Loaded(val ms: Long) : JobResult

private class SettleWindow(
    val fromSample: Int,
    val toSample: Int,
    val text: String,
    val ms: Long,
    val usedContext: Boolean,
    val fellBack: Boolean,
)

private class Settled(val text: String, val windows: List<SettleWindow>, val generation: Int) : JobResult

/** Prior slices after one slice is joined on, the slice's own words, and an edit of the previous slice's end. */
private class SliceJoin(
    val taken: List<String>,
    val sliceText: String,
    val endChanged: Boolean,
    val endRemoved: Int,
    val endPrefix: String,
)

/** Work the engine wants done. [run] is the only part that waits on a model, and is safe on another thread. */
class Job internal constructor(
    internal val kind: Kind,
    internal val moment: Moment,
    internal val slice: IntArray? = null,
    internal val plan: CleanPlan? = null,
    private val body: () -> JobResult,
) {
    internal enum class Kind { DECODE, CLEAN, LOAD, SETTLE }

    fun run(): JobResult = body()
}

private class Kept(val text: String, val inTokens: Int, val outTokens: Int)

private class NeedsCleaner : RuntimeException(null, null, false, false)

/**
 * One take's pipeline writing into a [Document]. Every method but [onAudio] is called from one thread.
 * [next] returns the next job, [Job.run] does it anywhere, and [complete] applies its result; what to
 * clean and when is the [policy]'s. The engine reads the [slicer] and does not own it, so it can be built
 * after audio has begun: the recorder feeds the slicer and calls [onAudio].
 */
class TakeEngine(
    document: Document,
    private val slicer: Slicer,
    private val recognizer: Recognizer,
    private val cleaner: Cleaner,
    private val policy: CleanPolicy,
    private val cfg: PipelineConfig,
    private val jobs: (JobRecord) -> Unit = {},
    private val prepareCleaner: (trigger: String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The region's text, after every change the take made. */
    var onText: ((String) -> Unit)? = null

    /** The end of the audio, in seconds, that decoded slices cover. */
    var onCovered: ((Double) -> Unit)? = null

    /** Something other than a job result may have changed what [next] returns. */
    var onWoken: (() -> Unit)? = null

    /** The document went away and the rest of the take is in memory. */
    var onDetached: (() -> Unit)? = null

    val policyName: String get() = policy.name

    private var document = document
    private var region = TakeRegion(document.read()?.selectionEnd ?: 0)
    private var attachedFrom = 0
    private var lead = 0
    private var cleans = 0
    private var editsInside = 0
    private var revisionDropped = false
    private var previousInline = false
    private val texts = mutableListOf<String>()
    private val kept = HashMap<String, Kept>()
    private val chunks = mutableListOf<String>()
    private val outputs = mutableListOf<String>()
    private var decoded = 0
    private var sliceLanded = false
    private var running: Job? = null
    private var asrMs = 0L
    private var cleanMs = 0L
    private var stopAsrMs = 0L
    private var stopCleanMs = 0L
    private var inTokens = 0
    private var outTokens = 0
    private var idleCleans = 0
    private var idleHits = 0
    private var drifts = 0
    private var rejects = 0
    private var contextDecodes = 0
    private var contextFallbacks = 0
    private var settleGeneration = 0
    private var settleQueued = false
    private var settleResult: String? = null
    private var settleMs = 0L
    private var settleWindows = 0

    @Volatile private var abandonSpoken: Int? = null
    private var timedLoadRequested = false
    private val timing = Any()
    private val closedAtMs = HashMap<Int, Long>()
    private var seenClosed = 0
    private var recordingStartMs: Long? = null
    private var silenceFrom = 0

    /** End samples of tails [stop] closed. A later stop can run before an earlier tail's job does. */
    private val stopTailEnds = mutableSetOf<Int>()

    /** Preview words no decode has covered, written after the region. Not part of the region. */
    private var requestedPreview: String? = null
    private var previewDirty = false
    private var provisional: Span? = null
    private var provisionalAt: Long? = null
    private var detaching = false

    /** Rewrites of the preview into the field. Job lines do not record them. */
    var provisionalWrites = 0
        private set

    /** [nowMs] when the take first wrote into a field, or null when it has not. */
    var firstWriteMs: Long? = null
        private set

    var stopping = false
        private set

    init {
        hookEdits()
    }

    private fun hookEdits() {
        document.onEdit = ::applyEdit
    }

    private fun applyEdit(edit: Edit) {
        if (edit.at <= region.end && edit.at + edit.deleted >= region.start) editsInside++
        val span = provisional
        region.apply(edit)
        if (span != null) {
            if (requestedPreview != null && overlapsSpan(span, edit)) previewDirty = true
            provisional = span.apply(edit).takeIf { it.start < it.end }
        }
        onWoken?.invoke()
    }

    /** An edit strictly inside the span. One at either edge is outside it, as it is for [Span]. */
    private fun overlapsSpan(span: Span, edit: Edit): Boolean =
        if (edit.deleted == 0) edit.at > span.start && edit.at < span.end
        else edit.at < span.end && edit.at + edit.deleted > span.start

    /** Where the take's own words begin and end in the document, after any opener. */
    val textStart: Int get() = region.start + lead
    val textEnd: Int get() = region.end

    /** False while a write would land under the user's typing; the stop never waits. */
    fun writesHeld(): Boolean = !stopping && document.writesHeld()

    /**
     * Preview words the streaming model has that no decode has covered. Only a field take writes them, as one span
     * directly after the region, led as a slice would be. Rewritten at most every [PROVISIONAL_MS], and not while
     * [writesHeld] or stopped; a pending update waits for [flushProvisional].
     */
    fun setProvisional(text: String) {
        if (document !is FieldDocument) return
        requestedPreview = text
        previewDirty = true
        flushProvisional()
    }

    /** Writes a pending preview when the hold and the throttle allow it. The driver's tick calls this. */
    fun flushProvisional() {
        if (stopping || document !is FieldDocument) return
        val preview = requestedPreview ?: return
        if (!previewDirty) return
        if (writesHeld()) return
        val text = readText()
        if (stopping || document !is FieldDocument) return
        val span = provisional
        val from = (span?.start ?: region.end).coerceIn(0, text.length)
        val to = (span?.end ?: from).coerceIn(from, text.length)
        val written = leadProvisional(preview, text)
        if (text.substring(from, to) == written) {
            previewDirty = false
            if (written.isEmpty()) provisional = null
            return
        }
        val at = provisionalAt
        if (from < to && written.isNotEmpty() && at != null && nowMs() - at < PROVISIONAL_MS) return
        if (!replaceDocument(from, to, written)) {
            detach()
            return
        }
        provisionalAt = nowMs()
        provisionalWrites++
        provisional = if (written.isEmpty()) null else Span(from, from + written.length)
        previewDirty = false
    }

    /** A write into the field the engine did not make itself, such as the stop's final shaping of the text. */
    fun markFieldWrite() {
        if (firstWriteMs == null && document is FieldDocument) firstWriteMs = nowMs()
    }

    /**
     * The document is gone: what the take hears from here on collects in memory, as the text of a take with no field,
     * and the text it already committed stays where it is. A provisional span still in reach is removed.
     */
    fun detach() {
        if (detaching) return
        detaching = true
        dropProvisional()
        document = BufferDocument()
        hookEdits()
        region = TakeRegion()
        attachedFrom = texts.size
        lead = 0
        cleans = 0
        editsInside = 0
        revisionDropped = false
        previousInline = false
        kept.clear()
        onDetached?.invoke()
        onText?.invoke(regionText())
        detaching = false
    }

    private fun readText(): String = document.read()?.text ?: run {
        detach()
        document.read()!!.text
    }

    val raw: String get() = Pipeline.joinSlices(texts)

    /** Seconds since the last window at or above `stop_silence_dbfs`, or since the take opened or last resumed when there has been none. */
    fun silenceS(): Double = (slicer.sampleCount - maxOf(slicer.lastLoudEnd, silenceFrom, 0)).toDouble() / Slicer.SAMPLE_RATE

    /** Recorder thread, after each feed of the slicer. */
    fun onAudio() {
        noteTiming()
        if (!timedLoadRequested && slicer.sampleCount >= cfg.cleanerLoadAfterS * Slicer.SAMPLE_RATE) {
            timedLoadRequested = true
            prepareCleaner("time")
        }
        val spoken = abandonSpoken
        if (spoken != null && slicer.lastSpeechEnd != spoken) cleaner.cancel()
    }

    fun markStop() {
        stopAsrMs = asrMs
        stopCleanMs = cleanMs
    }

    /** No more audio will arrive until [resume]: what is open is recognized and cleaned. */
    fun stop() {
        if (stopping) return
        stopping = true
        region.close()
        val tail = slicer.consume() ?: run {
            if (slicer.closedSlices(decoded).isEmpty()) dropProvisional()
            return
        }
        val at = nowMs()
        synchronized(timing) { closedAtMs.putIfAbsent(tail[1], at) }
        stopTailEnds.add(tail[1])
    }

    /** The mic is open on this take again. A clean in flight is not written when it returns, and a settle in flight is discarded when it returns. */
    fun resume() {
        if (!stopping) return
        stopping = false
        region.reopen()
        region.abandonFlight()
        silenceFrom = slicer.sampleCount
        settleGeneration++
        settleQueued = false
        flushProvisional()
    }

    fun next(): Job? {
        check(running == null) { "a job is still running" }
        nextSlice()?.let { return start(decodeJob(it)) }
        if (stopping && !settleQueued && document is FieldDocument) {
            settleQueued = true
            return start(settleJob())
        }
        return planJob()?.let(::start)
    }

    fun complete(job: Job, result: JobResult) {
        check(running === job) { "not the running job" }
        running = null
        when (job.kind) {
            Job.Kind.DECODE -> completeDecode(job, result as Decoded)
            Job.Kind.CLEAN -> completeClean(job, result as Cleaned)
            Job.Kind.LOAD -> completeLoad(job, result as Loaded)
            Job.Kind.SETTLE -> completeSettle(result as Settled)
        }
    }

    /** [stop] and then [next], [Job.run] and [complete] inline until nothing is left. */
    fun finish(): LiveResult {
        stop()
        while (true) {
            val job = next() ?: break
            complete(job, job.run())
        }
        return result()
    }

    fun result(): LiveResult {
        val raw = raw
        val skipped = Pipeline.skipsCleaner(raw, cfg)
        val (corrections, correctionsKept) = if (skipped) 0 to 0 else Pipeline.unresolvedCorrections(chunks, outputs, cfg.selfCorrectionCues)
        return LiveResult(
            raw, regionText(), if (skipped) 0 else chunks.size, if (skipped) "raw" else "chunks", asrMs, cleanMs,
            asrMs - stopAsrMs, cleanMs - stopCleanMs, inTokens, outTokens, idleCleans, idleHits,
            Pipeline.matchedCue(raw, cfg.selfCorrectionCues), slicer.sampleCount / (Slicer.SAMPLE_RATE / 1000).toLong(),
            contextDecodes, contextFallbacks, drifts, corrections, correctionsKept, rejects,
            settleResult, settleMs, settleWindows,
        )
    }

    private fun start(job: Job): Job {
        running = job
        return job
    }

    private fun regionText(): String = readText().let { it.substring(region.start, region.end.coerceAtMost(it.length)) }

    private fun nextSlice(): IntArray? {
        val slice = slicer.closedSlices(decoded).firstOrNull() ?: return null
        decoded++
        return slice
    }

    private fun momentNow(): Moment? = when {
        stopping -> Moment.FINISH
        sliceLanded -> Moment.SLICE
        slicer.paused && slicer.start >= slicer.lastSpeechEnd -> Moment.PAUSE
        else -> null
    }

    private fun planJob(): Job? {
        var moment = momentNow() ?: return null
        if (writesHeld()) return null
        while (true) {
            val text = readText()
            val tailStart = region.cleanEnd
            val tail = text.substring(tailStart, region.end.coerceAtMost(text.length))
            val plan = try {
                policy.next(
                    PolicyView(
                        tail, tailStart, chunks.isNotEmpty() || region.cleanEnd > region.start + lead, kept.keys.toSet(), counter(),
                        RegionFacts(text.substring(region.start + lead, region.end.coerceAtMost(text.length)), region.start + lead, cleans, editsInside, revisionDropped),
                    ),
                    moment,
                )
            } catch (_: NeedsCleaner) {
                if (moment == Moment.FINISH) return loadJob(moment)
                prepareCleaner("text")
                return null
            }
            if (plan == null) {
                if (moment != Moment.SLICE) return null
                sliceLanded = false
                moment = momentNow() ?: return null
                continue
            }
            val heard = reviseFromRaw(plan)
            when {
                heard.local -> applyLocal(heard)
                !heard.speculative && heard.target in kept -> applyKept(heard)
                else -> return cleanJob(heard, moment)
            }
        }
    }

    private fun counter(): (String) -> Int = cleaner.tokenCounter ?: { throw NeedsCleaner() }

    /** A revision cleans the words that were heard. The field already holds each slice's own punctuation. */
    private fun reviseFromRaw(plan: CleanPlan): CleanPlan {
        if (!plan.region) return plan
        val heard = raw.trim()
        if (heard.isEmpty() || counter()(heard) > cfg.chunkMaxTokens) return plan
        return CleanPlan(plan.from, plan.to, heard, plan.abandonable, plan.speculative, plan.local, plan.spaceAfter, region = true)
    }

    private fun loadJob(moment: Moment) = start(Job(Job.Kind.LOAD, moment) {
        val started = nowMs()
        cleaner.countTokens("")
        Loaded(nowMs() - started)
    })

    private fun samples(from: Int, to: Int): FloatArray = slicer.copy(from, to).let { pcm -> FloatArray(pcm.size) { pcm[it] / 32768f } }

    /** The tail's own words, before they are joined onto the slice before them. */
    private fun fillerTail(slice: IntArray, own: String): Boolean {
        if (slice[1] !in stopTailEnds || texts.none { it.isNotEmpty() }) return false
        if ((slice[1] - slice[0]).toDouble() / Slicer.SAMPLE_RATE >= cfg.tailFillerMaxS) return false
        return Pipeline.onlyTailFillers(own, cfg.tailFillerWords)
    }

    private data class HeardSlice(val text: String, val mark: String?, val usedContext: Boolean, val fellBack: Boolean)

    private fun hear(read: (Int, Int) -> FloatArray, start: Int, end: Int, withContext: Boolean, settle: Boolean): HeardSlice {
        fun plain() = recognizer.recognize(RecognizeRequest(read(start, end), settle = settle)).text
        if (!withContext) return HeardSlice(Pipeline.stripHidden(plain()), null, false, false)
        val (audio, junctionS) = Pipeline.contextAudio(read, start, end, cfg.contextS)
        val heard = recognizer.recognize(RecognizeRequest(audio, junctionS, settle))
        if (heard.text.isEmpty()) return HeardSlice(Pipeline.stripHidden(plain()), null, true, true)
        return HeardSlice(Pipeline.stripHidden(heard.text), heard.mark.orEmpty(), true, false)
    }

    private fun decodeJob(slice: IntArray): Job {
        val withContext = texts.isNotEmpty()
        val moment = if (stopping) Moment.FINISH else Moment.SLICE
        return Job(Job.Kind.DECODE, moment, slice = slice) {
            val waited = waitMs(slice[1])
            val started = System.nanoTime()
            val heard = hear(::samples, slice[0], slice[1], withContext, settle = false)
            Decoded(heard.text, heard.mark, heard.usedContext, heard.fellBack, elapsedMs(started), waited)
        }
    }

    private fun joinSlice(taken: List<String>, heard: String, incoming: String, mark: String?): SliceJoin {
        val updated = taken.toMutableList()
        val previous = updated.lastOrNull().orEmpty()
        var sliceText = incoming
        if (mark != null && previous.isNotEmpty()) {
            val old = Pipeline.trailingMark(previous)
            val settled = Pipeline.settledMark(old, mark)
            updated[updated.lastIndex] = Pipeline.withoutTrailingMark(previous) + settled
            sliceText = Pipeline.dropOverlap(heard, sliceText)
            if (sliceText.isNotEmpty()) sliceText = Pipeline.continuing(sliceText, updated.last(), updated.last().length)
            val changed = old != settled
            return SliceJoin(updated, sliceText, changed, if (changed) old.length else 0, if (changed) settled else "")
        }
        if (previous.isNotEmpty() && sliceText.isNotEmpty()) {
            val continued = Pipeline.continueFragment(previous, sliceText, heard)
            sliceText = continued.second
            if (continued.first != previous && previous.startsWith(continued.first)) {
                updated[updated.lastIndex] = continued.first
                return SliceJoin(updated, sliceText, true, previous.removePrefix(continued.first).length, "")
            }
        }
        return SliceJoin(updated, sliceText, false, 0, "")
    }

    private fun settleJob(): Job {
        val generation = settleGeneration
        val pcm = slicer.copy(0, slicer.sampleCount)
        return Job(Job.Kind.SETTLE, Moment.FINISH) {
            val read = { from: Int, to: Int -> FloatArray(to - from) { pcm[from + it] / 32768f } }
            val cutter = Slicer.forSettle(cfg)
            val cuts = cutter.feed(pcm) + cutter.finish()
            var taken = emptyList<String>()
            val windows = mutableListOf<SettleWindow>()
            for (cut in cuts) {
                val started = System.nanoTime()
                val heard = hear(read, cut[0], cut[1], taken.isNotEmpty(), settle = true)
                val join = joinSlice(taken, Pipeline.joinSlices(taken), heard.text, heard.mark)
                taken = join.taken
                if (join.sliceText.isNotEmpty()) taken = taken + join.sliceText
                windows += SettleWindow(cut[0], cut[1], heard.text, elapsedMs(started), heard.usedContext, heard.fellBack)
            }
            Settled(Pipeline.joinSlices(taken), windows, generation)
        }
    }

    private fun completeSettle(settled: Settled) {
        for (window in settled.windows) {
            asrMs += window.ms
            if (window.usedContext) contextDecodes++
            if (window.fellBack) contextFallbacks++
        }
        val outcome = writeSettled(settled)
        val logged = if (outcome == "abandoned") "abandoned" else "applied"
        for (window in settled.windows) {
            jobs(JobRecord(
                "asr", "settle", window.ms, null, window.text, logged,
                fromMs = samplesToMs(window.fromSample), toMs = samplesToMs(window.toSample),
                context = window.usedContext,
            ))
        }
        settleResult = outcome
        settleMs = settled.windows.sumOf { it.ms }
        settleWindows = settled.windows.size
    }

    private fun writeSettled(settled: Settled): String {
        if (settled.generation != settleGeneration) return "abandoned"
        if (document !is FieldDocument) return "detached"
        if (editsInside > 0) return "edited"
        val text = readText()
        if (document !is FieldDocument) return "detached"
        val from = textStart
        val to = region.end
        val before = if (from in 0..text.length) text.substring(0, from) else ""
        val body = Pipeline.continuing(Pipeline.stripHidden(settled.text), before, from)
        val current = if (from in 0..to && to <= text.length) text.substring(from, to) else null
        if (body == current) return "same"
        if (!replaceDocument(from, to, body)) {
            detach()
            return "detached"
        }
        region.appended(body.length - (to - from))
        region.cleanedUntil(textStart)
        val head = texts.take(attachedFrom)
        texts.clear()
        texts.addAll(head)
        if (body.isNotEmpty()) texts += body
        onText?.invoke(regionText())
        return "applied"
    }

    private fun completeDecode(job: Job, decoded: Decoded) {
        val slice = job.slice!!
        if (fillerTail(slice, decoded.text)) {
            asrMs += decoded.ms
            if (decoded.usedContext) contextDecodes++
            if (decoded.fellBack) contextFallbacks++
            logAsr(job, slice, decoded, "filler", decoded.text, null)
            if (stopping) dropProvisional()
            onCovered?.invoke(slice[1].toDouble() / Slicer.SAMPLE_RATE)
            onText?.invoke(regionText())
            return
        }
        asrMs += decoded.ms
        if (decoded.usedContext) contextDecodes++
        if (decoded.fellBack) contextFallbacks++
        val text = readText()
        var from = region.end
        val heard = raw
        val owned = texts.drop(attachedFrom)
        val previous = owned.lastOrNull().orEmpty()
        val join = joinSlice(owned, heard, decoded.text, decoded.mark)
        if (texts.size > attachedFrom) texts.subList(attachedFrom, texts.size).clear()
        texts.addAll(join.taken)
        var sliceText = join.sliceText
        var prefix = ""
        var replacing = false
        if (join.endChanged) {
            val at = region.end - join.endRemoved
            val removed = previous.takeLast(join.endRemoved)
            if (decoded.mark != null && previous.isNotEmpty()) {
                if (at >= region.cleanEnd && text.startsWith(removed, at)) {
                    from = at
                    prefix = join.endPrefix
                    replacing = true
                }
            } else if (at >= region.cleanEnd && at <= text.length && text.startsWith(removed, at)) {
                from = at
                replacing = true
            }
        }
        val opener = when {
            sliceText.isEmpty() -> ""
            replacing || texts.drop(attachedFrom).any { it.isNotEmpty() } -> " "
            else -> Pipeline.takeOpener(text, region.start)
        }
        var first = texts.drop(attachedFrom).none { it.isNotEmpty() } && sliceText.isNotEmpty()
        if (first) sliceText = Pipeline.continuing(sliceText, text.substring(0, region.start) + opener, region.start + opener.length)
        var written = prefix + opener + sliceText
        if (sliceText.isNotEmpty()) texts += sliceText
        val through = provisional?.let { if (it.start > region.end) region.end else maxOf(region.end, it.end) } ?: region.end
        val wrote = written.isNotEmpty() || from != through || provisional?.start?.let { it > region.end } == true
        if (wrote) {
            if (!writeSlice(from, written)) {
                detach()
                attachedFrom = texts.size - 1
                first = false
                from = 0
                written = sliceText
                replaceDocument(0, 0, written)
                region.appended(written.length)
            }
        }
        if (first) {
            lead = opener.length
            region.skipLead(lead)
        }
        logAsr(job, slice, decoded, if (decoded.fellBack) "fallback" else "applied", decoded.text, lagMs(slice[1], wrote))
        sliceLanded = true
        if (!Pipeline.skipsCleaner(raw, cfg)) prepareCleaner("text")
        onCovered?.invoke(slice[1].toDouble() / Slicer.SAMPLE_RATE)
        onText?.invoke(regionText())
    }

    private fun logAsr(job: Job, slice: IntArray, decoded: Decoded, outcome: String, output: String?, lagMs: Long?) {
        jobs(JobRecord("asr", job.moment.name.lowercase(), decoded.ms, null, output, outcome,
            fromMs = samplesToMs(slice[0]), toMs = samplesToMs(slice[1]), context = decoded.usedContext,
            waitMs = decoded.waitMs, lagMs = lagMs))
    }

    private fun cleanJob(plan: CleanPlan, moment: Moment): Job {
        region.flightOver(plan.from, plan.to)
        val spoken = slicer.lastSpeechEnd
        abandonSpoken = if (plan.abandonable) spoken else null
        return Job(Job.Kind.CLEAN, moment, plan = plan) {
            val started = System.nanoTime()
            val result = if (plan.abandonable && slicer.lastSpeechEnd != spoken) null else cleaner.clean(CleanRequest(plan.target))
            val ms = elapsedMs(started)
            if (result == null) {
                Cleaned(null, null, 0, 0, ms)
            } else {
                val rejected = if (result.text.isBlank()) "empty" else Pipeline.untrustedClean(plan.target, result.text)
                val text = if (rejected != null) Spoken.convert(plan.target) else result.text
                Cleaned(Spoken.keepSpokenEnding(plan.target, text), rejected, cleaner.countTokens(plan.target), result.outTokens, ms)
            }
        }
    }

    private fun completeClean(job: Job, cleaned: Cleaned) {
        val plan = job.plan!!
        abandonSpoken = null
        cleanMs += cleaned.ms
        if (plan.speculative) idleCleans++
        val flight = region.flight
        val record = { outcome: String, output: String? ->
            jobs(JobRecord("clean", job.moment.name.lowercase(), cleaned.ms, plan.target, output, outcome,
                cleaned.inTokens.takeIf { cleaned.text != null }, cleaned.outTokens.takeIf { cleaned.text != null },
                scope = if (plan.region) "region" else "chunk", reason = cleaned.rejected))
        }
        if (cleaned.text != null) {
            inTokens += cleaned.inTokens
            outTokens += cleaned.outTokens
            if (cleaned.rejected == "drift") drifts++ else if (cleaned.rejected != null) rejects++
        }
        when {
            flight?.abandoned == true -> {
                val dirty = flight.dirty
                region.dropFlight()
                if (plan.region && dirty) revisionDropped = true
                record("abandoned", cleaned.text)
            }
            cleaned.text == null && (plan.abandonable || plan.speculative) -> {
                region.dropFlight()
                record("abandoned", null)
            }
            plan.speculative && cleaned.text != null -> {
                kept[plan.target] = Kept(cleaned.text, cleaned.inTokens, cleaned.outTokens)
                region.dropFlight()
                record("kept", cleaned.text)
            }
            flight == null || flight.dirty -> {
                region.dropFlight()
                if (plan.region) revisionDropped = true
                record("dropped", cleaned.text)
            }
            else -> {
                val text = cleaned.text ?: Spoken.convert(plan.target)
                apply(flight.from, flight.to, text, plan)
                record(if (cleaned.text == null || cleaned.rejected != null) "fallback" else "applied", text)
            }
        }
    }

    private fun applyKept(plan: CleanPlan) {
        region.flightOver(plan.from, plan.to)
        idleHits++
        apply(plan.from, plan.to, kept.getValue(plan.target).text, plan)
    }

    private fun applyLocal(plan: CleanPlan) {
        region.flightOver(plan.from, plan.to)
        val text = Spoken.convert(plan.target)
        replaceDocument(plan.from, plan.to, text)
        region.flightReplaced(text.length)
        onText?.invoke(regionText())
    }

    /** Writes [output] over the flight, set off from the cleaned text before it as `Pipeline.joinCleaned` sets chunks off. */
    private fun apply(from: Int, to: Int, output: String, plan: CleanPlan) {
        val text = readText()
        if (plan.region) {
            val opened = Pipeline.continuing(output, text, from)
            replaceDocument(from, to, opened)
            region.flightReplaced(opened.length)
            cleans = 1
            editsInside = 0
            onText?.invoke(regionText())
            return
        }
        val target = plan.target
        val cleanedBefore = text.substring(region.start + lead, region.cleanEnd.coerceAtLeast(region.start + lead))
        val unit = Pipeline.isCorrectionUnit(target, cfg.selfCorrectionCues)
        val separator = Pipeline.joinSeparator(cleanedBefore, output, previousInline, unit)
        previousInline = unit || plan.spaceAfter
        chunks += target
        outputs += output
        cleans++
        val written = separator + Pipeline.continuing(output, text.substring(0, from) + separator, from + separator.length)
        replaceDocument(from, to, written)
        region.flightReplaced(written.length)
        onText?.invoke(regionText())
    }

    private fun completeLoad(job: Job, loaded: Loaded) {
        jobs(JobRecord("load", job.moment.name.lowercase(), loaded.ms, null, null, "applied"))
    }

    /** The opener a slice would take: a space once the take has words, otherwise [Pipeline.takeOpener]. */
    private fun leadProvisional(preview: String, text: String): String {
        if (preview.isEmpty()) return ""
        val opener = if (texts.drop(attachedFrom).any { it.isNotEmpty() }) " " else Pipeline.takeOpener(text, region.start)
        return opener + preview
    }

    /**
     * Writes the slice over [from, region end) and the provisional span. One replacement when the span sits on
     * the region's end; text the user put between them, while the region was closed, stays.
     */
    private fun writeSlice(from: Int, written: String): Boolean {
        val span = provisional
        if (span != null && span.start > region.end) {
            val growth = written.length - (region.end - from)
            if (!replaceDocument(from, region.end, written)) return false
            region.appended(growth)
            dropProvisional()
            return true
        }
        val through = span?.end ?: region.end
        val growth = written.length - (region.end - from)
        if (!replaceDocument(from, maxOf(through, region.end), written)) return false
        clearProvisional()
        region.appended(growth)
        return true
    }

    private fun replaceDocument(from: Int, to: Int, text: String): Boolean {
        if (!document.replace(from, to, text)) return false
        if (document is FieldDocument && firstWriteMs == null) firstWriteMs = nowMs()
        shiftProvisional(from, to, text.length)
        return true
    }

    private fun shiftProvisional(from: Int, to: Int, inserted: Int) {
        val span = provisional ?: return
        if (to > span.start) return
        val delta = inserted - (to - from)
        if (delta == 0) return
        provisional = Span(span.start + delta, span.end + delta).takeIf { it.start < it.end }
    }

    private fun dropProvisional() {
        val span = provisional
        clearProvisional()
        if (span == null) return
        replaceDocument(span.start, span.end, "")
    }

    private fun clearProvisional() {
        provisional = null
        requestedPreview = null
        previewDirty = false
    }

    private fun noteTiming() {
        val samples = slicer.sampleCount
        val at = nowMs()
        val fresh = slicer.closedSlices(seenClosed)
        synchronized(timing) {
            if (recordingStartMs == null) recordingStartMs = at - samplesToMs(samples)
            for (slice in fresh) closedAtMs.putIfAbsent(slice[1], at)
            seenClosed += fresh.size
        }
    }

    private fun waitMs(sliceEnd: Int): Long? = synchronized(timing) {
        closedAtMs[sliceEnd]?.let { nowMs() - it }
    }

    private fun lagMs(sliceEnd: Int, wrote: Boolean): Long? {
        if (!wrote) return null
        val toMs = samplesToMs(sliceEnd)
        return synchronized(timing) { recordingStartMs?.let { nowMs() - (it + toMs) } }
    }

    private fun samplesToMs(samples: Int) = samples.toLong() / (Slicer.SAMPLE_RATE / 1000)

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    companion object {
        const val PROVISIONAL_MS = 250L
    }
}
