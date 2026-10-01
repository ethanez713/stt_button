package com.ezhou.sttbutton

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

interface ClosableRecognizer : SliceRecognizer, AutoCloseable

interface ClosableCleaner : ChunkCleaner, AutoCloseable

class ParakeetRecognizer(filesDir: File, threads: Int = LlamaCleaner.THREADS) : ClosableRecognizer {
    private val recognizer: OfflineRecognizer

    init {
        val model = File(filesDir, ModelFiles.UNIFIED)
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(model, "encoder.int8.onnx").path,
                    decoder = File(model, "decoder.int8.onnx").path,
                    joiner = File(model, "joiner.int8.onnx").path,
                ),
                tokens = File(model, "tokens.txt").path,
                numThreads = threads,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        ))
    }

    override fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

class LlamaChunkCleaner(private val llama: LlamaCleaner, private val cfg: PipelineConfig) : ClosableCleaner {
    @Volatile private var cancelRequested = false

    override fun countTokens(text: String) = llama.countTokens(text)

    override fun clean(chunk: String): ChunkResult? {
        cancelRequested = false
        val started = System.nanoTime()
        val text = llama.clean(Pipeline.promptFor(cfg, chunk), Pipeline.maxNewTokens(cfg, countTokens(chunk)))
        if (text != null) {
            val stats = llama.stats()
            Log.i(DictationService.TAG, "chunk clean ms=${(System.nanoTime() - started) / 1_000_000} " +
                "out=${stats.outTokens} drafted=${stats.drafted} accepted=${stats.accepted} threads=${stats.threads}")
            return ChunkResult(text.trim(), stats.outTokens)
        }
        if (cancelRequested) return null
        // The prompt did not fit or decoding failed: the recognized words are kept rather than lost.
        Log.e(DictationService.TAG, "clean failed for a ${countTokens(chunk)}-token chunk; keeping it as recognized")
        return ChunkResult(chunk, 0)
    }

    override fun cancel() {
        cancelRequested = true
        llama.cancel()
    }

    override fun close() = llama.close()
}

/**
 * The models of one take, released when its text is inserted or the take is discarded. The recognizer
 * loads as the take opens, so a short take waits only for it. The cleaner loads on [prepareCleaner],
 * which the take calls once it runs long or its text needs cleaning; a user of the cleaner before then
 * triggers the load and blocks until it is ready.
 */
class Models(
    private val loadRecognizer: () -> ClosableRecognizer,
    private val loadCleaner: () -> ClosableCleaner,
    private val log: (String) -> Unit = { Log.i(DictationService.TAG, it) },
) : AutoCloseable {
    private val recognizerReady = CountDownLatch(1)
    private val cleanerReady = CountDownLatch(1)
    private val cleanerRequested = AtomicBoolean(false)
    @Volatile private var recognizerModel: ClosableRecognizer? = null
    @Volatile private var cleanerModel: ClosableCleaner? = null
    @Volatile private var recognizerFailure: Throwable? = null
    @Volatile private var cleanerFailure: Throwable? = null
    @Volatile private var closed = false
    private var cleanerLoader: Thread? = null

    val recognizer = object : SliceRecognizer {
        override fun recognize(samples: FloatArray): String {
            recognizerReady.await()
            recognizerFailure?.let { throw IllegalStateException("recognizer failed to load", it) }
            check(!closed) { "models released" }
            return recognizerModel!!.recognize(samples)
        }
    }

    private val cleanerProxy = object : ChunkCleaner {
        override fun countTokens(text: String) = awaitCleaner().countTokens(text)
        override fun clean(chunk: String) = awaitCleaner().clean(chunk)
        override fun cancel() {
            cleanerModel?.cancel()
        }
    }

    fun cleaner(): ChunkCleaner = cleanerProxy

    /** Blocks until the recognizer has loaded or failed to. */
    fun awaitRecognizerLoaded() = recognizerReady.await()

    private val recognizerLoader = Thread {
        try {
            val started = System.nanoTime()
            recognizerModel = loadRecognizer()
            log("recognizer loaded ms=${(System.nanoTime() - started) / 1_000_000}")
        } catch (e: Throwable) {
            recognizerFailure = e
            Log.e(DictationService.TAG, "recognizer load failed", e)
        } finally {
            recognizerReady.countDown()
        }
    }.apply { name = "recognizer-loader"; start() }

    /** Starts loading the cleaner if nothing has; [trigger] is logged by the call that starts it. */
    fun prepareCleaner(trigger: String) {
        if (!cleanerRequested.compareAndSet(false, true)) return
        synchronized(this) {
            if (closed) {
                cleanerReady.countDown()
                return
            }
            cleanerLoader = Thread {
                try {
                    val started = System.nanoTime()
                    cleanerModel = loadCleaner()
                    log("cleaner loaded ms=${(System.nanoTime() - started) / 1_000_000} trigger=$trigger")
                } catch (e: Throwable) {
                    cleanerFailure = e
                    Log.e(DictationService.TAG, "cleaner load failed", e)
                } finally {
                    cleanerReady.countDown()
                }
            }.apply { name = "cleaner-loader"; start() }
        }
    }

    private fun awaitCleaner(): ClosableCleaner {
        prepareCleaner("stop")
        cleanerReady.await()
        cleanerFailure?.let { throw IllegalStateException("cleaner failed to load", it) }
        check(!closed) { "models released" }
        return cleanerModel!!
    }

    override fun close() {
        val loader = synchronized(this) {
            closed = true
            cleanerLoader
        }
        recognizerLoader.join()
        loader?.join()
        recognizerModel?.close()
        cleanerModel?.close()
        recognizerModel = null
        cleanerModel = null
    }

    companion object {
        fun forFiles(filesDir: File, cfg: PipelineConfig) = Models(
            { ParakeetRecognizer(filesDir) },
            { LlamaChunkCleaner(LlamaCleaner(File(filesDir, ModelFiles.S1_MINI).path), cfg) },
        )
    }
}
