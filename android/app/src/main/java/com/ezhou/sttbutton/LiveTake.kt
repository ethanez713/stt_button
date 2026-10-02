package com.ezhou.sttbutton

interface SliceRecognizer {
    fun recognize(samples: FloatArray): String

    /** Decodes `Pipeline.contextAudio`'s output: the context's closing punctuation and the slice's words. */
    fun recognizeAfter(samples: FloatArray, junctionS: Double): Pair<String, String>
}

class ChunkResult(val text: String, val outTokens: Int)

interface ChunkCleaner {
    fun countTokens(text: String): Int

    /** Null when [cancel] was called while generating. */
    fun clean(chunk: String): ChunkResult?
    fun cancel()
}

/** What the preview bubble needs to hear about; step 8 implements it. */
interface TakeSink {
    fun sliceLanded(endS: Double, text: String)
    fun chunkCleaned(raw: String, cleaned: String)
    fun segmentEnded(endS: Double) {}
}

class LiveResult(
    val raw: String,
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
    /** What goes before [cleaned] to join it to the text inserted earlier in the take; empty for the first segment. */
    val prefix: String = "",
    val contextDecodes: Int = 0,
    val contextFallbacks: Int = 0,
    val drifts: Int = 0,
    val corrections: Int = 0,
    val correctionsKept: Int = 0,
)

/**
 * One take's pipeline, the port of `stt_button.live.LiveTake`. [addSamples] runs on the recorder thread;
 * [step], [markStop] and [finish] run on the one worker thread that recognizes slices and cleans chunks.
 */
