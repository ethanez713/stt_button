package com.ezhou.sttbutton

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal: one engine replaces the earlier take class without changing what any take inserts.
 * Pass: for every take script in `pipeline_vectors.json`, the engine over a `BufferDocument` produces the
 * vector's `raw`, `cleaned`, `clean_mode` and `chunks` exactly.
 * Fail: any difference in any script; the engine is then wrong, not the vector, because the vector is the
 * host library's output and desktop dictation runs on it.
 */
class TakeEngineTest {
    private class ScriptedRecognizer(texts: List<Pair<String, String>>) : Recognizer {
        constructor(vararg texts: String) : this(texts.map { it to "." })

        private val queue = ArrayDeque(texts)
        override fun recognize(request: RecognizeRequest): Recognized {
            val (text, mark) = queue.removeFirst()
            return Recognized(text, if (request.junctionS == null) null else mark)
        }
    }

    private class MarkingCleaner(
        private val replacements: List<Pair<String, String>> = emptyList(),
        private val onClean: (String) -> Unit = {},
    ) : Cleaner {
        val calls = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var armed: (() -> Unit)? = null
        @Volatile var cancelled = false

        override fun countTokens(text: String) = Pipeline.wordCount(text)

        override fun clean(request: CleanRequest): CleanResult? {
            cancelled = false
            armed?.also { armed = null }?.invoke()
            calls += request.target
            onClean(request.target)
            if (cancelled) return null
            completed += request.target
            var text = request.target.uppercase()
            for ((find, replace) in replacements) text = text.replace(find, replace)
            return CleanResult(text, 1)
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class Rig(
        val cfg: PipelineConfig,
        recognizer: Recognizer,
        val cleaner: Cleaner = MarkingCleaner(),
        val doc: BufferDocument = BufferDocument(),
        val slicer: Slicer = Slicer.fromConfig(cfg),
        prepare: (String) -> Unit = {},
        pauseOpen: Boolean = true,
    ) {
        val records = mutableListOf<JobRecord>()
        val policy = GatedPolicy(ChunkPolicy(cfg)).also { it.pauseOpen = pauseOpen }
        val engine = TakeEngine(doc, slicer, recognizer, cleaner, policy, cfg, jobs = { records += it }, prepareCleaner = prepare)
        val driver = SerialDriver(engine)

        fun feed(samples: ShortArray) {
            slicer.feed(samples)
            engine.onAudio()
            driver.drain()
        }

        fun feedInPieces(samples: ShortArray) {
            for (at in samples.indices step Slicer.SAMPLE_RATE / 10) feed(samples.copyOfRange(at, minOf(samples.size, at + Slicer.SAMPLE_RATE / 10)))
        }

        fun finish(): LiveResult {
            engine.markStop()
            return engine.finish()
        }
    }

    private val cfg = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).apply {
        put("chunk_min_tokens", 15)
        put("skip_max_words", 5)
    })

    private fun labelled(prefix: String, count: Int) =
        (1..count).joinToString(" ") { i -> List(9) { "$prefix$i" }.joinToString(" ") + "." }

    private fun audio(speechS: Double, silenceS: Double): ShortArray = Fixtures.signal(JSONArray(
        """[["silence",0.3],["noise",$speechS,8000],["silence",$silenceS]]"""))

    private fun tone(seconds: Int) = ShortArray(seconds * Slicer.SAMPLE_RATE) { (9000 * Math.sin(2 * Math.PI * 220 * it / 16000)).toInt().toShort() }

