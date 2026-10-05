package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeAudioTest {
    private fun file(name: String, bytes: Long, modifiedAtMs: Long) = TakeAudioFile(name, bytes, modifiedAtMs)

    @Test
    fun given_more_than_twenty_files_when_pruned_then_the_newest_twenty_stay() {
        val files = (1..25).map { file("t$it.pcm", 1000, it.toLong()) }

        assertEquals((1..5).map { "t$it.pcm" }, TakeAudio.toDelete(files))
    }

    @Test
    fun given_a_total_past_one_hundred_megabytes_when_pruned_then_the_oldest_go_until_it_fits() {
        val mb = 1024L * 1024
        val files = listOf(
            file("old.pcm", 60 * mb, 1),
            file("mid.pcm", 50 * mb, 2),
            file("new.pcm", 30 * mb, 3),
        )

        assertEquals(listOf("old.pcm"), TakeAudio.toDelete(files))
    }

    @Test
    fun given_both_limits_passed_when_pruned_then_the_byte_cap_continues_oldest_first() {
        val mb = 1024L * 1024
        val files = (1..21).map { file("t$it.pcm", 10 * mb, it.toLong()) }

        assertEquals((1..11).map { "t$it.pcm" }, TakeAudio.toDelete(files))
    }

    @Test
    fun given_exactly_twenty_files_at_the_byte_cap_when_pruned_then_nothing_is_deleted() {
        val each = TakeAudio.MAX_BYTES / 20
        val files = (1..20).map { file("t$it.pcm", each, it.toLong()) }

        assertTrue(TakeAudio.toDelete(files).isEmpty())
        assertEquals(20, TakeAudio.KEEP_NEWEST)
        assertEquals(100L * 1024 * 1024, TakeAudio.MAX_BYTES)
    }
}
