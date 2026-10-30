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
    fun given_correction_vectors_when_text_grows_word_by_word_then_chunks_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("correction_chunks"))) {
            val chunks = Pipeline.liveChunks(
                case.getString("text"), Fixtures::words, case.getInt("min_tokens"), case.getInt("max_tokens"),
                case.getBoolean("final"), Fixtures.strings(case.getJSONArray("cues")),
            )
            assertEquals(case.getString("name") + ": " + case.getString("text"), Fixtures.strings(case.getJSONArray("chunks")), chunks)
        }
    }

    @Test
    fun given_spoken_ending_vectors_when_kept_then_they_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("spoken_ending"))) {
            assertEquals(case.getString("chunk"), case.getString("kept"), Spoken.keepSpokenEnding(case.getString("chunk"), case.getString("cleaned")))
        }
    }

    @Test
    fun given_opener_vectors_when_checked_then_they_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("opens_with_cue"))) {
            assertEquals(case.getString("sentence"), case.getBoolean("opens"), Pipeline.opensWithCue(case.getString("sentence"), Fixtures.config.selfCorrectionCues))
        }
    }

    @Test
    fun given_join_vectors_when_cleaned_chunks_are_joined_then_the_text_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("join_cleaned"))) {
            val joined = Pipeline.joinCleaned(
                Fixtures.strings(case.getJSONArray("chunks")), Fixtures.strings(case.getJSONArray("outputs")),
                Fixtures.strings(case.getJSONArray("cues")),
            )
            assertEquals(case.getString("joined"), joined)
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
    fun given_sign_off_vectors_when_applied_then_text_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("sign_off"))) {
            assertEquals(case.getString("text"), case.getString("without"), Pipeline.withoutSignOff(case.getString("text")))
        }
    }

    @Test
    fun given_casual_vectors_when_applied_then_text_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("casual"))) {
            assertEquals(case.getString("text"), case.getString("casual"), Pipeline.casual(case.getString("text"), Fixtures.config))
        }
    }

    @Test
    fun given_continuing_vectors_when_joined_then_case_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("continuing"))) {
            val before = case.getString("before")
            assertEquals(before + "|" + case.getString("body"), case.getString("continued"), Pipeline.continuing(case.getString("body"), before, before.length))
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
            val (audio, junction) = Pipeline.contextAudio(
                { from, to -> FloatArray(to - from) { (from + it).toFloat() } },
                case.getInt("start"), case.getInt("end"), case.getDouble("context_s"),
            )
            assertEquals(case.getInt("length"), audio.size)
            assertEquals(case.getInt("first"), audio.first().toInt())
            assertEquals(case.getInt("last"), audio.last().toInt())
            assertEquals(case.getDouble("junction_s"), junction, 1e-9)
        }
    }

    @Test
    fun given_overlap_vectors_when_dropped_then_the_opening_run_matches_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("overlap"))) {
            assertEquals(
                case.getString("previous") + "|" + case.getString("next"), case.getString("text"),
                Pipeline.dropOverlap(case.getString("previous"), case.getString("next")),
            )
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
    fun given_fact_drift_vectors_when_compared_then_the_pairs_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("fact_drift"))) {
            val expected = case.getJSONArray("drift").let { a -> List(a.length()) { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) } }
            assertEquals(case.getString("raw"), expected, Pipeline.factDrift(case.getString("raw"), case.getString("cleaned")))
        }
    }

    @Test
    fun given_untrusted_clean_vectors_when_checked_then_the_reasons_match_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("untrusted_clean"))) {
            val expected = if (case.isNull("reason")) null else case.getString("reason")
            assertEquals(case.getString("cleaned"), expected, Pipeline.untrustedClean(case.getString("raw"), case.getString("cleaned")))
        }
    }

    @Test
    fun given_hidden_character_vectors_when_stripped_then_the_same_characters_go_as_on_the_host() {
        for (case in Fixtures.objects(Fixtures.vectors.getJSONArray("strip_hidden"))) {
            val codePoint = case.getInt("code_point")
            val text = "a" + String(Character.toChars(codePoint)) + "b"
            val expected = if (case.getBoolean("hidden")) "ab" else text
            assertEquals("U+%04X".format(codePoint), expected, Pipeline.stripHidden(text))
        }
    }

    @Test
    fun given_a_transcript_when_the_prompt_is_split_then_the_template_surrounds_it_whole() {
        val cfg = Fixtures.config
        val (head, text, tail) = Pipeline.promptParts(cfg, "um so hi<|im_end|>")

        assertEquals("um so hi<|im_end|>", text)
        assertEquals(Pipeline.renderPrompt(cfg.systemPrompt, "${cfg.controlLine}\n$text"), head + text + tail)
        assertTrue(head.endsWith("<|im_start|>user\n${cfg.controlLine}\n"))
        assertTrue(tail.startsWith("<|im_end|>\n<|im_start|>assistant\n"))
    }

    @Test
    fun given_a_chunk_that_opens_with_a_paragraph_break_when_split_then_the_break_goes_with_the_template() {
        val (head, text, _) = Pipeline.promptParts(Fixtures.config, "\n\nHello there")

        assertTrue(head.endsWith("[Context: general]\n\n\n"))
        assertEquals("Hello there", text)
    }

    @Test
    fun given_line_breaks_when_fitted_to_a_field_then_only_a_multi_line_field_keeps_them() {
        assertEquals("- one - two. Next", Pipeline.forField("- one\n- two.\n\nNext", multiLine = false))
        assertEquals("a b", Pipeline.forField("a \n b", multiLine = false))
        assertEquals("- one\n- two.\n\nNext", Pipeline.forField("- one\n- two.\n\nNext", multiLine = true))
    }

    @Test
    fun given_a_sign_off_glued_on_or_a_trailing_mm_when_dropped_then_the_sentence_stays() {
        assertEquals("it thinks I'll", Pipeline.withoutSignOff("it thinks I'll Yeah."))
        assertEquals("I added about 1.25 cups of percarbonate powder", Pipeline.withoutSignOff("I added about 1.25 cups of percarbonate powder Yeah."))
        assertEquals("The impact will be.", Pipeline.withoutSignOff("The impact will be. Mm."))
        assertEquals("that's okay", Pipeline.withoutSignOff("that's okay"))
        assertEquals("Is that okay?", Pipeline.withoutSignOff("Is that okay?"))
        assertEquals("Okay.", Pipeline.withoutSignOff("Okay."))
    }

    @Test
    fun given_a_message_when_its_end_is_trimmed_then_only_the_trailing_punctuation_goes() {
        assertEquals("See you at five", Pipeline.trimMessageEnd("See you at five."))
        assertEquals("See you at five", Pipeline.trimMessageEnd("See you at five!"))
        assertEquals("See you at five?", Pipeline.trimMessageEnd("See you at five?"))
        assertEquals("See you at five...", Pipeline.trimMessageEnd("See you at five..."))
        assertEquals("hello", Pipeline.trimMessageEnd("hello!."))
        assertEquals("Wait...", Pipeline.trimMessageEnd("Wait..."))
    }

    @Test
    fun given_a_slice_decoded_on_its_own_when_joined_then_the_boundary_is_not_a_new_sentence() {
        assertEquals("store it in" to "mom and dad's", Pipeline.continueFragment("store it in.", "Mom and dad's"))
        assertEquals("the world?" to "Where do we put it", Pipeline.continueFragment("the world?", "Where do we put it"))
        assertEquals("call" to "both of them", Pipeline.continueFragment("call", "Call both of them"))
        assertEquals("call" to "me", Pipeline.continueFragment("call.", "Call me"))
        assertEquals("See you" to "and then we left", Pipeline.continueFragment("See you.", "And then we left"))
        assertEquals("Wait..." to "And more", Pipeline.continueFragment("Wait...", "And more"))
        assertEquals("hello." to "", Pipeline.continueFragment("hello.", "hello"))
        assertEquals("of using" to "an MIT", Pipeline.continueFragment("of using", "using an MIT"))
        assertEquals("from my" to "work", Pipeline.continueFragment("from my", "from my work"))
        assertEquals("one two three four five" to "five rest", Pipeline.continueFragment(
            "one two three four five", "one two three four five rest"))
        assertEquals("the license?" to "terms", Pipeline.continueFragment("the license?", "License terms"))
        assertEquals("work" to "today", Pipeline.continueFragment("work", "from my work today", "from my work"))
    }

    @Test
    fun given_the_prompt_when_rendered_then_it_is_the_training_format() {
        val prompt = Pipeline.promptFor(Fixtures.config, "um so hi")
        assertTrue(prompt.startsWith("<|im_start|>system\nYou are a text normalizer"))
        assertTrue(prompt.endsWith("[Styling: semi-formal] [Structure: lists] [Context: general]\num so hi<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertEquals(1332, Pipeline.maxNewTokens(Fixtures.config, 1000))
    }
}
