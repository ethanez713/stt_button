package com.ezhou.sttbutton

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch

class ParakeetRecognizer(filesDir: File, threads: Int = LlamaCleaner.THREADS) : SliceRecognizer, AutoCloseable {
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

class LlamaChunkCleaner(private val llama: LlamaCleaner, private val cfg: PipelineConfig) : ChunkCleaner, AutoCloseable {
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
 * The models of one take, loaded on a background thread from the moment the take opens and released
 * when its text is inserted or the take is discarded. Users block until their model is ready.
 */
class Models(private val filesDir: File, private val cfg: PipelineConfig) : AutoCloseable {
    private val ready = CountDownLatch(1)
    @Volatile private var recognizerModel: ParakeetRecognizer? = null
    @Volatile private var cleanerModel: LlamaChunkCleaner? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var closed = false

    val recognizer = object : SliceRecognizer {
        override fun recognize(samples: FloatArray) = await { recognizerModel!! }.recognize(samples)
    }

    private val cleanerProxy = object : ChunkCleaner {
        override fun countTokens(text: String) = await { cleanerModel!! }.countTokens(text)
        override fun clean(chunk: String) = await { cleanerModel!! }.clean(chunk)
        override fun cancel() {
            cleanerModel?.cancel()
        }
    }

    fun cleaner(): ChunkCleaner = cleanerProxy

    private val loader = Thread {
        try {
            val started = System.nanoTime()
            recognizerModel = ParakeetRecognizer(filesDir)
            cleanerModel = LlamaChunkCleaner(LlamaCleaner(File(filesDir, ModelFiles.S1_MINI).path), cfg)
            Log.i(DictationService.TAG, "models loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        } catch (e: Throwable) {
            failure = e
            Log.e(DictationService.TAG, "model load failed", e)
        } finally {
            ready.countDown()
        }
    }.apply { name = "model-loader"; start() }

    private fun <T> await(model: () -> T): T {
        ready.await()
        failure?.let { throw IllegalStateException("models failed to load", it) }
        check(!closed) { "models released" }
        return model()
    }

    override fun close() {
        loader.join()
        closed = true
        recognizerModel?.close()
        cleanerModel?.close()
        recognizerModel = null
        cleanerModel = null
    }
}
