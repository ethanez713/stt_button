package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldDocumentTest {
    private fun opened(editor: FakeEditor): Pair<FieldDocument, MutableList<Edit>> {
        val doc = FieldDocument(editor)
        val seen = mutableListOf<Edit>()
        doc.onEdit = { seen += it }
        assertTrue(doc.sync())
        return doc to seen
    }

    @Test
    fun given_a_replace_before_the_caret_when_written_then_the_caret_shifts_with_the_text() {
        val editor = FakeEditor("hello world", 11)
        val (doc, _) = opened(editor)

        assertTrue(doc.replace(0, 5, "hi"))

        assertEquals("hi world", editor.text)
        assertEquals(8, editor.selectionStart)
        assertEquals(8, editor.selectionEnd)
    }

    @Test
    fun given_a_caret_resting_at_the_end_of_the_span_when_it_is_replaced_then_the_caret_follows_the_new_text() {
        val editor = FakeEditor("abc", 3)
        val (doc, _) = opened(editor)

        doc.replace(0, 3, "xy")

        assertEquals(2, editor.selectionStart)
    }

    @Test
    fun given_a_caret_inside_the_span_when_it_is_replaced_then_the_caret_goes_to_the_spans_new_end() {
        val editor = FakeEditor("hello world", 3)
        val (doc, _) = opened(editor)

        doc.replace(0, 5, "hey there")

        assertEquals("hey there world", editor.text)
        assertEquals(9, editor.selectionStart)
    }

    @Test
    fun given_a_caret_before_the_span_when_it_is_replaced_then_the_caret_stays() {
        val editor = FakeEditor("hello world", 2)
        val (doc, _) = opened(editor)

        doc.replace(6, 11, "there")

        assertEquals(2, editor.selectionStart)
    }

    @Test
    fun given_an_outside_edit_between_two_writes_when_written_then_it_reaches_on_edit_and_the_takes_own_write_does_not() {
        val editor = FakeEditor("hello world", 11)
        val (doc, seen) = opened(editor)

        doc.replace(0, 5, "hi")
        assertTrue(seen.isEmpty())
        editor.type(2, 0, "!!")
        doc.replace(0, 2, "yo")

        assertEquals(listOf(Edit(2, 0, 2)), seen)
        assertEquals("yo!! world", editor.text)
    }

    @Test
    fun given_a_sync_after_typing_when_reported_then_the_edit_is_the_one_the_caret_explains() {
        val editor = FakeEditor("aaa", 3)
        val (doc, seen) = opened(editor)

        editor.type(1, 0, "a")
        doc.sync()

        assertEquals(listOf(Edit(1, 0, 1)), seen)
    }

    @Test
    fun given_an_editor_that_cannot_be_read_or_only_partly_when_read_then_the_document_is_gone() {
        val editor = FakeEditor("abc")
        val (doc, _) = opened(editor)

        editor.partial = true
        assertNull(doc.read())
        assertFalse(doc.replace(0, 1, "x"))
        assertFalse(doc.sync())
        editor.partial = false
        editor.gone = true
        assertNull(doc.read())
        assertFalse(doc.replace(0, 1, "x"))
    }

    @Test
    fun given_a_write_the_editor_refuses_when_replaced_then_the_document_reports_it_and_the_mirror_is_unchanged() {
        val editor = FakeEditor("abc", 3)
        val (doc, seen) = opened(editor)
        editor.refuseWrites = true

        assertFalse(doc.replace(0, 1, "x"))
        editor.refuseWrites = false
        doc.sync()

        assertTrue(seen.isEmpty())
    }

    @Test
    fun given_a_range_outside_the_field_when_replaced_then_it_is_refused() {
        val (doc, _) = opened(FakeEditor("abc"))

        assertFalse(doc.replace(2, 9, "x"))
    }
}
