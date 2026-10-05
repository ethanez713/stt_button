package com.ezhou.sttbutton

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadHealthTest {
    private fun healthAfterEmptyReads(count: Int) = ReadHealth().apply { repeat(count) { failure(0) } }

    @Test
    fun given_a_read_that_delivers_audio_when_judged_then_capture_goes_on() {
        assertNull(ReadHealth().failure(1600))
    }

    @Test
    fun given_a_read_that_returns_an_error_code_when_judged_then_capture_has_failed() {
        assertNotNull(ReadHealth().failure(ERROR_DEAD_OBJECT))
    }

    @Test
    fun given_empty_reads_one_short_of_the_limit_when_judged_then_capture_goes_on() {
        assertNull(healthAfterEmptyReads(48).failure(0))
    }

    @Test
    fun given_empty_reads_in_a_row_when_the_limit_is_reached_then_capture_has_failed() {
        assertNotNull(healthAfterEmptyReads(49).failure(0))
    }

    @Test
    fun given_audio_after_a_run_of_empty_reads_when_another_is_empty_then_the_count_has_started_over() {
        val health = healthAfterEmptyReads(49)
        health.failure(1600)
        assertNull(health.failure(0))
    }

    private companion object {
        const val ERROR_DEAD_OBJECT = -6
    }
}
