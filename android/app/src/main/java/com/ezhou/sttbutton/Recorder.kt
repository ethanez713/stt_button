package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16 from the microphone, appended to [file] and handed to [sink] in 100 ms pieces. */
class Recorder(private val file: File, private val sink: (ShortArray, Int) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE),
        )
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialise" }
        record = audio
        file.parentFile?.mkdirs()
        running = true
        audio.startRecording()
        thread = Thread {
            val buffer = ShortArray(RATE / 10)
            val bytes = ByteArray(buffer.size * 2)
            FileOutputStream(file, true).use { out ->
                while (running) {
                    val n = audio.read(buffer, 0, buffer.size)
                    if (n <= 0) {
                        if (n < 0) Log.e(DictationService.TAG, "AudioRecord.read returned $n")
                        continue
                    }
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, n)
                    out.write(bytes, 0, n * 2)
                    sink(buffer, n)
                }
            }
        }.apply { name = "recorder"; start() }
    }

    /** Stops capture and returns once every sample read so far has reached the sink. */
    fun stop() {
        running = false
        record?.stop()
        thread?.join()
        record?.release()
        record = null
    }

    companion object {
        const val RATE = 16000
    }
}
