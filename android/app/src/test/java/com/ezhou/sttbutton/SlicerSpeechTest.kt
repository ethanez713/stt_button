package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlicerSpeechTest {
    private fun silence(seconds: Double) = ShortArray((seconds * Slicer.SAMPLE_RATE).toInt())
    private fun tone(seconds: Double) = ShortArray((seconds * Slicer.SAMPLE_RATE).toInt()) { (9000 * Math.sin(2 * Math.PI * 220 * it / 16000)).toInt().toShort() }

    @Test
    fun given_a_pause_between_two_stretches_of_speech_when_asked_then_the_slicer_finds_the_speech_around_it() {
        val slicer = Slicer(30.0, 5.0, 0.8)
        slicer.feed(silence(1.5) + tone(3.0) + silence(2.0) + tone(2.0))

        assertEquals((4.5 * Slicer.SAMPLE_RATE).toInt(), slicer.speechBefore((5.0 * Slicer.SAMPLE_RATE).toInt()))
        assertEquals((6.5 * Slicer.SAMPLE_RATE).toInt(), slicer.speechAfter((4.5 * Slicer.SAMPLE_RATE).toInt()))
        assertNull(slicer.speechBefore(Slicer.SAMPLE_RATE))
        assertNull(slicer.speechAfter((8.6 * Slicer.SAMPLE_RATE).toInt()))
    }
}
