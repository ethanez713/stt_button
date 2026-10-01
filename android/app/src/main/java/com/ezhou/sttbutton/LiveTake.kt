package com.ezhou.sttbutton

interface SliceRecognizer {
    fun recognize(samples: FloatArray): String
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
    private var stopAsrMs = 0L
    private var stopCleanMs = 0L
    private val finishedRaw = mutableListOf<String>()
    private var segmentStartSample = 0
    private var segmentsEnded = 0

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
        complete(takePending() + if (segmentsEnded > 0) slicer.endSegment() else slicer.finish())

    /** Speech was heard and followed by `insert_pause_s` of silence: the text so far is due for insertion. */
    fun segmentDue(): Boolean = cfg.insertPauseS?.let(slicer::segmentDue) ?: false

    /** Finishes the open segment as [finish] finishes a take, then starts the next one empty. */
    fun finishSegment(): LiveResult {
        markStop()
        val result = complete(takePending() + slicer.endSegment())
        sink?.segmentEnded(slicer.sampleCount.toDouble() / Slicer.SAMPLE_RATE)
        startNextSegment()
        return result
    }

    private fun complete(slices: List<IntArray>): LiveResult {
        for (slice in slices) recognize(slice)
        val raw = raw
        if (skips()) return result(raw, Pipeline.casual(Spoken.convert(raw), cfg, judged), 0, "raw", 0)
        val chunks = cleanClosed(final = true)
        val cleaned = chunks.map { cleanedChunks.getValue(it) }.filter { it.isNotEmpty() }.joinToString("\n\n")
        return result(raw, Pipeline.casual(cleaned, cfg, judged), chunks.size, "chunks", chunks.count { it in idleChunks })
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
        stopAsrMs = 0
        stopCleanMs = 0
        segmentStartSample = slicer.sampleCount
        segmentsEnded++
    }

    private fun result(raw: String, cleaned: String, chunks: Int, mode: String, idleHits: Int) = LiveResult(
        raw, cleaned, chunks, mode, asrMs, cleanMs, asrMs - stopAsrMs, cleanMs - stopCleanMs,
        inTokens, outTokens, idleCleans, idleHits, Pipeline.matchedCue(judged, cfg.selfCorrectionCues),
        (slicer.sampleCount - segmentStartSample) / (Slicer.SAMPLE_RATE / 1000).toLong(),
    )

    private fun takePending(): List<IntArray> = synchronized(pending) { pending.toList().also { pending.clear() } }

    private fun recognize(slice: IntArray) {
        val started = System.nanoTime()
        val pcm = slicer.copy(slice[0], slice[1])
        val text = recognizer.recognize(FloatArray(pcm.size) { pcm[it] / 32768f })
        asrMs += elapsedMs(started)
        texts += text
        sink?.sliceLanded(slice[1].toDouble() / Slicer.SAMPLE_RATE, text)
    }

    private fun chunksOf(cleaner: ChunkCleaner, final: Boolean) =
        Pipeline.liveChunks(raw, cleaner::countTokens, cfg.chunkMinTokens, cfg.chunkMaxTokens, final)

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

    private fun keep(cleaner: ChunkCleaner, chunk: String, cleaned: ChunkResult) {
        cleanedChunks[chunk] = cleaned.text
        inTokens += cleaner.countTokens(chunk)
        outTokens += cleaned.outTokens
        sink?.chunkCleaned(chunk, cleaned.text)
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000
}
