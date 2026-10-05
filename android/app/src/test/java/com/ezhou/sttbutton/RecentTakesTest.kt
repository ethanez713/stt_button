package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class RecentTakesTest {
    @Test
    fun given_malformed_lines_and_other_events_when_read_then_only_whole_inserts_and_clips_remain() {
        val lines = listOf(
            "",
            "   ",
            "{not json",
            """{"event":"start","id":"a","ts":"2026-10-05T12:00:00Z","pkg":"app.one"}""",
            """{"event":"discard","id":"a","ts":"2026-10-05T12:01:00Z","pkg":"app.one","reason":"empty","trigger":"stop"}""",
            """{"event":"edit","id":"b","ts":"2026-10-05T12:02:00Z","text_after":"later"}""",
            """{"event":"insert","ts":"2026-10-05T12:03:00Z","pkg":"app.one","raw":"heard it"}""",
            """{"event":"insert","ts":"not-a-time","pkg":"app.one","cleaned":"nope"}""",
            """{"event":"insert","ts":"2026-10-05T12:04:00Z","pkg":null,"cleaned":"no package"}""",
            """{"event":"clipboard","ts":"2026-10-05T12:05:00Z","pkg":"app.one","raw":"heard clip"}""",
            """{"event":"insert","ts":"2026-10-05T12:06:00Z","pkg":"app.one","cleaned":"Hello","raw":"hello"}""",
            """{"event":"clipboard","ts":"2026-10-05T12:07:00Z","pkg":"app.two","text":"Copied","raw":"copied"}""",
            "[1, 2, 3]",
        )

        val takes = RecentTakes.fromLines(lines)

        assertEquals(listOf("Copied", "Hello"), takes.map { it.text })
        assertEquals(listOf("app.two", "app.one"), takes.map { it.pkg })
        assertEquals(Instant.parse("2026-10-05T12:07:00Z"), takes[0].at)
        assertTrue(takes.none { it.text == "heard it" || it.text == "hello" || it.text == "nope" })
    }

    @Test
    fun given_more_than_ten_when_read_then_the_newest_ten_come_first_by_time() {
        val minutes = listOf(3, 1, 11, 0, 7, 2, 10, 4, 8, 5, 9, 6)
        val lines = mutableListOf(clip(30, "copied"))
        lines += minutes.map { insert(it, "text $it") }
        // Same clock time as an older line, written later, so the file order breaks the tie.
        lines += insert(4, "text 4 again")

        val takes = RecentTakes.fromLines(lines)

        assertEquals(
            listOf("copied", "text 11", "text 10", "text 9", "text 8", "text 7", "text 6", "text 5", "text 4 again", "text 4"),
            takes.map { it.text },
        )
        assertEquals(10, RecentTakes.LIMIT)
        assertEquals("app.clip", takes[0].pkg)
    }

    @Test
    fun given_no_log_file_when_read_then_the_list_is_empty() {
        val missing = Files.createTempDirectory("recent-takes").resolve("edits.jsonl").toFile()

        assertEquals(emptyList<RecentTake>(), RecentTakes.read(missing))
    }

    @Test
    fun given_a_log_file_when_read_then_its_lines_are_the_list() {
        val file = Files.createTempDirectory("recent-takes").resolve("edits.jsonl").toFile()
        file.writeText(insert(1, "from disk") + "\n" + "{not json\n")

        assertEquals(listOf("from disk"), RecentTakes.read(file).map { it.text })
    }

    private fun insert(minute: Int, cleaned: String) =
        """{"event":"insert","ts":"2026-10-05T12:${minute.toString().padStart(2, '0')}:00Z","pkg":"app.insert","cleaned":"$cleaned","raw":"raw $cleaned"}"""

    private fun clip(minute: Int, text: String) =
        """{"event":"clipboard","ts":"2026-10-05T12:${minute.toString().padStart(2, '0')}:00Z","pkg":"app.clip","text":"$text","raw":"raw $text"}"""
}
