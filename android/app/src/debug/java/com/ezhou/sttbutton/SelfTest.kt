package com.ezhou.sttbutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es wav <name> [--ei speed N]
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es asr <name>
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es clean <name>
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --ez replay true
 * adb shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es probe <read|replace|typing|append>
 *
 * wav plays files/selftest/<name>.wav through the take pipeline (models, slicer, worker, chunk
 * cleans) at N times real time, inserting nothing, and writes the text a take would insert to
 * <name>.take.txt. asr recognizes the wav alone (<name>.asr.txt). clean runs <name>.prompt.txt with
 * the token limit in <name>.max (<name>.out.txt). A summary goes to logcat tag SttButton. It runs as a
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
                intent.getStringExtra("wav")?.let { take(this, it, intent.getIntExtra("speed", 1)) }
                intent.getStringExtra("asr")?.let { recognize(this, it, intent.getIntExtra("threads", ParakeetRecognizer.THREADS)) }
                intent.getStringExtra("clean")?.let { clean(this, it, intent.getStringExtra("with")) }
                if (intent.hasExtra("replay")) replay(this)
                intent.getStringExtra("probe")?.let { probe(this, it, intent) }
            } catch (e: Throwable) {
                Log.e(TAG, "selftest failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun recognize(context: Context, name: String, threads: Int) {
        val dir = File(context.filesDir, "selftest")
        val samples = readWav(File(dir, "$name.wav")).let { pcm -> FloatArray(pcm.size) { pcm[it] / 32768f } }
        val started = SystemClock.elapsedRealtime()
        ParakeetRecognizer(context.filesDir, threads).use { recognizer ->
            val loaded = SystemClock.elapsedRealtime()
            val cpuBefore = Process.getElapsedCpuTime()
            val text = recognizer.recognize(samples)
            val cpuMs = Process.getElapsedCpuTime() - cpuBefore
            File(dir, "$name.asr.txt").writeText(text + "\n")
            Log.i(TAG, "selftest asr $name audio_ms=${samples.size / 16} load_ms=${loaded - started} " +
                "decode_ms=${SystemClock.elapsedRealtime() - loaded} threads=$threads cpu_ms=$cpuMs " +
                "cpus_allowed=${File("/proc/self/status").readLines().first { it.startsWith("Cpus_allowed_list") }.substringAfter(':').trim()} " +
                "words=${text.split(' ').size}")
        }
    }

    private class TakeRun(val result: LiveResult, val inserted: String, val stopToInsertMs: Long, val firstPreviewMs: Long, val shown: BubbleContent)

    private fun runTake(models: Models, cfg: PipelineConfig, audio: ShortArray, speed: Int): TakeRun {
        val bubble = BubbleText()
        val live = LiveTake(models.recognizer, models::cleaner, cfg, bubble, models::prepareCleaner)
        val feedStarted = SystemClock.elapsedRealtime()
        var firstPreviewMs = -1L
        val preview = PreviewRecognizer(models::awaitPreview, { words ->
            if (firstPreviewMs < 0 && words.isNotEmpty()) firstPreviewMs = SystemClock.elapsedRealtime() - feedStarted
            bubble.preview(words)
        })
        val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = thread {
            while (!stopping.get()) {
                if (!live.step()) Thread.sleep(25)
            }
        }
        val piece = Slicer.SAMPLE_RATE / 10
        for (at in audio.indices step piece) {
            val buffer = audio.copyOfRange(at, minOf(audio.size, at + piece))
            live.addSamples(buffer)
            preview.offer(buffer, buffer.size, live.slicer.paused)
            Thread.sleep(100L / speed)
        }
        stopping.set(true)
        worker.join()
        val stopped = SystemClock.elapsedRealtime()
        live.markStop()
        val result = live.finish()
        val inserted = Pipeline.casual(result.cleaned, cfg)
        val stopToInsert = SystemClock.elapsedRealtime() - stopped
        preview.stop()
        return TakeRun(result, inserted, stopToInsert, firstPreviewMs, bubble.text())
    }

    private fun take(context: Context, name: String, speed: Int) {
        val dir = File(context.filesDir, "selftest")
        val cfg = PipelineConfig(org.json.JSONObject(context.assets.open("pipeline.json").bufferedReader().readText()))
        val audio = readWav(File(dir, "$name.wav"))
        val models = Models.forFiles(context.filesDir, cfg)
        val run = runTake(models, cfg, audio, speed)
        val result = run.result
        models.close()
        Log.i(TAG, "selftest bubble $name first_preview_ms=${run.firstPreviewMs} settled_words=${Pipeline.wordCount(run.shown.settled)} " +
            "preview_words=${Pipeline.wordCount(run.shown.preview)}")
        File(dir, "$name.take.txt").writeText(run.inserted + "\n")
        Log.i(TAG, "selftest take $name mode=${result.cleanMode} chunks=${result.chunks} audio_ms=${result.audioMs} " +
            "cue=${result.cue} context=${result.contextDecodes}/${result.contextFallbacks} text=${run.inserted}")
        File(dir, "$name.raw.txt").writeText(result.raw + "\n")
        Log.i(TAG, "selftest wav $name audio_ms=${audio.size / 16} speed=$speed mode=${result.cleanMode} " +
            "chunks=${result.chunks} stop_to_insert_ms=${run.stopToInsertMs} asr_ms=${result.asrMs} clean_ms=${result.cleanMs} " +
            "asr_after_stop_ms=${result.asrMsAfterStop} clean_after_stop_ms=${result.cleanMsAfterStop} " +
            "idle_cleans=${result.idleCleans} idle_hits=${result.idleHits} raw_words=${result.raw.split(' ').size}")
    }

    /**
     * Runs every `.pcm` and `.wav` in files/selftest/replay through the take pipeline at [REPLAY_SPEED],
     * one line each to files/selftest/replay.jsonl, under replay/pipeline.json when that file is there.
     */
    private fun replay(context: Context) {
        val dir = File(context.filesDir, "selftest")
        val inputs = File(dir, "replay")
        val override = File(inputs, "pipeline.json").takeIf { it.isFile }?.readBytes()
        val configBytes = override ?: context.assets.open("pipeline.json").use { it.readBytes() }
        val cfg = PipelineConfig(org.json.JSONObject(String(configBytes)))
        val configId = ConfigId.of(configBytes, ModelFiles.required.map { it to File(context.filesDir, it).length() })
        val out = File(dir, "replay.jsonl")
        val files = inputs.listFiles { f -> f.isFile && f.extension in listOf("pcm", "wav") }.orEmpty().sortedBy { it.name }
        val models = Models.forFiles(context.filesDir, cfg)
        try {
            for (file in files) {
                val audio = if (file.extension == "wav") readWav(file) else readPcm(file)
                val run = runTake(models, cfg, audio, REPLAY_SPEED)
                val result = run.result
                val line = org.json.JSONObject().put("name", file.name).put("config_id", configId)
                    .put("raw", result.raw).put("cleaned", run.inserted).put("clean_mode", result.cleanMode)
                    .put("chunks", result.chunks).put("audio_ms", audio.size / 16)
                    .put("asr_ms", result.asrMs).put("clean_ms", result.cleanMs).put("stop_to_insert_ms", run.stopToInsertMs)
                out.appendText(line.toString() + "\n")
            }
        } finally {
            models.close()
        }
        Log.i(TAG, "selftest replay done ${files.size}")
    }

    private fun probe(context: Context, mode: String, intent: Intent) {
        val service = DictationService.live ?: error("the accessibility service is not connected")
        val out = File(context.filesDir, "selftest/probe.jsonl")
        FieldProbe(service, out).run(mode, intent.getIntExtra("from", 0), intent.getIntExtra("to", 0), intent.getStringExtra("text").orEmpty())
        Log.i(TAG, "selftest probe done $mode")
    }

    private fun readPcm(file: File): ShortArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.limit() / 2) { bytes.getShort(2 * it) }
    }

    private fun clean(context: Context, name: String, with: String?) {
        val dir = File(context.filesDir, "selftest")
        val parakeet = if (with != null) ParakeetRecognizer(context.filesDir) else null
        if (with == "decode") parakeet!!.recognize(FloatArray(16000 * 5))
        val prompt = File(dir, "$name.prompt.txt").readText()
        val max = File(dir, "$name.max").readText().trim().toInt()
        val started = SystemClock.elapsedRealtime()
        LlamaCleaner(File(context.filesDir, ModelFiles.S1_MINI).path).use { cleaner ->
            val loaded = SystemClock.elapsedRealtime()
            val text = cleaner.clean(prompt, max)?.trim() ?: error("clean returned null")
            val stats = cleaner.stats()
            File(dir, "$name.out.txt").writeText(text + "\n")
            parakeet?.close()
            Log.i(TAG, "selftest clean $name with=$with load_ms=${loaded - started} " +
                "clean_ms=${SystemClock.elapsedRealtime() - loaded} out_tokens=${stats.outTokens} " +
                "drafted=${stats.drafted} accepted=${stats.accepted} threads=${stats.threads}")
        }
    }

    private fun readWav(file: File): ShortArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(at + it) })
            val size = bytes.getInt(at + 4)
            if (id == "data") {
                val count = minOf(size, bytes.limit() - at - 8) / 2
                return ShortArray(count) { bytes.getShort(at + 8 + 2 * it) }
            }
            at += 8 + size
        }
        error("no data chunk in $file")
    }

    companion object {
        private const val TAG = DictationService.TAG
        private const val REPLAY_SPEED = 8
    }
}
