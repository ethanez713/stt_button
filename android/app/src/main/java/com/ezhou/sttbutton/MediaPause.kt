package com.ezhou.sttbutton

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

interface FocusPort {
    fun request(): Boolean
    fun abandon()
}

/** Holds audio focus for the length of a recording so media that honours focus pauses and does not reach the microphone. */
class MediaPause(private val port: FocusPort) {
    private var held = false

    /** True when focus is held after the call. */
    fun pause(): Boolean {
        if (held) return true
        held = port.request()
        return held
    }

    fun resume() {
        if (!held) return
        held = false
        port.abandon()
    }
}

class AndroidFocusPort(private val audio: AudioManager) : FocusPort {
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    override fun request() = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audio.abandonAudioFocusRequest(request)
    }
}
