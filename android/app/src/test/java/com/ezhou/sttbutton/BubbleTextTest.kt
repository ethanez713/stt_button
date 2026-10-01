package com.ezhou.sttbutton

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextTest {
    @Test
    fun given_the_host_event_sequences_when_replayed_then_the_text_after_each_event_matches() {
        for (sequence in Fixtures.objects(Fixtures.vectors.getJSONArray("bubble"))) {
            val bubble = BubbleText()
            val events = sequence.getJSONArray("events")
            val expected = sequence.getJSONArray("after_each")
            for (i in 0 until events.length()) {
                val event = events.getJSONArray(i)
                when (event.getString(0)) {
                    "preview" -> bubble.preview(words(event.getJSONArray(1)))
                    "slice" -> bubble.sliceLanded(event.getDouble(1), event.getString(2))
                    "chunk" -> bubble.chunkCleaned(event.getString(1), event.getString(2))
                }
                val shown = bubble.text()
                val want = expected.getJSONArray(i)
                assertEquals("${sequence.getString("name")} after event $i settled", want.getString(0), shown.settled)
                assertEquals("${sequence.getString("name")} after event $i preview", want.getString(1), shown.preview)
            }
        }
    }

    @Test
    fun given_streaming_tokens_when_grouped_then_a_leading_space_starts_a_word() {
        val words = BubbleText.wordsFromTokens(arrayOf(" me", "et", " at", " noon"), floatArrayOf(0.1f, 0.2f, 0.9f, 1.4f))

        assertEquals(listOf("meet", "at", "noon"), words.map { it.text })
        assertEquals(listOf(0.1, 0.9, 1.4), words.map { it.startS }.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun given_a_listener_when_events_arrive_then_it_is_told_after_each() {
        val bubble = BubbleText()
        var calls = 0
        bubble.onChange = { calls++ }
        bubble.preview(emptyList())
        bubble.sliceLanded(1.0, "Hi.")
        bubble.chunkCleaned("Hi.", "Hi")

        assertEquals(3, calls)
    }

    private fun words(array: JSONArray) = List(array.length()) { i ->
        array.getJSONArray(i).let { PreviewWord(it.getString(0), it.getDouble(1)) }
    }
}
