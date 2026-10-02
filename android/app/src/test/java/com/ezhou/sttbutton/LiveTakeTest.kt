package com.ezhou.sttbutton

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTakeTest {
    private class ScriptedRecognizer(vararg texts: String) : SliceRecognizer {
        private val queue = ArrayDeque(texts.toList())
        override fun recognize(samples: FloatArray) = queue.removeFirst()
        override fun recognizeAfter(samples: FloatArray, junctionS: Double) = "." to queue.removeFirst()
    }

    private class UppercaseCleaner(private val onClean: (String) -> Unit = {}) : ChunkCleaner {
        val calls = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var cancelled = false
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(chunk: String): ChunkResult? {
            calls += chunk
            cancelled = false
            onClean(chunk)
            if (cancelled) return null
            completed += chunk
            return ChunkResult(chunk.uppercase(), 1)
        }
        override fun cancel() {
            cancelled = true
        }
    }

    private val cfg = PipelineConfig(org.json.JSONObject(Fixtures.resource("pipeline.json")).apply {
        put("chunk_min_tokens", 15)
        put("skip_max_words", 5)
    })

    private fun labelled(prefix: String, count: Int) =
        (1..count).joinToString(" ") { i -> List(9) { "$prefix$i" }.joinToString(" ") + "." }

    private fun audio(speechS: Double, silenceS: Double): ShortArray = Fixtures.signal(JSONArray(
        """[["silence",0.3],["noise",$speechS,8000],["silence",$silenceS]]"""))

    private fun tone(seconds: Int) = ShortArray(seconds * Slicer.SAMPLE_RATE) { (9000 * Math.sin(2 * Math.PI * 220 * it / 16000)).toInt().toShort() }

    @Test
    fun given_a_three_slice_take_when_run_then_chunks_clean_before_the_stop_and_join_with_blank_lines() {
        val cleaner = UppercaseCleaner()
        val slices = listOf(labelled("a", 3), labelled("b", 3), labelled("c", 1))
        val take = LiveTake(ScriptedRecognizer(*slices.toTypedArray()), { cleaner }, cfg)

        take.addSamples(tone(65))
        while (take.step()) Unit
        assertEquals(2, cleaner.calls.size)
        take.markStop()
        val result = take.finish()

        assertEquals(4, cleaner.calls.size)
        assertEquals(4, result.chunks)
        assertEquals("chunks", result.cleanMode)
        assertEquals(cleaner.calls.joinToString("\n\n") { it.uppercase() }, result.cleaned)
        assertEquals(slices.joinToString(" "), result.raw)
    }

    @Test
    fun given_a_five_word_take_when_finished_then_it_is_raw_and_the_cleaner_is_never_asked_for() {
        val take = LiveTake(ScriptedRecognizer("Meet at noon, okay."), { error("the cleaner must not be touched") }, cfg)
        take.addSamples(tone(3))
        val result = take.finish()

        assertEquals("Meet at noon okay", result.cleaned)
        assertEquals(0, result.chunks)
        assertEquals("raw", result.cleanMode)
    }

    @Test
    fun given_a_short_plain_take_when_run_then_the_cleaner_is_never_prepared() {
        val triggers = mutableListOf<String>()
        val take = LiveTake(ScriptedRecognizer("Meet at noon."), { error("not needed") }, cfg, prepareCleaner = { triggers += it })
        take.addSamples(tone(3))
        take.finish()

        assertTrue(triggers.isEmpty())
    }

    @Test
    fun given_audio_past_the_load_threshold_when_fed_then_the_cleaner_is_prepared_once_by_time() {
        val triggers = mutableListOf<String>()
        val take = LiveTake(ScriptedRecognizer("Meet at noon."), { error("not needed") }, cfg, prepareCleaner = { triggers += it })
        take.addSamples(tone(5))
        assertTrue(triggers.isEmpty())
        take.addSamples(tone(2))
        take.addSamples(tone(2))

        assertEquals(listOf("time"), triggers)
    }

    @Test
    fun given_a_cue_in_a_landed_slice_when_stepped_then_the_cleaner_is_prepared_by_text() {
        val triggers = mutableListOf<String>()
        val cleaner = UppercaseCleaner()
        val take = LiveTake(ScriptedRecognizer("Tuesday actually Wednesday."), { cleaner }, cfg, prepareCleaner = { triggers += it })
        take.addSamples(audio(1.5, 2.0))
        take.step()

        assertEquals(listOf("text"), triggers)
    }

    private fun feedSegment(take: LiveTake, speechS: Double, silenceS: Double) {
        val chunk = audio(speechS, silenceS)
        for (at in chunk.indices step Slicer.SAMPLE_RATE / 10) take.addSamples(chunk.copyOfRange(at, minOf(chunk.size, at + Slicer.SAMPLE_RATE / 10)))
    }

    @Test
    fun given_a_one_second_pause_when_checked_then_no_segment_is_due_but_two_seconds_make_one() {
        val take = LiveTake(ScriptedRecognizer("Okay sounds good", "unused"), { UppercaseCleaner() }, cfg)
        feedSegment(take, 1.5, 1.0)
        assertFalse(take.segmentDue())
        feedSegment(take, 0.0, 1.2)
        assertTrue(take.segmentDue())
    }

    @Test
    fun given_two_segments_when_finished_then_each_is_judged_on_the_take_so_far_and_cleaned_on_its_own() {
        val cleaner = UppercaseCleaner()
        val take = LiveTake(ScriptedRecognizer("Okay, sounds good.", "and bring the cake please thanks."), { cleaner }, cfg)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        assertTrue(take.segmentDue())
        val first = take.finishSegment()

        assertEquals("raw", first.cleanMode)
        assertEquals("Okay sounds good", first.cleaned)
        assertFalse(take.segmentDue())

        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        assertTrue(take.segmentDue())
        val second = take.finishSegment()

        assertEquals("chunks", second.cleanMode)
        assertEquals(listOf("and bring the cake please thanks."), cleaner.calls)
        assertEquals("AND BRING THE CAKE PLEASE THANKS", second.cleaned)
    }

    @Test
    fun given_a_segment_ended_by_a_pause_when_the_take_stops_then_silence_alone_is_not_recognized() {
        val take = LiveTake(ScriptedRecognizer("Okay sounds good"), { UppercaseCleaner() }, cfg)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        take.finishSegment()
        feedSegment(take, 0.0, 1.0)
        val last = take.finish()

        assertEquals("", last.cleaned)
    }

    @Test
    fun given_an_earlier_cue_when_a_later_segment_is_finished_then_it_is_cleaned_even_though_it_is_short() {
        val cleaner = UppercaseCleaner()
        val take = LiveTake(ScriptedRecognizer("meet Tuesday actually", "no Wednesday"), { cleaner }, cfg)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        take.finishSegment()
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        val second = take.finishSegment()

        assertEquals("chunks", second.cleanMode)
        assertEquals("actually", second.cue)
    }

    private fun pausedTake(cleaner: UppercaseCleaner, vararg texts: String): LiveTake {
        val take = LiveTake(ScriptedRecognizer(*texts), { cleaner }, cfg)
        take.addSamples(audio(3.0, 3.0))
        take.step()
        return take
    }

    @Test
    fun given_a_pause_when_idle_then_the_stop_finds_the_remainder_already_cleaned() {
        val cleaner = UppercaseCleaner()
        val take = pausedTake(cleaner, labelled("a", 3), "")
        assertEquals(1, cleaner.calls.size)

        assertTrue(take.step())
        take.markStop()
        val result = take.finish()

        assertEquals(2, cleaner.calls.size)
        assertEquals(2, result.chunks)
        assertEquals(1, result.idleCleans)
        assertEquals(1, result.idleHits)
        assertEquals(cleaner.calls.joinToString("\n\n") { it.uppercase() }, result.cleaned)
    }

    @Test
    fun given_speech_during_an_idle_clean_when_added_then_it_is_cancelled_and_the_text_is_unchanged() {
        lateinit var take: LiveTake
        val cleaner = UppercaseCleaner { chunk ->
            if (chunk.startsWith("a3")) take.addSamples(audio(1.0, 0.0))
        }
        take = pausedTake(cleaner, labelled("a", 3), labelled("b", 1))

        take.step()
        assertEquals(1, cleaner.completed.size)
        take.markStop()
        val result = take.finish()

        assertEquals(labelled("a", 3) + " " + labelled("b", 1), result.raw)
        assertEquals(0, result.idleHits)
        assertEquals(result.chunks - 1, Regex("\n\n").findAll(result.cleaned).count())
        assertEquals(cleaner.completed.size, cleaner.completed.toSet().size)
    }

    @Test
    fun given_everything_cleaned_when_idle_again_then_the_cleaner_is_not_called() {
        val cleaner = UppercaseCleaner()
        val take = pausedTake(cleaner, labelled("a", 3), "")
        take.step()
        val calls = cleaner.calls.size

        assertFalse(take.step())
        assertEquals(calls, cleaner.calls.size)
    }

    @Test
    fun given_a_short_take_when_idle_then_nothing_is_cleaned() {
        val cleaner = UppercaseCleaner()
        val take = pausedTake(cleaner, "Meet at noon, okay.", "")
        assertFalse(take.step())
        assertTrue(cleaner.calls.isEmpty())
    }

    private class MarkedRecognizer(private val first: String, vararg after: Pair<String, String>) : SliceRecognizer {
        private val queue = ArrayDeque(after.toList())
        override fun recognize(samples: FloatArray) = first
        override fun recognizeAfter(samples: FloatArray, junctionS: Double) = queue.removeFirst()
    }

    private class SentenceCleaner : ChunkCleaner {
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(chunk: String) = ChunkResult(chunk.replaceFirstChar { it.uppercase() }.removeSuffix(".") + ".", 1)
        override fun cancel() = Unit
    }

    private val longCasualCfg = PipelineConfig(org.json.JSONObject(Fixtures.resource("pipeline.json")).apply { put("skip_max_words", 40) })

    private fun segments(recognizer: SliceRecognizer, config: PipelineConfig, cleaner: ChunkCleaner = SentenceCleaner()): Pair<LiveResult, LiveResult> {
        val take = LiveTake(recognizer, { cleaner }, config)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        val first = take.finishSegment()
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        return first to take.finishSegment()
    }

    @Test
    fun given_a_mid_sentence_pause_when_the_next_segment_is_decoded_then_it_continues_in_lower_case_with_no_period() {
        val (first, second) = segments(MarkedRecognizer("Update the plan in this project to.", "" to "have some monitoring"), longCasualCfg)

        assertEquals("Update the plan in this project to", first.cleaned)
        assertEquals("have some monitoring", second.cleaned)
        assertEquals(" ", second.prefix)
    }

    @Test
    fun given_a_pause_that_ended_a_sentence_when_the_next_segment_is_decoded_then_the_period_comes_back_before_it() {
        val (_, second) = segments(MarkedRecognizer("Update the plan.", "." to "Have some monitoring"), longCasualCfg)

        assertEquals(". ", second.prefix)
        assertEquals("Have some monitoring", second.cleaned)
    }

    @Test
    fun given_a_withheld_question_mark_when_the_context_decode_gives_a_full_stop_then_the_question_mark_comes_back() {
        val (_, second) = segments(MarkedRecognizer("Is it ready?", "." to "Then ship it"), longCasualCfg)

        assertEquals("? ", second.prefix)
    }

    @Test
    fun given_a_withheld_question_mark_when_the_context_decode_continues_the_sentence_then_no_mark_comes_back() {
        val (_, second) = segments(MarkedRecognizer("Is it ready?", "" to "or still building"), longCasualCfg)

        assertEquals(" ", second.prefix)
    }

    @Test
    fun given_a_comma_after_the_pause_when_the_take_is_casual_then_the_comma_is_dropped() {
        val (_, second) = segments(MarkedRecognizer("Update the plan", "," to "and then"), longCasualCfg)

        assertEquals(" ", second.prefix)
    }

    @Test
    fun given_a_cleaned_segment_when_a_pause_ends_it_then_its_period_is_withheld_and_the_next_one_continues_in_lower_case() {
        val (first, second) = segments(MarkedRecognizer("Update the plan in this project to.", "" to "have some monitoring now please"), cfg)

        assertEquals("Update the plan in this project to", first.cleaned)
        assertEquals("have some monitoring now please", second.cleaned)
        assertEquals(" ", second.prefix)
    }

    @Test
    fun given_a_withheld_question_mark_when_the_take_stops_in_silence_then_the_mark_is_inserted_alone() {
        val take = LiveTake(MarkedRecognizer("Is it ready?"), { SentenceCleaner() }, longCasualCfg)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        assertEquals("Is it ready", take.finishSegment().cleaned.removeSuffix("?"))
        feedSegment(take, 0.0, 1.0)
        val last = take.finish()

        assertEquals("", last.cleaned)
        assertEquals("?", last.prefix)
    }

    @Test
    fun given_a_withheld_period_in_a_long_take_when_the_take_stops_in_silence_then_the_period_is_inserted_alone() {
        val take = LiveTake(MarkedRecognizer("Update the plan in this project to."), { SentenceCleaner() }, cfg)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        take.finishSegment()
        feedSegment(take, 0.0, 1.0)
        val last = take.finish()

        assertEquals(".", last.prefix)
    }

    @Test
    fun given_a_short_take_when_a_pause_ends_it_then_nothing_is_withheld_and_a_silent_stop_inserts_nothing() {
        val take = LiveTake(MarkedRecognizer("Update the plan."), { SentenceCleaner() }, longCasualCfg)
        feedSegment(take, 1.5, 2.5)
        while (take.step()) Unit
        assertEquals("Update the plan", take.finishSegment().cleaned)
        feedSegment(take, 0.0, 1.0)

        assertEquals("", take.finish().prefix)
    }

    @Test
    fun given_a_context_decode_with_no_words_when_decoded_then_the_slice_is_recognized_plainly_and_counted() {
        val recognizer = object : SliceRecognizer {
            private var plain = 0
            override fun recognize(samples: FloatArray) = if (plain++ == 0) "Update the plan" else "plain fallback"
            override fun recognizeAfter(samples: FloatArray, junctionS: Double) = "" to ""
        }
        val (_, second) = segments(recognizer, longCasualCfg)

        assertEquals("plain fallback", second.cleaned)
        assertEquals(1, second.contextFallbacks)
    }
}
