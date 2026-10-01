package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineVectorsTest {
    private fun bounds(slices: List<IntArray>) = slices.map { it.toList() }

    private fun sliced(case: org.json.JSONObject, piece: Int): List<List<Int>> {
        val pause = case.getJSONObject("pause")
        val slicer = Slicer(
            case.getDouble("slice_max_s"), case.getDouble("search_s"),
            if (pause.has("pause_s")) pause.getDouble("pause_s") else null,
            pause.optDouble("slice_min_s", 2.0), pause.optDouble("pause_margin_db", 10.0),
        )
        val audio = Fixtures.signal(case.getJSONArray("segments"))
        val out = mutableListOf<IntArray>()
        var at = 0
        while (at < audio.size) {
            val end = minOf(audio.size, at + piece)
            out += slicer.feed(audio.copyOfRange(at, end))
            at = end
        }
        return bounds(out + slicer.finish())
    }

    @Test
    fun given_slicer_vectors_when_fed_whole_or_in_pieces_then_cuts_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("slicer"))) {
            val b = case.getJSONArray("bounds")
            val expected = List(b.length()) { b.getJSONArray(it).let { p -> listOf(p.getInt(0), p.getInt(1)) } }
            for (piece in listOf(Int.MAX_VALUE, 777, 1600, 4000)) {
                assertEquals("${case.getString("name")} piece=$piece", expected, sliced(case, piece))
            }
        }
    }

    @Test
    fun given_live_chunk_vectors_when_chunked_then_chunks_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("live_chunks"))) {
            val chunks = Pipeline.liveChunks(
                case.getString("text"), Fixtures::words, case.getInt("min_tokens"), case.getInt("max_tokens"),
                case.getBoolean("final"),
            )
            assertEquals(case.getString("name"), Fixtures.strings(case.getJSONArray("chunks")), chunks)
        }
    }

    @Test
    fun given_skip_vectors_when_checked_then_decisions_match_the_host() {
        val skips = Fixtures.vectors.getJSONObject("skips_cleaner")
        val cues = Fixtures.strings(skips.getJSONArray("self_correction_cues"))
        assertEquals(Fixtures.config.selfCorrectionCues, cues)
        assertEquals(Fixtures.config.skipMaxWords, skips.getInt("skip_max_words"))
        for (case in Fixtures.objects(skips.getJSONArray("cases"))) {
            assertEquals(case.getString("text"), case.getBoolean("skipped"), Pipeline.skipsCleaner(case.getString("text"), Fixtures.config))
            val cue = if (case.isNull("cue")) null else case.getString("cue")
            assertEquals(case.getString("text"), cue, Pipeline.matchedCue(case.getString("text"), Fixtures.config.selfCorrectionCues))
        }
    }

    @Test
    fun given_casual_vectors_when_applied_then_text_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("casual"))) {
            assertEquals(case.getString("text"), case.getString("casual"), Pipeline.casual(case.getString("text"), Fixtures.config))
        }
    }

    @Test
    fun given_the_prompt_when_rendered_then_it_is_the_training_format() {
        val prompt = Pipeline.promptFor(Fixtures.config, "um so hi")
        assertTrue(prompt.startsWith("<|im_start|>system\nYou are a text normalizer"))
        assertTrue(prompt.endsWith("[Styling: semi-formal] [Structure: lists] [Context: general]\num so hi<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertEquals(1332, Pipeline.maxNewTokens(Fixtures.config, 1000))
    }
}