    private fun scriptConfig(overrides: JSONObject) = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).also { base ->
        overrides.keys().forEach { base.put(it, overrides.get(it)) }
    })

    private fun runScript(script: JSONObject): LiveResult {
        val config = scriptConfig(script.getJSONObject("cfg"))
        val replacements = Fixtures.objects(JSONArray().also { }).let { script.optJSONArray("cleaner") }
            ?.let { arr -> List(arr.length()) { arr.getJSONArray(it).getString(0) to arr.getJSONArray(it).getString(1) } }.orEmpty()
        val slices = script.getJSONArray("slices").let { arr -> List(arr.length()) { arr.getJSONArray(it).getString(0) to arr.getJSONArray(it).getString(1) } }
        val cleaner = MarkingCleaner(replacements)
        val rig = Rig(config, ScriptedRecognizer(slices), cleaner, pauseOpen = false)
        val steps = script.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONArray(i)
            val signal = Fixtures.signal(step.getJSONArray(1))
            if (step.getString(0) == "audio") {
                rig.feedInPieces(signal)
            } else {
                rig.policy.pauseOpen = true
                cleaner.armed = { rig.slicer.feed(signal); rig.engine.onAudio() }
                rig.driver.drain()
                rig.policy.pauseOpen = false
            }
        }
        return rig.finish()
    }

    @Test
    fun given_every_take_script_when_run_then_the_engine_inserts_what_the_host_library_does() {
        val scripts = Fixtures.objects(Fixtures.vectors.getJSONArray("takes"))
        assertEquals(9, scripts.size)
        for (script in scripts) {
            val expected = script.getJSONObject("result")
            val result = runScript(script)
            val name = script.getString("name")
            assertEquals("$name raw", expected.getString("raw"), result.raw)
            assertEquals("$name cleaned", expected.getString("cleaned"), Pipeline.casual(result.cleaned, scriptConfig(script.getJSONObject("cfg"))))
            assertEquals("$name clean_mode", expected.getString("clean_mode"), result.cleanMode)
            assertEquals("$name chunks", expected.getInt("chunks"), result.chunks)
        }
    }

    @Test
    fun given_a_three_slice_take_when_run_then_chunks_clean_before_the_stop_and_join_with_blank_lines() {
        val cleaner = MarkingCleaner()
        val slices = listOf(labelled("a", 3), labelled("b", 3), labelled("c", 1))
        val rig = Rig(cfg, ScriptedRecognizer(*slices.toTypedArray()), cleaner)

        rig.feed(tone(65))
        assertEquals(2, cleaner.calls.size)
        val result = rig.finish()

        assertEquals(4, cleaner.calls.size)
        assertEquals(4, result.chunks)
        assertEquals("chunks", result.cleanMode)
        assertEquals(cleaner.calls.joinToString("\n\n") { it.uppercase() }, result.cleaned)
        assertEquals(slices.joinToString(" "), result.raw)
    }

    @Test
    fun given_a_clean_that_swaps_a_model_name_when_finished_then_the_chunk_is_inserted_as_heard() {
        val cleaner = MarkingCleaner(listOf("S20" to "S25"))
        val rig = Rig(cfg, ScriptedRecognizer("Put it on the S20 Ultra please now"), cleaner)
        rig.feed(tone(3))
        val result = rig.finish()

        assertEquals("Put it on the S20 Ultra please now", result.cleaned)
        assertEquals(1, result.drifts)
        assertEquals("fallback", rig.records.last { it.kind == "clean" }.outcome)
    }

    @Test
    fun given_speech_then_silence_when_asked_then_the_silence_is_measured_from_the_last_speech() {
        val rig = Rig(cfg, ScriptedRecognizer("Okay sounds good", "unused"))
        assertEquals(0.0, rig.engine.silenceS(), 1e-9)

        rig.feedInPieces(audio(1.5, 4.0))

        assertEquals(4.0, rig.engine.silenceS(), 0.15)
    }

    @Test
    fun given_room_noise_below_the_stop_level_after_speech_when_asked_then_it_counts_as_silence() {
        val rig = Rig(cfg, ScriptedRecognizer("Okay sounds good", "unused"))

        rig.feedInPieces(Fixtures.signal(JSONArray("""[["silence",0.3],["noise",1.5,8000],["noise",4.0,100]]""")))

        assertEquals(4.0, rig.engine.silenceS(), 0.15)
    }

    @Test
    fun given_noise_above_the_stop_level_after_speech_when_asked_then_the_silence_restarts() {
        val rig = Rig(cfg, ScriptedRecognizer("Okay sounds good", "unused"))

        rig.feedInPieces(Fixtures.signal(JSONArray("""[["silence",0.3],["noise",1.5,8000],["noise",2.0,600],["silence",1.0]]""")))

        assertEquals(1.0, rig.engine.silenceS(), 0.15)
    }

    @Test
    fun given_no_speech_at_all_when_asked_then_the_silence_runs_from_the_start() {
        val rig = Rig(cfg, ScriptedRecognizer("unused"))
        rig.feed(ShortArray(3 * Slicer.SAMPLE_RATE))

        assertEquals(3.0, rig.engine.silenceS(), 1e-9)
    }

    private val untouchable = object : Cleaner {
        override fun countTokens(text: String): Int = error("the cleaner must not be touched")
        override fun clean(request: CleanRequest): CleanResult? = error("the cleaner must not be touched")
        override fun cancel() = Unit
    }

    @Test
    fun given_a_five_word_take_when_finished_then_it_is_raw_and_the_cleaner_is_never_asked_for() {
        val rig = Rig(cfg, ScriptedRecognizer("Meet at noon, okay."), untouchable)
        rig.feed(tone(3))
        val result = rig.finish()

        assertEquals("Meet at noon, okay.", result.cleaned)
        assertEquals(0, result.chunks)
        assertEquals("raw", result.cleanMode)
    }

    @Test
    fun given_a_short_plain_take_when_run_then_the_cleaner_is_never_prepared() {
        val triggers = mutableListOf<String>()
        val rig = Rig(cfg, ScriptedRecognizer("Meet at noon."), untouchable, prepare = { triggers += it })
        rig.feed(tone(3))
        rig.finish()

        assertTrue(triggers.isEmpty())
    }

    @Test
    fun given_audio_past_the_load_threshold_when_fed_then_the_cleaner_is_prepared_once_by_time() {
        val triggers = mutableListOf<String>()
        val rig = Rig(cfg, ScriptedRecognizer("Meet at noon."), untouchable, prepare = { triggers += it })
        rig.feed(tone(5))
        assertTrue(triggers.isEmpty())
        rig.feed(tone(2))
        rig.feed(tone(2))

        assertEquals(listOf("time"), triggers)
    }

    @Test
    fun given_a_cue_in_a_landed_slice_when_decoded_then_the_cleaner_is_prepared_by_text() {
        val triggers = mutableListOf<String>()
        val rig = Rig(cfg, ScriptedRecognizer("Tuesday actually Wednesday."), prepare = { triggers += it })
        rig.feed(audio(1.5, 2.0))

        assertEquals(listOf("text"), triggers)
    }

    private fun pausedRig(cleaner: MarkingCleaner, vararg texts: String): Rig {
        val rig = Rig(cfg, ScriptedRecognizer(*texts), cleaner, pauseOpen = false)
        rig.feed(audio(3.0, 3.0))
        return rig
    }

    @Test
    fun given_a_pause_when_idle_then_the_stop_finds_the_remainder_already_cleaned() {
        val cleaner = MarkingCleaner()
        val rig = pausedRig(cleaner, labelled("a", 3), "")
        assertEquals(1, cleaner.calls.size)

        rig.policy.pauseOpen = true
        assertTrue(rig.driver.drain() > 0)
        val result = rig.finish()

        assertEquals(2, cleaner.calls.size)
        assertEquals(2, result.chunks)
        assertEquals(1, result.idleCleans)
        assertEquals(1, result.idleHits)
        assertEquals(cleaner.calls.joinToString("\n\n") { it.uppercase() }, result.cleaned)
    }

    @Test
    fun given_speech_during_an_idle_clean_when_added_then_it_is_cancelled_and_the_text_is_unchanged() {
        lateinit var rig: Rig
        val cleaner = MarkingCleaner { chunk ->
            if (chunk.startsWith("a3")) {
                rig.slicer.feed(audio(1.0, 0.0))
                rig.engine.onAudio()
            }
        }
        rig = pausedRig(cleaner, labelled("a", 3), labelled("b", 1))

        rig.policy.pauseOpen = true
        rig.driver.drain()
        assertEquals(1, cleaner.completed.size)
        val result = rig.finish()

        assertEquals(labelled("a", 3) + " " + labelled("b", 1), result.raw)
        assertEquals(0, result.idleHits)
        assertEquals(result.chunks - 1, Regex("\n\n").findAll(result.cleaned).count())
        assertEquals(cleaner.completed.size, cleaner.completed.toSet().size)
        assertEquals(listOf("applied", "abandoned"), rig.records.filter { it.kind == "clean" }.take(2).map { it.outcome })
    }

    @Test
    fun given_everything_cleaned_when_idle_again_then_the_cleaner_is_not_called() {
        val cleaner = MarkingCleaner()
        val rig = pausedRig(cleaner, labelled("a", 3), "")
        rig.policy.pauseOpen = true
        rig.driver.drain()
        val calls = cleaner.calls.size

        assertNull(rig.engine.next())
        assertEquals(calls, cleaner.calls.size)
    }

    @Test
    fun given_a_short_take_when_idle_then_nothing_is_cleaned() {
        val cleaner = MarkingCleaner()
        val rig = pausedRig(cleaner, "Meet at noon, okay.", "")
        rig.policy.pauseOpen = true

        assertNull(rig.engine.next())
        assertTrue(cleaner.calls.isEmpty())
    }

    @Test
    fun given_a_twelve_word_take_when_finished_then_the_period_stays_unless_the_text_is_casual() {
        val heard = "one, two three four five six seven eight nine ten eleven twelve."
        val rig = Rig(Fixtures.config, ScriptedRecognizer(heard), untouchable)
        rig.feed(tone(3))
        val result = rig.finish()

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
        val rig = Rig(cfg, recognizer)
        rig.feedInPieces(audio(2.5, 2.0))
        rig.feedInPieces(audio(2.5, 2.0))
        val result = rig.finish()

        assertTrue(result.raw.contains("plain fallback"))
        assertEquals(1, result.contextFallbacks)
        assertTrue(rig.records.any { it.kind == "asr" && it.outcome == "fallback" })
    }

    private fun oneSliceRig(cleaner: MarkingCleaner = MarkingCleaner(), text: String = labelled("a", 3)): Rig {
        val rig = Rig(cfg, ScriptedRecognizer(text), cleaner)
        rig.slicer.feed(audio(3.0, 0.0))
        rig.engine.stop()
        return rig
    }

    @Test
    fun given_an_edit_inside_the_flight_when_the_clean_completes_then_it_is_dropped_and_the_edited_text_is_cleaned_next() {
        val cleaner = MarkingCleaner()
        val rig = oneSliceRig(cleaner)
        val decode = rig.engine.next()!!
        rig.engine.complete(decode, decode.run())
        val job = rig.engine.next()!!

        rig.doc.userEdit(0, 2, "xx")
        rig.engine.complete(job, job.run())
        rig.driver.drain()
        val result = rig.engine.result()

        assertEquals(listOf("dropped", "applied", "applied"), rig.records.filter { it.kind == "clean" }.map { it.outcome })
        assertTrue(result.cleaned, result.cleaned.startsWith("XX A1"))
    }

    @Test
    fun given_an_edit_inside_a_cleaned_stretch_when_finished_then_it_survives() {
        val rig = oneSliceRig(text = labelled("a", 3))
        val decode = rig.engine.next()!!
        rig.engine.complete(decode, decode.run())
        val clean = rig.engine.next()!!
        rig.engine.complete(clean, clean.run())

        rig.doc.userEdit(0, 2, "Q")
        rig.driver.drain()
        val result = rig.engine.result()

        assertTrue(result.cleaned, result.cleaned.startsWith("Q A1"))
        assertEquals(2, result.chunks)
    }

    @Test
    fun given_text_typed_at_the_end_when_finished_then_it_is_cleaned_with_the_tail() {
        val rig = oneSliceRig(text = labelled("a", 1))
        val decode = rig.engine.next()!!
        rig.engine.complete(decode, decode.run())
        rig.doc.userEdit(rig.doc.read()!!.text.length, rig.doc.read()!!.text.length, " typed words here")
        rig.driver.drain()
        val result = rig.engine.result()

        assertTrue(result.cleaned, result.cleaned.endsWith("TYPED WORDS HERE"))
    }

    @Test
    fun given_a_take_when_run_then_every_model_call_has_a_job_record() {
        val cleaner = MarkingCleaner()
        val rig = Rig(cfg, ScriptedRecognizer(labelled("a", 3), labelled("b", 3), labelled("c", 1)), cleaner)
        rig.feed(tone(65))
        rig.finish()

        assertEquals(3, rig.records.count { it.kind == "asr" })
        assertEquals(cleaner.calls.size, rig.records.count { it.kind == "clean" })
        assertTrue(rig.records.filter { it.kind == "clean" }.all { it.outcome == "applied" && it.input != null && it.outTokens == 1 })
        val decodes = rig.records.filter { it.kind == "asr" }
        assertEquals(0L, decodes.first().fromMs)
        assertEquals(decodes.map { it.toMs }.dropLast(1), decodes.map { it.fromMs }.drop(1))
        assertEquals(65_000L, decodes.last().toMs)
    }

    @Test
    fun given_a_plan_asked_before_the_cleaner_has_loaded_when_run_then_a_load_job_comes_first_and_then_the_same_plan() {
        val loaded = booleanArrayOf(false)
        val cleaner = object : Cleaner {
            override fun countTokens(text: String): Int {
                loaded[0] = true
                return Pipeline.wordCount(text)
            }
            override val tokenCounter: ((String) -> Int)? get() = if (loaded[0]) ::countTokens else null
            override fun clean(request: CleanRequest) = CleanResult(request.target.uppercase(), 1)
            override fun cancel() = Unit
        }
        val rig = Rig(cfg, ScriptedRecognizer(labelled("a", 3)), cleaner)
        rig.slicer.feed(audio(3.0, 0.0))
        rig.engine.stop()
        val decode = rig.engine.next()!!
        rig.engine.complete(decode, decode.run())

        val load = rig.engine.next()!!
        assertEquals(Job.Kind.LOAD, load.kind)
        rig.engine.complete(load, load.run())
        val clean = rig.engine.next()!!

        assertEquals(Job.Kind.CLEAN, clean.kind)
        assertEquals(labelled("a", 2), clean.plan!!.target)
    }

    @Test
    fun given_a_slicer_that_already_holds_two_slices_when_the_engine_is_built_then_both_are_decoded() {
        val slicer = Slicer.fromConfig(cfg)
        slicer.feed(tone(65))
        val rig = Rig(cfg, ScriptedRecognizer(labelled("a", 3), labelled("b", 3)), slicer = slicer)
        rig.driver.drain()

        assertEquals(labelled("a", 3) + " " + labelled("b", 3), rig.engine.raw)
        assertEquals(2, rig.records.count { it.kind == "asr" })
    }

    @Test
    fun given_a_mark_change_at_the_junction_when_decoded_then_the_document_holds_the_settled_mark() {
        val rig = Rig(cfg, ScriptedRecognizer(listOf("Update the plan." to ".", "have some monitoring now" to "?")))
        rig.feedInPieces(audio(2.5, 2.0))
        rig.feedInPieces(audio(2.5, 2.0))

        assertEquals("Update the plan? have some monitoring now", rig.doc.read()!!.text)
    }

    @Test
    fun given_a_policy_name_when_asked_then_the_engine_reports_it() {
        assertEquals("chunk", Rig(cfg, ScriptedRecognizer()).engine.policyName)
        assertFalse(Rig(cfg, ScriptedRecognizer()).engine.stopping)
        assertNotNull(Rig(cfg, ScriptedRecognizer()).engine)
    }
}
