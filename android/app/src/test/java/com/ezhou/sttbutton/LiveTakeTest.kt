package com.ezhou.sttbutton

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTakeTest {
    private class ScriptedRecognizer(vararg texts: String) : Recognizer {
        private val queue = ArrayDeque(texts.toList())
        override fun recognize(request: RecognizeRequest) = Recognized(queue.removeFirst(), if (request.junctionS == null) null else ".")
    }

    private class UppercaseCleaner(private val onClean: (String) -> Unit = {}) : Cleaner {
        val calls = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var cancelled = false
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(request: CleanRequest): CleanResult? {
            val chunk = request.target
            calls += chunk
            cancelled = false
            onClean(chunk)
            if (cancelled) return null
            completed += chunk
            return CleanResult(chunk.uppercase(), 1)
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
    fun given_a_clean_that_swaps_a_model_name_when_finished_then_the_chunk_is_inserted_as_heard() {
        val swapping = object : Cleaner {
            override fun countTokens(text: String) = Pipeline.wordCount(text)
            override fun clean(request: CleanRequest) = CleanResult(request.target.replace("S20", "S25"), 1)
            override fun cancel() = Unit
        }
        val take = LiveTake(ScriptedRecognizer("Put it on the S20 Ultra please now"), { swapping }, cfg)
        take.addSamples(tone(3))
        val result = take.finish()

        assertEquals("Put it on the S20 Ultra please now", result.cleaned)
        assertEquals(1, result.drifts)
    }

    @Test
    fun given_speech_then_silence_when_asked_then_the_silence_is_measured_from_the_last_speech() {
        val take = LiveTake(ScriptedRecognizer("Okay sounds good", "unused"), { UppercaseCleaner() }, cfg)
        assertEquals(0.0, take.silenceS(), 1e-9)

        feed(take, 1.5, 4.0)

        assertEquals(4.0, take.silenceS(), 0.15)
    }

    @Test
    fun given_no_speech_at_all_when_asked_then_the_silence_runs_from_the_start() {
        val take = LiveTake(ScriptedRecognizer("unused"), { UppercaseCleaner() }, cfg)
        take.addSamples(ShortArray(3 * Slicer.SAMPLE_RATE))

        assertEquals(3.0, take.silenceS(), 1e-9)
    }

    @Test
    fun given_a_five_word_take_when_finished_then_it_is_raw_and_the_cleaner_is_never_asked_for() {
        val take = LiveTake(ScriptedRecognizer("Meet at noon, okay."), { error("the cleaner must not be touched") }, cfg)
        take.addSamples(tone(3))
        val result = take.finish()

        assertEquals("Meet at noon, okay.", result.cleaned)
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

    private fun feed(take: LiveTake, speechS: Double, silenceS: Double) {
        val chunk = audio(speechS, silenceS)
        for (at in chunk.indices step Slicer.SAMPLE_RATE / 10) take.addSamples(chunk.copyOfRange(at, minOf(chunk.size, at + Slicer.SAMPLE_RATE / 10)))
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

    @Test
    fun given_a_twelve_word_take_when_finished_then_the_period_stays_unless_the_text_is_casual() {
        val heard = "one, two three four five six seven eight nine ten eleven twelve."
        val take = LiveTake(ScriptedRecognizer(heard), { error("the cleaner must not be touched") }, Fixtures.config)
        take.addSamples(tone(3))
        val result = take.finish()

        assertEquals(12, Pipeline.wordCount(result.cleaned))
        assertEquals(heard, result.cleaned)
        assertEquals("one two three four five six seven eight nine ten eleven twelve", Pipeline.casual(result.cleaned, Fixtures.config))
    }

    @Test
    fun given_a_context_decode_with_no_words_when_decoded_then_the_slice_is_recognized_plainly_and_counted() {
        val recognizer = object : Recognizer {
            private var plain = 0
            override fun recognize(request: RecognizeRequest) =
                if (request.junctionS != null) Recognized("", "") else Recognized(if (plain++ == 0) "Update the plan" else "plain fallback")
        }
        val take = LiveTake(recognizer, { UppercaseCleaner() }, cfg)
        feed(take, 2.5, 2.0)
        feed(take, 2.5, 2.0)
        val result = take.finish()

        assertTrue(result.raw.contains("plain fallback"))
        assertEquals(1, result.contextFallbacks)
    }
}
