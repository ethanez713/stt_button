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
 * Maps a time in the audio the preview model was fed to the take's own time, which is later by the audio
 * dropped during pauses. The bubble compares word times with the take's slices, so they must be take times.
 */
class PreviewClock {
    private val breakpoints = ArrayList<Pair<Double, Double>>()
    private var fedSamples = 0L
    private var skippedSamples = 0L
    private var skippedAtLastBreak = 0L

    @Synchronized
    fun fed(samples: Int) {
        fedSamples += samples
    }

    @Synchronized
    fun skipped(samples: Int) {
        skippedSamples += samples
    }

    /** Audio is being fed again: from here on, stream time lags take time by everything skipped so far. */
    @Synchronized
    fun resumed() {
        if (skippedSamples == skippedAtLastBreak) return
        skippedAtLastBreak = skippedSamples
        breakpoints += fedSamples.toDouble() / Slicer.SAMPLE_RATE to skippedSamples.toDouble() / Slicer.SAMPLE_RATE
    }

    @Synchronized
    fun takeTimeS(streamS: Double): Double =
        streamS + (breakpoints.lastOrNull { it.first <= streamS }?.second ?: 0.0)
}

/** The streaming FastConformer behind the preview bubble; [Models] keeps it loaded between takes. */
fun loadPreviewModel(modelDir: File, threads: Int = PreviewRecognizer.THREADS): OnlineRecognizer = OnlineRecognizer(
    config = OnlineRecognizerConfig(
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
    ),
)

/**
 * One take's stream over the preview model, on its own thread so it never delays a slice or a chunk clean.
 * [awaitModel] returns once the take's real recognizer has loaded and then the preview model, so a short
 * take is never held up by it; a null model or a decode failure disables the preview for the take and
 * nothing else.
 */
class PreviewRecognizer(
    private val awaitModel: () -> OnlineRecognizer?,
    private val onWords: (List<PreviewWord>) -> Unit,
) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    private val clock = PreviewClock()
    private var held: FloatArray? = null
    @Volatile private var stopped = false
    private val thread = Thread(::run).apply { name = "preview"; start() }

    /**
     * Called on the recorder thread: copies the samples and returns. Audio that arrives while the take is
     * [paused] is not decoded, except the last piece before it resumes, which keeps a soft word onset.
     */
    fun offer(samples: ShortArray, length: Int, paused: Boolean) {
        if (stopped) return
        val piece = FloatArray(length) { samples[it] / 32768f }
        if (paused) {
            held?.let { clock.skipped(it.size) }
            held = piece
            return
        }
        held?.let {
            clock.resumed()
            queue.add(it)
            clock.fed(it.size)
            held = null
        }
        queue.add(piece)
        clock.fed(length)
    }

    fun stop() {
        stopped = true
        queue.add(FloatArray(0))
        thread.join()
    }

    private fun run() {
        try {
            val recognizer = awaitModel() ?: return
            if (stopped) return
            val stream = recognizer.createStream()
            try {
                var shown = ""
                while (!stopped) {
                    val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (chunk.isEmpty()) continue
                    stream.acceptWaveform(chunk, 16000)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    if (result.text != shown) {
                        shown = result.text
                        onWords(BubbleText.wordsFromTokens(result.tokens, result.timestamps).map { PreviewWord(it.text, clock.takeTimeS(it.startS)) })
                    }
                }
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            Log.e(DictationService.TAG, "preview disabled for this take", e)
        }
    }

    companion object {
        const val THREADS = 2
        private const val POLL_MS = 100L
    }
}
