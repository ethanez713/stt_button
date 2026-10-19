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
)

/** One model call, as the job log records it. */
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
)

sealed interface JobResult
class Decoded(val text: String, val mark: String?, val usedContext: Boolean, val fellBack: Boolean, val ms: Long) : JobResult
class Cleaned(val text: String?, val rejected: String?, val inTokens: Int, val outTokens: Int, val ms: Long) : JobResult
object Loaded : JobResult

/** Work the engine wants done. [run] is the only part that waits on a model, and is safe on another thread. */
class Job internal constructor(
    internal val kind: Kind,
    internal val moment: Moment,
    internal val slice: IntArray? = null,
    internal val plan: CleanPlan? = null,
    private val body: () -> JobResult,
) {
    internal enum class Kind { DECODE, CLEAN, LOAD }

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
    private var finalSlice: IntArray? = null
    private var finalTaken = false
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

    @Volatile private var abandonSpoken: Int? = null
    private var timedLoadRequested = false

    var stopping = false
        private set

    init {
        hookEdits()
    }

    private fun hookEdits() {
        document.onEdit = { edit ->
            if (edit.at <= region.end && edit.at + edit.deleted >= region.start) editsInside++
            region.apply(edit)
            onWoken?.invoke()
        }
    }

    /** Where the take's own words begin and end in the document, after any opener. */
    val textStart: Int get() = region.start + lead
    val textEnd: Int get() = region.end

    /** False while a write would land under the user's typing; the stop never waits. */
    fun writesHeld(): Boolean = !stopping && document.writesHeld()

    /**
     * The document is gone: what the take hears from here on collects in memory, as the text of a take with no field,
     * and what it already wrote stays where it is.
     */
    fun detach() {
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
    }

    private fun readText(): String = document.read()?.text ?: run {
        detach()
        document.read()!!.text
    }

    val raw: String get() = Pipeline.joinSlices(texts)

    /** Seconds since the last window at or above `stop_silence_dbfs`, or since the take opened when there has been none. */
    fun silenceS(): Double = (slicer.sampleCount - maxOf(slicer.lastLoudEnd, 0)).toDouble() / Slicer.SAMPLE_RATE

    /** Recorder thread, after each feed of the slicer. */
    fun onAudio() {
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

    /** No more audio will arrive: what is left is recognized and cleaned for good. */
    fun stop() {
        if (stopping) return
        stopping = true
        finalSlice = slicer.finish().firstOrNull()
    }

    fun next(): Job? {
        check(running == null) { "a job is still running" }
        nextSlice()?.let { return start(decodeJob(it)) }
        return planJob()?.let(::start)
    }

    fun complete(job: Job, result: JobResult) {
        check(running === job) { "not the running job" }
        running = null
        when (job.kind) {
            Job.Kind.DECODE -> completeDecode(job, result as Decoded)
            Job.Kind.CLEAN -> completeClean(job, result as Cleaned)
            Job.Kind.LOAD -> Unit
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
        )
    }

    private fun start(job: Job): Job {
        running = job
        return job
    }

    private fun regionText(): String = readText().let { it.substring(region.start, region.end.coerceAtMost(it.length)) }

    private fun nextSlice(): IntArray? {
        slicer.closedSlices(decoded).firstOrNull()?.let {
            decoded++
            return it
        }
        if (stopping && !finalTaken) {
            finalTaken = true
            return finalSlice
        }
        return null
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
            } catch (e: NeedsCleaner) {
                return loadJob(moment)
            }
            if (plan == null) {
                if (moment != Moment.SLICE) return null
                sliceLanded = false
                moment = momentNow() ?: return null
                continue
            }
            when {
                plan.local -> applyLocal(plan)
                !plan.speculative && plan.target in kept -> applyKept(plan)
                else -> return cleanJob(plan, moment)
            }
        }
    }

    private fun counter(): (String) -> Int = cleaner.tokenCounter ?: { throw NeedsCleaner() }

    private fun loadJob(moment: Moment) = start(Job(Job.Kind.LOAD, moment) {
        cleaner.countTokens("")
        Loaded
    })

    private fun samples(from: Int, to: Int): FloatArray = slicer.copy(from, to).let { pcm -> FloatArray(pcm.size) { pcm[it] / 32768f } }

    private fun decodeJob(slice: IntArray): Job {
        val before = slicer.speechBefore(slice[0])
        val after = slicer.speechAfter(slice[0])
        val withContext = texts.isNotEmpty() && before != null && after != null && after < slice[1]
        val moment = if (stopping) Moment.FINISH else Moment.SLICE
        return Job(Job.Kind.DECODE, moment, slice = slice) {
            val started = System.nanoTime()
            fun plain() = recognizer.recognize(RecognizeRequest(samples(slice[0], slice[1]))).text
            var text: String
            var mark: String? = null
            var fellBack = false
            if (!withContext) {
                text = plain()
            } else {
                val (audio, junctionS) = Pipeline.contextAudio(::samples, before!!, after!!, slice[1], cfg.contextS, cfg.junctionSqueezeS)
                val heard = recognizer.recognize(RecognizeRequest(audio, junctionS))
                if (heard.text.isEmpty()) {
                    fellBack = true
                    text = plain()
                } else {
                    text = heard.text
                    mark = heard.mark.orEmpty()
                }
            }
            Decoded(Pipeline.stripHidden(text), mark, withContext, fellBack, elapsedMs(started))
        }
    }

    private fun completeDecode(job: Job, decoded: Decoded) {
        val slice = job.slice!!
        asrMs += decoded.ms
        if (decoded.usedContext) contextDecodes++
        if (decoded.fellBack) contextFallbacks++
        val text = readText()
        var from = region.end
        val opener = if (texts.drop(attachedFrom).any { it.isNotEmpty() }) " " else Pipeline.takeOpener(text, region.start)
        var written = if (decoded.text.isEmpty()) "" else opener + decoded.text
        val previous = texts.drop(attachedFrom).lastOrNull().orEmpty()
        if (decoded.mark != null && previous.isNotEmpty()) {
            val old = Pipeline.trailingMark(previous)
            val settled = Pipeline.settledMark(old, decoded.mark)
            texts[texts.lastIndex] = Pipeline.withoutTrailingMark(previous) + settled
            val markStart = region.end - old.length
            if (old != settled && markStart >= region.cleanEnd && text.startsWith(old, markStart)) {
                from = markStart
                written = settled + written
            }
        }
        var first = texts.drop(attachedFrom).none { it.isNotEmpty() } && decoded.text.isNotEmpty()
        texts += decoded.text
        if (written.isNotEmpty() || from != region.end) {
            if (!document.replace(from, region.end, written)) {
                detach()
                attachedFrom = texts.size - 1
                first = false
                from = 0
                written = decoded.text
                document.replace(0, 0, written)
                region.appended(written.length)
            } else {
                region.appended(written.length - (region.end - from))
            }
        }
        if (first) {
            lead = opener.length
            region.skipLead(lead)
        }
        jobs(JobRecord("asr", job.moment.name.lowercase(), decoded.ms, null, decoded.text, if (decoded.fellBack) "fallback" else "applied",
            fromMs = slice[0].toLong() / (Slicer.SAMPLE_RATE / 1000), toMs = slice[1].toLong() / (Slicer.SAMPLE_RATE / 1000), context = decoded.usedContext))
        sliceLanded = true
        if (!Pipeline.skipsCleaner(raw, cfg)) prepareCleaner("text")
        onCovered?.invoke(slice[1].toDouble() / Slicer.SAMPLE_RATE)
        onText?.invoke(regionText())
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
                val rejected = Pipeline.untrustedClean(plan.target, result.text)
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
        document.replace(plan.from, plan.to, text)
        region.flightReplaced(text.length)
        onText?.invoke(regionText())
    }

    /** Writes [output] over the flight, set off from the cleaned text before it as `Pipeline.joinCleaned` sets chunks off. */
    private fun apply(from: Int, to: Int, output: String, plan: CleanPlan) {
        val text = readText()
        if (plan.region) {
            document.replace(from, to, output)
            region.flightReplaced(output.length)
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
        document.replace(from, to, separator + output)
        region.flightReplaced((separator + output).length)
        onText?.invoke(regionText())
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000
}
