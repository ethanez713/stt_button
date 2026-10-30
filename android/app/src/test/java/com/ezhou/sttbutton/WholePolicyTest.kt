package com.ezhou.sttbutton

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WholePolicyTest {
    /** A slice decode returns [slice]; a settle window returns [settled]. */
    private class Heard(
        private val slice: (Int) -> String,
        private val settled: (Int) -> String,
    ) : Recognizer {
        private var slices = 0
        private var windows = 0

        override fun recognize(request: RecognizeRequest): Recognized {
            if (request.settle) {
                windows++
                return Recognized(settled(windows), if (request.junctionS == null) null else ".")
            }
            slices++
            return Recognized(slice(slices), if (request.junctionS == null) null else ".")
        }
    }

    private class Upper(private val onClean: (String) -> Unit = {}) : Cleaner {
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(request: CleanRequest): CleanResult {
            onClean(request.target)
            return CleanResult(request.target.uppercase(), 1)
        }
        override fun cancel() = Unit
    }

    /** Fails if a model clean runs. Token counts stay available for a plan that should not clean. */
    private class Unused : Cleaner {
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(request: CleanRequest): CleanResult = error("cleaned ${request.target}")
        override fun cancel() = Unit
    }

    private class Rig(
        val cfg: PipelineConfig,
        val editor: FakeEditor,
        cleaner: Cleaner,
        recognizer: Recognizer,
        field: Boolean = false,
        prepare: (String) -> Unit = {},
    ) {
        val doc = FieldDocument(editor)
        val records = mutableListOf<JobRecord>()
        val slicer = if (field) Slicer.fromConfigForField(cfg) else Slicer.fromConfig(cfg)
        val engine = TakeEngine(
            doc, slicer, recognizer, cleaner, WholePolicy(cfg), cfg,
            jobs = { records += it }, prepareCleaner = prepare,
        )
        val driver = SerialDriver(engine)

        fun feed(samples: ShortArray) {
            for (at in samples.indices step Slicer.SAMPLE_RATE / 10) {
                slicer.feed(samples.copyOfRange(at, minOf(samples.size, at + Slicer.SAMPLE_RATE / 10)))
                engine.onAudio()
                driver.drain()
            }
        }

        fun finish(): LiveResult {
            engine.markStop()
            return engine.finish()
        }

        val clean get() = records.filter { it.kind == "clean" }
    }

    private fun config(maxTokens: Int = 1000) = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).apply {
        put("chunk_min_tokens", 15)
        put("skip_max_words", 5)
        put("chunk_max_tokens", maxTokens)
    })

    private fun audio(speechS: Double, silenceS: Double): ShortArray = Fixtures.signal(JSONArray(
        """[["silence",0.3],["noise",$speechS,8000],["silence",$silenceS]]"""))

    /** Twelve speech-and-pause cycles, sixty seconds, on the field slicer's cadence. */
    private fun pacedMinute(): ShortArray {
        val parts = (1..12).joinToString(",") { """["noise",3.0,8000],["silence",2.0]""" }
        return Fixtures.signal(JSONArray("[$parts]"))
    }

    private fun sentence(prefix: String) = List(9) { "$prefix$it" }.joinToString(" ") + "."

    private fun labelled(prefix: String, count: Int) =
        (1..count).joinToString(" ") { i -> List(9) { "$prefix$i" }.joinToString(" ") + "." }

    @Test
    fun given_a_field_policy_when_named_then_the_name_is_whole() {
        val cfg = config()
        assertEquals("whole", WholePolicy(cfg).name)
        val rig = Rig(cfg, FakeEditor(""), Upper(), Heard({ "" }, { "" }))
        assertEquals("whole", rig.engine.policyName)
    }

    @Test
    fun given_a_60_s_field_take_with_pauses_when_it_stops_then_one_clean_covers_the_settled_text() {
        val editor = FakeEditor("")
        val prepared = mutableListOf<String>()
        var sawPause = false
        val rig = Rig(
            config(), editor, Upper(),
            Heard(
                slice = { "draft slice $it is only a draft." },
                settled = { "settled window $it has several words today." },
            ),
            field = true, prepare = { prepared += it },
        )

        val audio = pacedMinute()
        assertEquals(60 * Slicer.SAMPLE_RATE, audio.size)
        for (at in audio.indices step Slicer.SAMPLE_RATE / 10) {
            rig.slicer.feed(audio.copyOfRange(at, minOf(audio.size, at + Slicer.SAMPLE_RATE / 10)))
            rig.engine.onAudio()
            rig.driver.drain()
            if (rig.slicer.paused) sawPause = true
        }

        assertTrue(sawPause)
        assertTrue(rig.records.count { it.kind == "asr" } >= 2)
        assertTrue(prepared.contains("text"))
        assertTrue(rig.clean.isEmpty())

        val result = rig.finish()

        val clean = rig.clean.single()
        assertEquals("finish", clean.moment)
        assertEquals("chunk", clean.scope)
        assertEquals("applied", clean.outcome)
        assertTrue(clean.input!!, clean.input!!.contains("settled window"))
        assertFalse(clean.input!!, clean.input!!.contains("draft"))
        assertEquals(clean.input!!.uppercase(), editor.text)
        assertEquals("applied", result.settle)
        assertTrue(result.settleWindows >= 2)
        assertTrue(rig.records.none { it.kind == "clean" && it.moment != "finish" })
    }

    @Test
    fun given_a_take_over_chunk_max_tokens_when_it_finishes_then_the_clean_splits_at_a_sentence_end() {
        val editor = FakeEditor("")
        val first = sentence("a")
        val second = sentence("b")
        val heard = "$first $second"
        val rig = Rig(config(maxTokens = 12), editor, Upper(), Heard({ heard }, { heard }))
        rig.feed(audio(3.0, 0.0))

        rig.finish()

        assertEquals(listOf(first, second), rig.clean.map { it.input })
        assertTrue(rig.clean.all { it.moment == "finish" && it.scope == "chunk" && it.outcome == "applied" })
        assertEquals(first.uppercase() + "\n\n" + second.uppercase(), editor.text)
    }

    @Test
    fun given_a_take_under_the_skip_rule_when_it_finishes_then_only_spoken_convert_is_applied() {
        val editor = FakeEditor("")
        val heard = "see you question mark"
        val rig = Rig(config(), editor, Unused(), Heard({ heard }, { heard }))
        rig.feed(audio(3.0, 0.0))

        val result = rig.finish()

        assertTrue(rig.clean.isEmpty())
        assertEquals(Spoken.convert(heard), editor.text)
        assertEquals("see you?", editor.text)
        assertEquals("raw", result.cleanMode)
        assertEquals(0, result.chunks)
    }

    @Test
    fun given_an_edit_inside_the_region_when_the_take_finishes_then_it_is_not_cleaned() {
        val editor = FakeEditor("")
        val heard = "see you question mark extra words today."
        val rig = Rig(config(), editor, Unused(), Heard({ if (it == 1) heard else "" }, { "settled text should not land here today." }))
        rig.feed(audio(3.0, 3.0))
        assertTrue(editor.text, editor.text.contains("question mark"))
        editor.type(4, 0, "zz")
        rig.doc.sync()

        val result = rig.finish()

        assertTrue(rig.clean.isEmpty())
        assertTrue(editor.text, editor.text.contains("zz"))
        assertTrue(editor.text, editor.text.contains("question mark"))
        assertFalse(editor.text, editor.text.contains("?"))
        assertEquals("edited", result.settle)
        assertTrue(rig.records.none { it.moment == "settle" })
    }

    @Test
    fun given_letters_typed_at_the_end_after_the_stop_when_the_take_finishes_then_they_are_left_as_typed() {
        val editor = FakeEditor("")
        val heard = labelled("a", 3)
        var typed = false
        lateinit var rig: Rig
        val cleaner = Upper { target ->
            if (!typed && target.startsWith("a1") && target.contains("a3")) {
                typed = true
                editor.type(editor.text.length, 0, "po")
                rig.doc.sync()
            }
        }
        rig = Rig(config(), editor, cleaner, Heard({ heard }, { heard }))
        rig.feed(audio(3.0, 0.0))

        rig.finish()

        assertEquals(heard.uppercase() + "po", editor.text)
        assertTrue(rig.clean.none { it.input?.contains("po") == true })
        assertTrue(typed)
    }

    @Test
    fun given_an_edit_during_the_clean_when_it_returns_then_the_field_stays_as_the_user_left_it() {
        val editor = FakeEditor("")
        val heard = labelled("a", 3)
        val rig = Rig(config(), editor, Upper(), Heard({ heard }, { heard }))
        rig.slicer.feed(audio(3.0, 0.0))
        rig.engine.stop()
        while (true) {
            val job = rig.engine.next() ?: break
            if (job.kind == Job.Kind.CLEAN) {
                editor.type(2, 0, "zz")
                rig.doc.sync()
            }
            rig.engine.complete(job, job.run())
        }

        assertTrue(editor.text, editor.text.contains("zz"))
        assertTrue(editor.text, editor.text.contains("a1"))
        assertFalse(editor.text, editor.text.contains("A1"))
        assertEquals("dropped", rig.clean.single().outcome)
        assertEquals("chunk", rig.clean.single().scope)
        assertNull(rig.engine.next())
    }
}
