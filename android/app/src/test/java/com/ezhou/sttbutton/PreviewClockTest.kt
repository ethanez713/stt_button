package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewClockTest {
    private val second = Slicer.SAMPLE_RATE

    @Test
    fun given_no_skipped_audio_when_mapped_then_stream_time_is_take_time() {
        val clock = PreviewClock().also { it.fed(5 * second) }

        assertEquals(3.0, clock.takeTimeS(3.0), 1e-9)
    }

    @Test
    fun given_a_skipped_pause_when_mapped_then_words_after_the_resume_shift_by_its_length() {
        val clock = PreviewClock()
        clock.fed(4 * second)
        clock.skipped(6 * second)
        clock.resumed()
        clock.fed(2 * second)

        assertEquals(3.9, clock.takeTimeS(3.9), 1e-9)
        assertEquals(10.0, clock.takeTimeS(4.0), 1e-9)
        assertEquals(11.5, clock.takeTimeS(5.5), 1e-9)
    }

    @Test
    fun given_two_skipped_pauses_when_mapped_then_each_word_shifts_by_the_pauses_before_it() {
        val clock = PreviewClock()
        clock.fed(2 * second)
        clock.skipped(3 * second)
        clock.resumed()
        clock.fed(2 * second)
        clock.skipped(5 * second)
        clock.resumed()
        clock.fed(second)

        assertEquals(5.0, clock.takeTimeS(2.0), 1e-9)
        assertEquals(6.9, clock.takeTimeS(3.9), 1e-9)
        assertEquals(12.0, clock.takeTimeS(4.0), 1e-9)
    }

    @Test
    fun given_a_resume_without_skipped_audio_when_mapped_then_nothing_shifts() {
        val clock = PreviewClock()
        clock.fed(second)
        clock.resumed()

        assertEquals(1.5, clock.takeTimeS(1.5), 1e-9)
    }
}
