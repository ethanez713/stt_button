package com.ezhou.sttbutton

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTakeTest {
    private class Heard(vararg texts: String) : Recognizer {
        private val queue = ArrayDeque(texts.toList())
        override fun recognize(request: RecognizeRequest) = Recognized(queue.removeFirst(), null)
    }

    private class Upper : Cleaner {
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(request: CleanRequest) = CleanResult(request.target.uppercase(), 1)
        override fun cancel() {}
    }

    private class Clock(var now: Long = 0)

    private val cfg = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).apply {
        put("chunk_min_tokens", 15)
        put("skip_max_words", 5)
        put("field_revise", "stop")
    })

    private fun audio(speechS: Double, silenceS: Double): ShortArray = Fixtures.signal(JSONArray(
        """[["silence",0.3],["noise",$speechS,8000],["silence",$silenceS]]"""))

    private fun words(prefix: String) = List(9) { "$prefix$it" }.joinToString(" ") + "."

    private class Rig(val editor: FakeEditor, val clock: Clock, cfg: PipelineConfig, vararg heard: String) {
        val records = mutableListOf<JobRecord>()
        val doc = FieldDocument(editor) { clock.now }.also { check(it.sync()) }
        val slicer = Slicer.fromConfig(cfg)
        val engine = TakeEngine(
            doc, slicer, Heard(*heard), Upper(), RevisePolicy(cfg), cfg,
            jobs = { records += it }, nowMs = { clock.now },
        )
        var detached = 0

        init {
            engine.onDetached = { detached++ }
        }

        fun decodeFirstSlice() {
            slicer.feed(Fixtures.signal(JSONArray("""[["silence",0.3],["noise",3.0,8000],["silence",3.0]]""")))
            val job = checkNotNull(engine.next())
            engine.complete(job, job.run())
        }
    }

    @Test
    fun given_typing_when_a_phrase_has_landed_then_no_clean_starts_until_700_ms_after_the_edit() {
        val clock = Clock()
        val rig = Rig(FakeEditor(""), clock, cfg, words("a"))
        rig.decodeFirstSlice()

        clock.now = 100
        rig.editor.type(rig.editor.text.length, 0, " ")
        rig.doc.sync()
        clock.now = 799
        assertTrue(rig.engine.writesHeld())
        assertNull(rig.engine.next())
        clock.now = 800
        assertFalse(rig.engine.writesHeld())
        assertNotNull(rig.engine.next())
    }

    @Test
    fun given_writes_held_for_typing_when_the_take_stops_then_they_are_no_longer_held() {
        val clock = Clock()
        val rig = Rig(FakeEditor(""), clock, cfg, words("a"), "")
        rig.decodeFirstSlice()
        rig.editor.type(rig.editor.text.length, 0, "w")
        rig.doc.sync()
        clock.now = 10
        assertTrue(rig.engine.writesHeld())

        rig.engine.stop()

        assertFalse(rig.engine.writesHeld())
    }

    @Test
    fun given_the_field_gone_mid_take_when_the_rest_is_heard_then_it_is_kept_apart_and_nothing_more_is_written_to_the_field() {
        val clock = Clock()
        val rig = Rig(FakeEditor(""), clock, cfg, words("a"), words("b"), "", "")
        rig.decodeFirstSlice()
        val written = rig.editor.text
        val writes = rig.editor.writes

        rig.editor.gone = true
        rig.slicer.feed(audio(3.0, 3.0))
        rig.engine.markStop()
        val result = rig.engine.finish()

        assertEquals(1, rig.detached)
        assertEquals(written, rig.editor.text)
        assertEquals(writes, rig.editor.writes)
        assertTrue(result.cleaned, result.cleaned.contains("B0"))
        assertFalse(result.cleaned, result.cleaned.contains("A0"))
    }

    @Test
    fun given_the_field_gone_when_a_write_is_attempted_then_the_heard_text_is_not_lost() {
        val clock = Clock()
        val rig = Rig(FakeEditor(""), clock, cfg, words("a"), words("b"))
        rig.decodeFirstSlice()
        rig.editor.refuseWrites = true
        rig.slicer.feed(audio(3.0, 3.0))
        val job = checkNotNull(rig.engine.next())
        rig.engine.complete(job, job.run())

        assertEquals(1, rig.detached)
        assertTrue(rig.engine.result().cleaned, rig.engine.result().cleaned.contains("b0"))
    }

    @Test
    fun given_preview_words_when_they_change_then_they_are_rewritten_no_faster_than_every_250_ms() {
        val rig = Rig(FakeEditor(""), Clock(), cfg)

        rig.engine.setProvisional("one")

        assertEquals("one", rig.editor.text)
        assertEquals(1, rig.engine.provisionalWrites)
        assertEquals(0L, rig.engine.firstWriteMs)
        assertTrue(rig.records.isEmpty())

        rig.clock.now = 249
        rig.engine.setProvisional("one two")
        rig.engine.flushProvisional()

        assertEquals("one", rig.editor.text)
        assertEquals(1, rig.engine.provisionalWrites)

        rig.clock.now = 250
        rig.engine.flushProvisional()

        assertEquals("one two", rig.editor.text)
        assertEquals(2, rig.engine.provisionalWrites)
        assertEquals(0L, rig.engine.firstWriteMs)
        assertTrue(rig.records.isEmpty())
    }

    @Test
    fun given_preview_words_when_a_slice_lands_then_it_replaces_the_words_inside_it_and_leaves_the_later_ones() {
        val rig = Rig(FakeEditor(""), Clock(), cfg, "one two.")
        rig.engine.setProvisional("one two three")

        rig.decodeFirstSlice()
        rig.engine.setProvisional("three")

        assertEquals("one two. three", rig.editor.text)
        assertEquals(2, rig.engine.provisionalWrites)
        assertTrue(rig.records.none { it.input?.contains("three") == true || it.output?.contains("three") == true })
    }

    @Test
    fun given_a_held_write_when_provisional_text_arrives_then_it_waits_too() {
        val rig = Rig(FakeEditor(""), Clock(), cfg)
        rig.clock.now = 100
        rig.editor.type(0, 0, " ")
        rig.doc.sync()

        rig.engine.setProvisional("one")
        rig.clock.now = 799
        rig.engine.flushProvisional()

        assertTrue(rig.engine.writesHeld())
        assertEquals(" ", rig.editor.text)
        assertEquals(0, rig.engine.provisionalWrites)

        rig.clock.now = 800
        rig.engine.flushProvisional()

        assertFalse(rig.engine.writesHeld())
        assertEquals(" one", rig.editor.text)
        assertEquals(1, rig.engine.provisionalWrites)
    }

    @Test
    fun given_an_edit_inside_the_preview_when_it_is_rewritten_then_the_preview_replaces_it() {
        val rig = Rig(FakeEditor(""), Clock(), cfg)
        rig.engine.setProvisional("one two")
        rig.editor.type(2, 0, ".")
        rig.doc.sync()

        rig.clock.now = 700
        rig.engine.flushProvisional()

        assertEquals("one two", rig.editor.text)
    }

    @Test
    fun given_text_typed_at_the_end_of_the_preview_when_it_is_rewritten_then_the_typed_text_stays() {
        val rig = Rig(FakeEditor(""), Clock(), cfg)
        rig.engine.setProvisional("one")
        rig.editor.type(rig.editor.text.length, 0, " KEEP ")
        rig.doc.sync()

        rig.clock.now = 700
        rig.engine.setProvisional("one two")

        assertEquals("one two KEEP ", rig.editor.text)
    }

    @Test
    fun given_an_edit_before_the_preview_when_the_preview_changes_then_it_stays_after_that_edit() {
        val editor = FakeEditor("Hi")
        val rig = Rig(editor, Clock(), cfg)
        rig.engine.setProvisional("one")
        assertEquals("Hi one", editor.text)
        editor.type(0, 0, "X")
        rig.doc.sync()
        editor.selectionStart = 0
        editor.selectionEnd = 0
        rig.doc.sync()

        rig.clock.now = 700
        rig.engine.setProvisional("two")

        assertEquals("XHi two", editor.text)
    }

    @Test
    fun given_the_field_still_reachable_when_the_take_detaches_then_the_preview_span_is_removed() {
        val rig = Rig(FakeEditor(""), Clock(), cfg)
        rig.engine.setProvisional("raw words")

        rig.engine.detach()
        rig.engine.setProvisional("more")
        rig.engine.flushProvisional()

        assertEquals("", rig.editor.text)
        assertEquals(1, rig.detached)
        assertEquals(1, rig.engine.provisionalWrites)
    }

    @Test
    fun given_the_field_gone_when_preview_text_arrives_then_nothing_more_is_written() {
        val rig = Rig(FakeEditor(""), Clock(), cfg)
        rig.engine.setProvisional("raw words")
        val writes = rig.editor.writes
        rig.editor.gone = true

        rig.engine.setProvisional("more")
        rig.engine.flushProvisional()

        assertEquals("raw words", rig.editor.text)
        assertEquals(writes, rig.editor.writes)
        assertEquals(0, rig.detached)
        assertEquals(1, rig.engine.provisionalWrites)
    }
}
