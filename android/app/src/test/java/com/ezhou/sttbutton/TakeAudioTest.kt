package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeAudioTest {
    private val mb = 1024L * 1024

    private fun pcm(id: String, bytes: Long, modifiedAtMs: Long) = TakeAudioFile("$id.pcm", bytes, modifiedAtMs)
    private fun marker(id: String) = TakeAudioFile("$id.corrected", 0, 0)

    @Test
    fun given_any_number_of_files_under_the_cap_when_pruned_then_nothing_is_deleted() {
        val files = (1..500).map { pcm("t$it", 1000, it.toLong()) }

        assertTrue(TakeAudio.toDelete(files).isEmpty())
    }

    @Test
    fun given_a_total_past_the_cap_when_pruned_then_the_oldest_go_until_it_fits() {
        val files = listOf(pcm("old", 200 * mb, 1), pcm("mid", 100 * mb, 2), pcm("new", 100 * mb, 3))

        assertEquals(listOf("old.pcm"), TakeAudio.toDelete(files))
    }

    @Test
    fun given_a_total_exactly_at_the_cap_when_pruned_then_nothing_is_deleted() {
        assertTrue(TakeAudio.toDelete(listOf(pcm("a", TakeAudio.MAX_BYTES, 1))).isEmpty())
    }

    @Test
    fun given_corrected_and_plain_takes_when_over_the_cap_then_plain_ones_go_first_oldest_first() {
        val files = listOf(
            pcm("a", 150 * mb, 1), marker("a"),
            pcm("b", 150 * mb, 2),
            pcm("c", 150 * mb, 3),
            pcm("d", 150 * mb, 4), marker("d"),
        )

        assertEquals(listOf("b.pcm", "c.pcm"), TakeAudio.toDelete(files))
    }

    @Test
    fun given_only_corrected_takes_over_the_cap_when_pruned_then_oldest_go_and_take_their_markers() {
        val files = listOf(pcm("a", 200 * mb, 1), marker("a"), pcm("b", 200 * mb, 2), marker("b"))

        assertEquals(listOf("a.pcm", "a.corrected"), TakeAudio.toDelete(files))
    }

    @Test
    fun given_the_recording_in_progress_alone_over_the_cap_when_pruned_then_it_survives() {
        val files = listOf(pcm("old", 10 * mb, 1), pcm("now", 400 * mb, 2))

        assertEquals(listOf("old.pcm"), TakeAudio.toDelete(files, inProgress = "now.pcm"))
    }

    @Test
    fun given_a_marker_without_a_recording_when_pruned_then_it_is_listed() {
        val files = listOf(pcm("a", mb, 1), marker("a"), marker("gone"))

        assertEquals(listOf("gone.corrected"), TakeAudio.toDelete(files))
    }
}