class LiveTake(
    private val recognizer: SliceRecognizer,
    private val cleaner: () -> ChunkCleaner,
    private val cfg: PipelineConfig,
    private val sink: TakeSink? = null,
    private val prepareCleaner: (trigger: String) -> Unit = {},
) {
    val slicer = Slicer.fromConfig(cfg)
    private val pending = ArrayDeque<IntArray>()
    private val texts = mutableListOf<String>()
    private val cleanedChunks = HashMap<String, String>()
    private val idleChunks = HashSet<String>()
    private var asrMs = 0L
    private var cleanMs = 0L
    private var inTokens = 0
    private var outTokens = 0
    private var idleCleans = 0
    private var drifts = 0
    private var stopAsrMs = 0L
    private var stopCleanMs = 0L
    private val finishedRaw = mutableListOf<String>()
    private var segmentStartSample = 0
    private var segmentsEnded = 0
    private var slicesRecognized = 0
    private var contextDecodes = 0
    private var contextFallbacks = 0
    private var segmentsWithText = 0
    /** The punctuation the context decode put after the speech before this segment, or null without one. */
    private var leadingMark: String? = null
    /** The closing punctuation withheld from the last inserted segment, which the next one decides. */
    private var heldMark = ""

    @Volatile private var idleSpoken: Int? = null
    private var timedLoadRequested = false

    val raw: String get() = Pipeline.joinSlices(texts)

    /** The take's raw text up to this segment, which the skip and casual rules are judged on. */
    private val judged: String get() = Pipeline.joinSlices(finishedRaw + texts)

    private fun skips() = Pipeline.skipsCleaner(judged, cfg)

    fun addSamples(samples: ShortArray, length: Int = samples.size) {
        val slices = slicer.feed(samples, length)
        if (slices.isNotEmpty()) synchronized(pending) { pending += slices }
        if (!timedLoadRequested && slicer.sampleCount >= cfg.cleanerLoadAfterS * Slicer.SAMPLE_RATE) {
            timedLoadRequested = true
            prepareCleaner("time")
        }
        val spoken = idleSpoken
        if (spoken != null && slicer.lastSpeechEnd != spoken) cleaner().cancel()
    }

    /** One unit of work: the pending slices, else an idle clean. Returns whether it did anything. */
    fun step(): Boolean {
        val slices = takePending()
        if (slices.isEmpty()) return idleStep()
        for (slice in slices) {
            recognize(slice)
            if (!skips()) prepareCleaner("text")
            cleanClosed(final = false)
        }
        return true
    }

    fun markStop() {
        stopAsrMs = asrMs
        stopCleanMs = cleanMs
    }

    /** After a segment has ended, a leftover of silence alone is dropped; a take without one slices as the host does. */
    fun finish(): LiveResult =
        join(complete(takePending() + if (segmentsEnded > 0) slicer.endSegment() else slicer.finish()), holdMark = false)

    /** Seconds since the last speech, or since the take opened when none has been heard. */
    fun silenceS(): Double = (slicer.sampleCount - maxOf(slicer.lastSpeechEnd, 0)).toDouble() / Slicer.SAMPLE_RATE

    /** Speech was heard and followed by `insert_pause_s` of silence: the text so far is due for insertion. */
    fun segmentDue(): Boolean = cfg.insertPauseS?.let(slicer::segmentDue) ?: false

    /** Finishes the open segment as [finish] finishes a take, then starts the next one empty. */
    fun finishSegment(): LiveResult {
        markStop()
        val result = join(complete(takePending() + slicer.endSegment()), holdMark = true)
        sink?.segmentEnded(slicer.sampleCount.toDouble() / Slicer.SAMPLE_RATE)
        startNextSegment()
        return result
    }

    /**
     * Joins a segment to the text before it. The closing punctuation of a segment ended by a pause is
     * withheld, because whether the pause ended the sentence is only known once the next words are heard;
     * the next segment then opens with the withheld mark settled against its context decode's, or the withheld one alone.
     */
    private fun join(result: LiveResult, holdMark: Boolean): LiveResult {
        val earlier = segmentsWithText > 0
        val mark = if (earlier) leadingMark?.let { Pipeline.settledMark(heldMark, it) } ?: heldMark else ""
        var body = result.cleaned
        if (earlier && leadingMark != null && leadingMark !in Pipeline.SENTENCE_ENDS) body = Pipeline.continueSentence(body, result.raw)
        var prefix = ""
        if (earlier && body.isNotBlank()) {
            val joined = Pipeline.casual(if (mark.isEmpty()) body else "$mark $body", cfg, judged)
            prefix = joined.removeSuffix(body)
            if (prefix.isEmpty() || !(prefix.first().isWhitespace() || prefix.first() in Pipeline.SENTENCE_MARKS)) prefix = " $prefix"
        } else if (earlier && !holdMark) {
            prefix = Pipeline.casual(mark, cfg, judged)
        }
        val held = if (holdMark) Pipeline.trailingMark(body) else ""
        if (holdMark) body = Pipeline.withoutTrailingMark(body)
        if (body.isNotBlank() || prefix.isNotBlank()) segmentsWithText++
        heldMark = held
        return LiveResult(
            result.raw, body, result.chunks, result.cleanMode, result.asrMs, result.cleanMs, result.asrMsAfterStop,
            result.cleanMsAfterStop, result.inTokens, result.outTokens, result.idleCleans, result.idleHits, result.cue,
            result.audioMs, prefix, contextDecodes, contextFallbacks, result.drifts, result.corrections, result.correctionsKept,
        )
    }

    private fun complete(slices: List<IntArray>): LiveResult {
        for (slice in slices) recognize(slice)
        val raw = raw
        if (skips()) return result(raw, Pipeline.casual(Spoken.convert(raw), cfg, judged), 0, "raw", 0)
        val chunks = cleanClosed(final = true)
        val outputs = chunks.map { cleanedChunks.getValue(it) }
        val cleaned = Pipeline.joinCleaned(chunks, outputs, cfg.selfCorrectionCues)
        val (corrections, kept) = Pipeline.unresolvedCorrections(chunks, outputs, cfg.selfCorrectionCues)
        return result(raw, Pipeline.casual(cleaned, cfg, judged), chunks.size, "chunks", chunks.count { it in idleChunks }, corrections, kept)
    }

    private fun startNextSegment() {
        if (raw.isNotEmpty()) finishedRaw += raw
        texts.clear()
        cleanedChunks.clear()
        idleChunks.clear()
        asrMs = 0
        cleanMs = 0
        inTokens = 0
        outTokens = 0
        idleCleans = 0
        drifts = 0
        stopAsrMs = 0
        stopCleanMs = 0
        leadingMark = null
        segmentStartSample = slicer.sampleCount
        segmentsEnded++
    }

    private fun result(
        raw: String, cleaned: String, chunks: Int, mode: String, idleHits: Int, corrections: Int = 0, correctionsKept: Int = 0,
    ) = LiveResult(
        raw, cleaned, chunks, mode, asrMs, cleanMs, asrMs - stopAsrMs, cleanMs - stopCleanMs,
        inTokens, outTokens, idleCleans, idleHits, Pipeline.matchedCue(judged, cfg.selfCorrectionCues),
        (slicer.sampleCount - segmentStartSample) / (Slicer.SAMPLE_RATE / 1000).toLong(),
        drifts = drifts, corrections = corrections, correctionsKept = correctionsKept,
    )

    private fun takePending(): List<IntArray> = synchronized(pending) { pending.toList().also { pending.clear() } }

    private fun recognize(slice: IntArray) {
        val started = System.nanoTime()
        val text = sliceText(slice)
        asrMs += elapsedMs(started)
        slicesRecognized++
        texts += text
        sink?.sliceLanded(slice[1].toDouble() / Slicer.SAMPLE_RATE, text)
    }

    private fun samples(from: Int, to: Int): FloatArray {
        val pcm = slicer.copy(from, to)
        return FloatArray(pcm.size) { pcm[it] / 32768f }
    }

    /** Mirrors `stt_button.live.LiveTake._slice_text`: a slice after a pause is decoded with the speech before it. */
    private fun sliceText(slice: IntArray): String {
        val before = slicer.speechBefore(slice[0])
        val after = slicer.speechAfter(slice[0])
        if (slicesRecognized == 0 || before == null || after == null || after >= slice[1]) return recognizer.recognize(samples(slice[0], slice[1]))
        val (audio, junctionS) = Pipeline.contextAudio(::samples, before, after, slice[1], cfg.contextS, cfg.junctionSqueezeS)
        contextDecodes++
        val (mark, text) = recognizer.recognizeAfter(audio, junctionS)
        if (text.isEmpty()) {
            contextFallbacks++
            return recognizer.recognize(samples(slice[0], slice[1]))
        }
        if (texts.isEmpty()) leadingMark = mark
        else if (texts.last().isNotEmpty()) texts[texts.lastIndex] = Pipeline.withoutTrailingMark(texts.last()) + Pipeline.settledMark(Pipeline.trailingMark(texts.last()), mark)
        return text
    }

    private fun chunksOf(cleaner: ChunkCleaner, final: Boolean) =
        Pipeline.liveChunks(raw, cleaner::countTokens, cfg.chunkMinTokens, cfg.chunkMaxTokens, final, cfg.selfCorrectionCues)

    private fun cleanClosed(final: Boolean): List<String> {
        if (skips()) return emptyList()
        val cleaner = cleaner()
        val chunks = chunksOf(cleaner, final)
        for (chunk in chunks) {
            if (chunk in cleanedChunks) continue
            val started = System.nanoTime()
            val cleaned = cleaner.clean(chunk) ?: continue
            cleanMs += elapsedMs(started)
            keep(cleaner, chunk, cleaned)
        }
        return chunks
    }

    private fun idleStep(): Boolean {
        val spoken = slicer.lastSpeechEnd
        if (!slicer.paused || slicer.start < spoken || skips()) return false
        val cleaner = cleaner()
        val chunks = chunksOf(cleaner, final = true).filter { it !in cleanedChunks }
        if (chunks.isEmpty()) return false
        idleSpoken = spoken
        try {
            for (chunk in chunks) {
                if (slicer.lastSpeechEnd != spoken) break
                idleCleans++
                val started = System.nanoTime()
                val cleaned = cleaner.clean(chunk)
                cleanMs += elapsedMs(started)
                if (cleaned == null) break
                keep(cleaner, chunk, cleaned)
                idleChunks += chunk
            }
        } finally {
            idleSpoken = null
        }
        return true
    }

    /** A clean that swapped a name or number for another is replaced by the chunk as heard. */
    private fun keep(cleaner: ChunkCleaner, chunk: String, cleaned: ChunkResult) {
        val text = if (Pipeline.factDrift(chunk, cleaned.text).isEmpty()) cleaned.text else {
            drifts++
            Spoken.convert(chunk)
        }
        val kept = Spoken.keepSpokenEnding(chunk, text)
        cleanedChunks[chunk] = kept
        inTokens += cleaner.countTokens(chunk)
        outTokens += cleaned.outTokens
        sink?.chunkCleaned(chunk, kept)
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000
}
