package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {
    @Test
    fun given_the_takes_own_write_when_replaced_then_the_text_changes_and_nothing_is_reported() {
        val doc = BufferDocument("hello world")
        val seen = mutableListOf<Edit>()
        doc.onEdit = { seen += it }

        assertTrue(doc.replace(6, 11, "there"))

        assertEquals("hello there", doc.read()!!.text)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun given_a_user_edit_when_applied_then_it_is_reported_with_its_offsets() {
        val doc = BufferDocument("hello world")
        val seen = mutableListOf<Edit>()
        doc.onEdit = { seen += it }

        doc.userEdit(5, 5, ",")

        assertEquals("hello, world", doc.read()!!.text)
        assertEquals(listOf(Edit(5, 0, 1)), seen)
    }

    @Test
    fun given_a_range_outside_the_text_when_replaced_then_it_is_refused() {
        val doc = BufferDocument("abc")

        assertFalse(doc.replace(2, 9, "x"))
        assertFalse(doc.replace(-1, 1, "x"))
        assertEquals("abc", doc.read()!!.text)
    }

    @Test
    fun given_a_caret_after_the_replaced_range_when_replaced_then_it_keeps_its_place_in_the_text() {
        val doc = BufferDocument("hello world")

        doc.replace(0, 5, "hi")

        assertEquals(8, doc.read()!!.selectionStart)
    }
}
