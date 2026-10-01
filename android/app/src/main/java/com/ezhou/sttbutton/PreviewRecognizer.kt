package com.ezhou.sttbutton

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The streaming FastConformer behind the preview bubble, on its own thread so it never delays a slice or a
 * chunk clean. It loads after [awaitFirst] returns, which is the take's real recognizer, so a short take is
 * never held up by it. A load or decode failure disables the preview for the take and nothing else.
 */
class PreviewRecognizer(
    private val modelDir: File,
    private val awaitFirst: () -> Unit,
    private val onWords: (List<PreviewWord>) -> Unit,
    private val threads: Int = THREADS,
) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    @Volatile private var stopped = false
    private val thread = Thread(::run).apply { name = "preview"; start() }

    /** Called on the recorder thread: copies the samples and returns. */
    fun offer(samples: ShortArray, length: Int) {
        if (!stopped) queue.add(FloatArray(length) { samples[it] / 32768f })
    }

    fun stop() {
        stopped = true
        queue.add(FloatArray(0))
        thread.join()
    }

    private fun run() {
        var recognizer: OnlineRecognizer? = null
        try {
            awaitFirst()
            if (stopped) return
            val started = System.nanoTime()
            recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, "encoder.int8.onnx").path,
                        decoder = File(modelDir, "decoder.int8.onnx").path,
                        joiner = File(modelDir, "joiner.int8.onnx").path,
                    ),
                    tokens = File(modelDir, "tokens.txt").path,
                    numThreads = threads,
                    modelType = "nemo_transducer",
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
            ))
            Log.i(DictationService.TAG, "preview loaded ms=${(System.nanoTime() - started) / 1_000_000}")
            val stream = recognizer.createStream()
            var shown = ""
            while (!stopped) {
                val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (chunk.isEmpty()) continue
                stream.acceptWaveform(chunk, 16000)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                if (result.text != shown) {
                    shown = result.text
                    onWords(BubbleText.wordsFromTokens(result.tokens, result.timestamps))
                }
            }
            stream.release()
        } catch (e: Throwable) {
            Log.e(DictationService.TAG, "preview disabled for this take", e)
        } finally {
            recognizer?.release()
        }
    }

    companion object {
        const val THREADS = 2
        private const val POLL_MS = 100L
    }
}
