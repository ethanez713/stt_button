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
    fun given_spoken_vectors_when_converted_then_text_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("spoken"))) {
            assertEquals(case.getString("text"), case.getString("spoken"), Spoken.convert(case.getString("text")))
        }
    }

    @Test
    fun given_junction_vectors_when_split_then_the_mark_and_text_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("junction"))) {
            val times = case.getJSONArray("times").let { a -> List(a.length(), a::getDouble) }
            val expected = Fixtures.strings(case.getJSONArray("split"))
            val split = Pipeline.splitAtJunction(Fixtures.strings(case.getJSONArray("tokens")), times, case.getDouble("junction_s"))
            assertEquals(expected, listOf(split.first, split.second))
        }
    }

    @Test
    fun given_context_audio_vectors_when_built_then_length_ends_and_junction_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("context_audio"))) {
            val squeeze = if (case.isNull("squeeze_s")) null else case.getDouble("squeeze_s")
            val (audio, junction) = Pipeline.contextAudio(
                { from, to -> FloatArray(to - from) { (from + it).toFloat() } }, case.getInt("speech_before"),
                case.getInt("speech_after"), case.getInt("end"), case.getDouble("context_s"), squeeze,
            )
            assertEquals(case.getInt("length"), audio.size)
            assertEquals(case.getInt("first"), audio.first().toInt())
            assertEquals(case.getInt("last"), audio.last().toInt())
            assertEquals(case.getDouble("junction_s"), junction, 1e-9)
        }
    }

    @Test
    fun given_settled_mark_vectors_when_settled_then_the_mark_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("settled_mark"))) {
            assertEquals(case.getString("mark"), Pipeline.settledMark(case.getString("slice"), case.getString("context")))
        }
    }

    @Test
    fun given_text_that_ends_in_a_word_or_a_mark_before_the_caret_when_a_take_opens_then_a_space_leads_it() {
        assertEquals(" ", Pipeline.takeOpener("short takes", 11))
        assertEquals(" ", Pipeline.takeOpener("(This is the gate test)", 23))
        assertEquals(" ", Pipeline.takeOpener("one two", 3))
        assertEquals(" ", Pipeline.takeOpener("no caret", -1))
    }

    @Test
    fun given_an_empty_field_white_space_or_an_open_bracket_before_the_caret_when_a_take_opens_then_nothing_leads_it() {
        assertEquals("", Pipeline.takeOpener("", 0))
        assertEquals("", Pipeline.takeOpener("one two", 0))
        assertEquals("", Pipeline.takeOpener("one ", 4))
        assertEquals("", Pipeline.takeOpener("one\n", 4))
        assertEquals("", Pipeline.takeOpener("see (", 5))
        assertEquals("", Pipeline.takeOpener("one two", 4))
    }

    @Test
    fun given_a_cleaned_first_word_that_matches_a_lower_case_raw_one_when_continued_then_it_is_lowercased() {
        assertEquals("have some", Pipeline.continueSentence("Have some", "have some monitoring"))
        assertEquals("Have some", Pipeline.continueSentence("Have some", "Have some monitoring"))
        assertEquals("Okay then", Pipeline.continueSentence("Okay then", "so then"))
        assertEquals("I think so", Pipeline.continueSentence("I think so", "I think so"))
    }

    @Test
    fun given_the_prompt_when_rendered_then_it_is_the_training_format() {
        val prompt = Pipeline.promptFor(Fixtures.config, "um so hi")
        assertTrue(prompt.startsWith("<|im_start|>system\nYou are a text normalizer"))
        assertTrue(prompt.endsWith("[Styling: semi-formal] [Structure: lists] [Context: general]\num so hi<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertEquals(1332, Pipeline.maxNewTokens(Fixtures.config, 1000))
    }
}
