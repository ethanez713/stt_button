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
    val contextDecodes: Int = 0,
    val contextFallbacks: Int = 0,
    val drifts: Int = 0,
    val corrections: Int = 0,
    val correctionsKept: Int = 0,
)

/**
 * One take's pipeline, the port of `stt_button.live.LiveTake`. [addSamples] runs on the recorder thread;
 * [step], [markStop] and [finish] run on the one worker thread that recognizes slices and cleans chunks.
 * [cleaned] is the text before the casual rule; the caller applies that where the text is inserted.
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
    private var contextDecodes = 0
    private var contextFallbacks = 0

    @Volatile private var idleSpoken: Int? = null
    private var timedLoadRequested = false

    val raw: String get() = Pipeline.joinSlices(texts)

    private fun skips() = Pipeline.skipsCleaner(raw, cfg)

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

    fun finish(): LiveResult = complete(takePending() + slicer.finish())

    /** Seconds since the last speech, or since the take opened when none has been heard. */
    fun silenceS(): Double = (slicer.sampleCount - maxOf(slicer.lastSpeechEnd, 0)).toDouble() / Slicer.SAMPLE_RATE

    private fun complete(slices: List<IntArray>): LiveResult {
        for (slice in slices) recognize(slice)
        val raw = raw
        if (skips()) return result(raw, Spoken.convert(raw), 0, "raw", 0)
        val chunks = cleanClosed(final = true)
        val outputs = chunks.map { cleanedChunks.getValue(it) }
        val cleaned = Pipeline.joinCleaned(chunks, outputs, cfg.selfCorrectionCues)
        val (corrections, kept) = Pipeline.unresolvedCorrections(chunks, outputs, cfg.selfCorrectionCues)
        return result(raw, cleaned, chunks.size, "chunks", chunks.count { it in idleChunks }, corrections, kept)
    }

    private fun result(
        raw: String, cleaned: String, chunks: Int, mode: String, idleHits: Int, corrections: Int = 0, correctionsKept: Int = 0,
    ) = LiveResult(
        raw, cleaned, chunks, mode, asrMs, cleanMs, asrMs - stopAsrMs, cleanMs - stopCleanMs,
        inTokens, outTokens, idleCleans, idleHits, Pipeline.matchedCue(raw, cfg.selfCorrectionCues),
        slicer.sampleCount / (Slicer.SAMPLE_RATE / 1000).toLong(),
        contextDecodes, contextFallbacks, drifts, corrections, correctionsKept,
    )

    private fun takePending(): List<IntArray> = synchronized(pending) { pending.toList().also { pending.clear() } }

    private fun recognize(slice: IntArray) {
        val started = System.nanoTime()
        val text = sliceText(slice)
        asrMs += elapsedMs(started)
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
        if (texts.isEmpty() || before == null || after == null || after >= slice[1]) return recognizer.recognize(samples(slice[0], slice[1]))
        val (audio, junctionS) = Pipeline.contextAudio(::samples, before, after, slice[1], cfg.contextS, cfg.junctionSqueezeS)
        contextDecodes++
        val (mark, text) = recognizer.recognizeAfter(audio, junctionS)
        if (text.isEmpty()) {
            contextFallbacks++
            return recognizer.recognize(samples(slice[0], slice[1]))
        }
        if (texts.last().isNotEmpty()) texts[texts.lastIndex] = Pipeline.withoutTrailingMark(texts.last()) + Pipeline.settledMark(Pipeline.trailingMark(texts.last()), mark)
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
