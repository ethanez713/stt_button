package com.ezhou.sttbutton

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisePolicyTest {
    /** A decode with the speech before it settles the junction with [mark]. */
    private class Heard(vararg texts: String, private val mark: String = ".") : Recognizer {
        private val queue = ArrayDeque(texts.toList())
        override fun recognize(request: RecognizeRequest) = Recognized(queue.removeFirst(), if (request.junctionS == null) null else mark)
    }

    /** Upper-cases; a text of several chunks (which holds a paragraph break) gets ` REVISED` after it. */
    private class Upper(private val onClean: (String) -> Unit = {}) : Cleaner {
        @Volatile var cancelled = false
        val targets = mutableListOf<String>()

        override fun countTokens(text: String) = Pipeline.wordCount(text)

        override fun clean(request: CleanRequest): CleanResult? {
            cancelled = false
            targets += request.target
            onClean(request.target)
            if (cancelled) return null
            return CleanResult(request.target.uppercase() + if (request.target.contains("\n\n")) " REVISED" else "", 1)
        }

        override fun cancel() {
            cancelled = true
        }
    }

    /** Cleans as the cleaner does a short chunk on its own: as a sentence, opening with a capital. */
    private class SentenceCase : Cleaner {
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(request: CleanRequest) = CleanResult(request.target.replaceFirstChar { it.uppercaseChar() }, 1)
        override fun cancel() = Unit
    }

    private class Rig(
        val cfg: PipelineConfig,
        val cleaner: Cleaner,
        val doc: Document,
        recognizer: Recognizer,
        policy: CleanPolicy = RevisePolicy(cfg),
    ) {
        val slicer = Slicer.fromConfig(cfg)
        val records = mutableListOf<JobRecord>()
        val engine = TakeEngine(doc, slicer, recognizer, cleaner, policy, cfg, jobs = { records += it })
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

    private fun config(revise: String = "stop", maxTokens: Int = 1000) = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).apply {
        put("chunk_min_tokens", 15)
        put("skip_max_words", 5)
        put("chunk_max_tokens", maxTokens)
        put("field_revise", revise)
    })

    private fun labelled(prefix: String, count: Int) =
        (1..count).joinToString(" ") { i -> List(9) { "$prefix$i" }.joinToString(" ") + "." }

    private fun audio(speechS: Double, silenceS: Double): ShortArray = Fixtures.signal(JSONArray(
        """[["silence",0.3],["noise",$speechS,8000],["silence",$silenceS]]"""))

    private fun rigOver(editor: FakeEditor, cfg: PipelineConfig, cleaner: Cleaner, vararg texts: String): Pair<FieldDocument, Rig> {
        val doc = FieldDocument(editor)
        return doc to Rig(cfg, cleaner, doc, Heard(*texts))
    }

    @Test
    fun given_a_pause_clean_that_completes_when_the_next_section_is_cleaned_then_it_is_in_the_field_and_joined_by_a_space() {
        val editor = FakeEditor("")
        val (_, rig) = rigOver(editor, config(), Upper(), labelled("a", 1), labelled("b", 2))

        rig.feed(audio(3.0, 3.0))
        assertEquals(labelled("a", 1).uppercase(), editor.text)
        rig.feed(audio(3.0, 3.0))

        assertEquals(labelled("a", 1).uppercase() + " " + labelled("b", 2).uppercase(), editor.text)
        assertFalse(editor.text.contains("\n\n"))
        assertTrue(rig.clean.all { it.outcome == "applied" })
    }

    @Test
    fun given_a_pause_clean_of_a_section_that_carries_on_a_sentence_when_written_then_it_opens_in_lower_case() {
        val editor = FakeEditor("")
        val rig = Rig(config(), SentenceCase(), FieldDocument(editor), Heard("it should not stop the take when the user is", "Typing into the field again today.", mark = ""))

        rig.feed(audio(3.0, 3.0))
        rig.feed(audio(3.0, 3.0))

        assertEquals("It should not stop the take when the user is typing into the field again today.", editor.text)
    }

    @Test
    fun given_dictation_into_the_middle_of_a_sentence_when_the_first_slice_lands_then_it_opens_in_lower_case() {
        val editor = FakeEditor("as soon as it starts ")
        val (_, rig) = rigOver(editor, config(), SentenceCase(), "Hearing words now.")

        rig.feed(audio(3.0, 3.0))

        assertEquals("as soon as it starts hearing words now.", editor.text)
    }

    @Test
    fun given_a_pause_clean_after_a_sentence_end_when_written_then_it_keeps_its_capital() {
        val editor = FakeEditor("")
        val (_, rig) = rigOver(editor, config(), SentenceCase(), "it should not stop the take when the user is typing.", "activate the system pop-up when we connect.")

        rig.feed(audio(3.0, 3.0))
        rig.feed(audio(3.0, 3.0))

        assertEquals("It should not stop the take when the user is typing. Activate the system pop-up when we connect.", editor.text)
    }

    @Test
    fun given_letters_typed_at_the_end_after_the_stop_when_the_take_finishes_then_they_are_left_as_typed() {
        val editor = FakeEditor("")
        val doc = FieldDocument(editor)
        var typed = false
        val cleaner = Upper { target ->
            if (!typed && target.startsWith("a1") && target.contains("a3")) {
                typed = true
                editor.type(editor.text.length, 0, "po")
                doc.sync()
            }
        }
        val rig = Rig(config(), cleaner, doc, Heard(labelled("a", 3)))
        rig.feed(audio(3.0, 0.0))

        rig.finish()

        assertEquals(labelled("a", 3).uppercase() + "po", editor.text)
        assertTrue(rig.clean.none { it.input == "po" })
    }

    @Test
    fun given_a_pause_clean_abandoned_by_speech_when_it_ends_then_the_tail_is_as_it_was() {
        lateinit var rig: Rig
        val editor = FakeEditor("")
        val cleaner = Upper { rig.slicer.feed(audio(1.0, 0.0)); rig.engine.onAudio() }
        rig = rigOver(editor, config(), cleaner, labelled("a", 1), "more").second

        rig.feed(audio(3.0, 3.0))

        assertEquals(labelled("a", 1), editor.text.substringBefore(" more"))
        assertEquals("abandoned", rig.clean.first().outcome)
    }

    @Test
    fun given_a_take_cleaned_in_two_chunks_when_finished_then_it_is_revised_once_with_both_chunks_as_the_input() {
        val editor = FakeEditor("")
        val cleaner = Upper()
        val (_, rig) = rigOver(editor, config(), cleaner, labelled("a", 3))
        rig.feed(audio(3.0, 0.0))

        val result = rig.finish()

        val heard = labelled("a", 3)
        val revision = rig.clean.single { it.scope == "region" }
        assertEquals(heard, revision.input)
        assertEquals(heard.uppercase(), result.cleaned)
        assertEquals(heard.uppercase(), editor.text)
        assertEquals(2, rig.clean.count { it.scope == "chunk" })
    }

    @Test
    fun given_a_take_cleaned_once_when_finished_then_it_is_not_revised() {
        val (_, rig) = rigOver(FakeEditor(""), config(), Upper(), labelled("a", 1))
        rig.feed(audio(3.0, 0.0))

        rig.finish()

        assertTrue(rig.clean.none { it.scope == "region" })
    }

    @Test
    fun given_a_region_over_the_cleaners_input_size_when_finished_then_it_is_not_revised() {
        val (_, rig) = rigOver(FakeEditor(""), config(maxTokens = 20), Upper(), labelled("a", 3))
        rig.feed(audio(3.0, 0.0))

        rig.finish()

        assertTrue(rig.clean.none { it.scope == "region" })
        assertEquals(2, rig.clean.size)
    }

    @Test
    fun given_revision_off_when_finished_then_nothing_is_revised() {
        val (_, rig) = rigOver(FakeEditor(""), config(revise = "off"), Upper(), labelled("a", 3))
        rig.feed(audio(3.0, 0.0))

        rig.finish()

        assertTrue(rig.clean.none { it.scope == "region" })
    }

    @Test
    fun given_an_edit_during_the_revision_when_it_returns_then_it_is_dropped_and_the_field_is_as_the_user_left_it() {
        val editor = FakeEditor("")
        val (doc, rig) = rigOver(editor, config(), Upper(), labelled("a", 3))
        rig.slicer.feed(audio(3.0, 0.0))
        rig.engine.stop()
        while (true) {
            val job = rig.engine.next() ?: break
            if (job.plan?.region == true) {
                editor.type(2, 0, "zz")
                doc.sync()
            }
            rig.engine.complete(job, job.run())
        }

        assertTrue(editor.text, editor.text.contains("zz"))
        assertFalse(editor.text.contains("REVISED"))
        assertEquals("dropped", rig.clean.single { it.scope == "region" }.outcome)
        assertNull(rig.engine.next())
    }

    @Test
    fun given_pause_revision_when_the_user_pauses_then_the_region_is_revised_and_when_speech_resumes_it_is_abandoned() {
        run {
            val editor = FakeEditor("")
            val (_, rig) = rigOver(editor, config(revise = "pause"), Upper(), labelled("a", 3))
            rig.feed(audio(3.0, 3.0))

            assertEquals(labelled("a", 3).uppercase(), editor.text)
            assertEquals("pause", rig.clean.single { it.scope == "region" }.moment)
        }
        lateinit var rig: Rig
        val editor = FakeEditor("")
        var spoke = false
        val heard = labelled("a", 3)
        val cleaner = Upper { target ->
            if (target == heard && !spoke) {
                spoke = true
                rig.slicer.feed(audio(1.0, 0.0))
                rig.engine.onAudio()
            }
        }
        rig = rigOver(editor, config(revise = "pause"), cleaner, labelled("a", 3), "more").second
        rig.feed(audio(3.0, 3.0))

        assertEquals("abandoned", rig.clean.first { it.scope == "region" }.outcome)
    }

    @Test
    fun given_a_typed_fix_inside_a_once_cleaned_region_when_finished_then_it_is_not_revised_and_the_fix_stands() {
        val editor = FakeEditor("")
        val (doc, rig) = rigOver(editor, config(), Upper(), labelled("a", 1))
        rig.feed(audio(3.0, 0.0))
        rig.engine.stop()
        rig.driver.drain()

        editor.type(3, 0, "zz")
        doc.sync()
        val result = rig.finish()

        assertTrue(rig.clean.none { it.scope == "region" })
        assertTrue(result.cleaned, result.cleaned.contains("zz"))
        assertTrue(editor.text, editor.text.contains("zz"))
    }

    @Test
    fun given_a_region_cleaned_twice_when_the_user_edits_it_before_the_finish_then_it_is_not_revised() {
        val editor = FakeEditor("")
        val (doc, rig) = rigOver(editor, config(), Upper(), labelled("a", 3), "")
        rig.feed(audio(3.0, 3.0))

        editor.type(2, 0, "zz")
        doc.sync()
        rig.finish()

        assertEquals(2, rig.clean.count { it.scope == "chunk" })
        assertTrue(rig.clean.none { it.scope == "region" })
        assertTrue(editor.text, editor.text.contains("zz"))
        assertFalse(editor.text.contains("REVISED"))
    }

    @Test
    fun given_a_seeded_buffer_under_revision_when_run_then_the_seed_is_as_it_was() {
        val doc = BufferDocument("seed")
        val rig = Rig(config(), Upper(), doc, Heard(labelled("a", 3)))
        rig.feed(audio(3.0, 0.0))

        val result = rig.finish()

        assertTrue(doc.read()!!.text, doc.read()!!.text.startsWith("seed "))
        assertEquals(" " + labelled("a", 3).uppercase().replaceFirstChar { it.lowercaseChar() }, result.cleaned)
        assertEquals("seed" + result.cleaned, doc.read()!!.text)
    }
}
