package com.ezhou.sttbutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es wav <name>
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es clean <name>
 *
 * Reads files/selftest/<name>.wav, or <name>.prompt.txt with <name>.max, and writes its result next to
 * the input as <name>.asr.txt or <name>.out.txt. A summary goes to logcat tag SttButton. It runs as a
 * foreground service because that is the priority the dictation service gives the models; a broadcast
 * receiver's process is throttled to a fraction of the speed.
 */
class SelfTest : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("selftest", "Self-test", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "selftest").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Self-test").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        thread {
            try {
                intent.getStringExtra("wav")?.let { recognize(this, it) }
                intent.getStringExtra("clean")?.let { clean(this, it) }
            } catch (e: Throwable) {
                Log.e(TAG, "selftest failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun recognize(context: Context, name: String) {
        val dir = File(context.filesDir, "selftest")
        val samples = readWav(File(dir, "$name.wav"))
        val model = File(context.filesDir, ModelFiles.UNIFIED)
        val started = SystemClock.elapsedRealtime()
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(model, "encoder.int8.onnx").path,
                    decoder = File(model, "decoder.int8.onnx").path,
                    joiner = File(model, "joiner.int8.onnx").path,
                ),
                tokens = File(model, "tokens.txt").path,
                numThreads = 6,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        ))
        val loaded = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, 16000)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        recognizer.release()
        File(dir, "$name.asr.txt").writeText(text + "\n")
        Log.i(TAG, "selftest wav $name audio_ms=${samples.size / 16} load_ms=${loaded - started} " +
            "decode_ms=${SystemClock.elapsedRealtime() - loaded} words=${text.split(' ').size}")
    }

    private fun clean(context: Context, name: String) {
        val dir = File(context.filesDir, "selftest")
        val prompt = File(dir, "$name.prompt.txt").readText()
        val max = File(dir, "$name.max").readText().trim().toInt()
        val started = SystemClock.elapsedRealtime()
        LlamaCleaner(File(context.filesDir, ModelFiles.S1_MINI).path).use { cleaner ->
            val loaded = SystemClock.elapsedRealtime()
            val text = cleaner.clean(prompt, max)?.trim() ?: error("clean returned null")
            val stats = cleaner.stats()
            File(dir, "$name.out.txt").writeText(text + "\n")
            Log.i(TAG, "selftest clean $name load_ms=${loaded - started} " +
                "clean_ms=${SystemClock.elapsedRealtime() - loaded} out_tokens=${stats.outTokens} " +
                "drafted=${stats.drafted} accepted=${stats.accepted}")
        }
    }

    private fun readWav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(at + it) })
            val size = bytes.getInt(at + 4)
            if (id == "data") {
                val count = minOf(size, bytes.limit() - at - 8) / 2
                return FloatArray(count) { bytes.getShort(at + 8 + 2 * it) / 32768f }
            }
            at += 8 + size
        }
        error("no data chunk in $file")
    }

    companion object {
        private const val TAG = DictationService.TAG
    }
}
