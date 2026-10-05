package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16 kHz mono PCM16 from the microphone, appended to [file] and handed to [sink] in 100 ms pieces.
 * [onFailure] is called on the recorder thread when capture cannot go on.
 */
class Recorder(
    private val file: File,
    private val onFailure: (Throwable) -> Unit,
    private val sink: (ShortArray, Int) -> Unit,
) {
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
        audio.startRecording()
        // startRecording() returns normally when the system refuses the microphone; only the state shows it.
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start recording" }
        running = true
        thread = Thread {
            try {
                capture(audio)
            } catch (e: Throwable) {
                // A read that fails because stop() ended the recording is the ordinary way out.
                if (running) onFailure(e)
            }
        }.apply { name = "recorder"; start() }
    }

    private fun capture(audio: AudioRecord) {
        val buffer = ShortArray(RATE / 10)
        val bytes = ByteArray(buffer.size * 2)
        val health = ReadHealth()
        FileOutputStream(file, true).use { out ->
            while (running) {
                val n = audio.read(buffer, 0, buffer.size)
                health.failure(n)?.let { error(it) }
                if (n <= 0) continue
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, n)
                out.write(bytes, 0, n * 2)
                sink(buffer, n)
            }
        }
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
