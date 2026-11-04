package com.ezhou.sttbutton

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SeenFieldsTest {
    @Test
    fun given_malformed_lines_when_read_then_they_are_skipped_and_a_bad_category_is_kept() {
        val lines = listOf(
            "",
            "   ",
            "{not json",
            "[1, 2, 3]",
            "null",
            """{"event":"start","pkg":"com.one","field_view_id":"v","field_input_type":1,"field_multi_line":true}""",
            """{"event":"clipboard","pkg":"com.one","text":"x","field_view_id":"v","field_input_type":1,"field_multi_line":true}""",
            """{"event":"insert","field_view_id":"v","field_hint":"h","field_input_type":1,"field_multi_line":true}""",
            """{"event":"insert","pkg":null,"field_view_id":"v","field_input_type":1,"field_multi_line":true}""",
            """{"event":"insert","pkg":"","field_view_id":"v","field_input_type":1,"field_multi_line":true}""",
            """{"event":"insert","pkg":"com.bad","field_view_id":"v","field_input_type":"nope","field_multi_line":true}""",
            """{"event":"insert","pkg":"com.bad","field_view_id":1,"field_input_type":1,"field_multi_line":true}""",
            """{"event":"insert","pkg":"com.bad","field_view_id":"v","field_hint":1,"field_input_type":1,"field_multi_line":true}""",
            """{"event":"insert","pkg":"com.bad","field_view_id":"v","field_input_type":1,"field_multi_line":"yes"}""",
            insert("com.ok", "id/ok", "Hello", 15, false, "nope", "nope"),
        )

        assertEquals(
            listOf(SeenField("com.ok", "id/ok", "Hello", 15, false, null, null)),
            SeenFields.fromLines(lines),
        )
    }

    @Test
    fun given_an_insert_from_before_field_keys_when_read_then_it_is_skipped() {
        val lines = listOf(
            insert("com.new", "id/box", "Note", 1, true, "notes", "builtin_app"),
            """{"event":"insert","ts":"2026-10-05T12:00:00Z","pkg":"com.old","raw":"hi","cleaned":"Hi"}""",
            """{"event":"insert","pkg":"com.new","cleaned":"later","category":"chat","style_source":"app"}""",
        )

        assertEquals(
            listOf(SeenField("com.new", "id/box", "Note", 1, true, Category.NOTES, StyleSource.BUILTIN_APP)),
            SeenFields.fromLines(lines),
        )
    }

    @Test
    fun given_two_inserts_for_one_field_when_read_then_the_later_line_wins() {
        val lines = listOf(
            insert("com.app", "id/entry", "Old", 1, false, "chat", "builtin_app"),
            insert("com.other", "id/x", "Other", 3, true, "email", "app"),
            insert("com.app", "id/entry", "New", 2, true, "notes", "field"),
        )

        assertEquals(
            listOf(
                SeenField("com.other", "id/x", "Other", 3, true, Category.EMAIL, StyleSource.APP),
                SeenField("com.app", "id/entry", "New", 2, true, Category.NOTES, StyleSource.FIELD),
            ),
            SeenFields.fromLines(lines),
        )
    }

    @Test
    fun given_a_null_view_id_when_read_then_the_field_is_kept() {
        val lines = listOf(
            insert("com.app", null, "First", 1, false, "chat", "builtin_app"),
            insert("com.app", null, null, null, null, "email", "app"),
            insert("com.app", "id/box", "Hi", 4, false, "form", "single_line"),
        )

        assertEquals(
            listOf(
                SeenField("com.app", null, null, null, null, Category.EMAIL, StyleSource.APP),
                SeenField("com.app", "id/box", "Hi", 4, false, Category.FORM, StyleSource.SINGLE_LINE),
            ),
            SeenFields.fromLines(lines),
        )
    }

    private fun insert(
        pkg: String,
        viewId: String?,
        hint: String?,
        inputType: Int?,
        multiLine: Boolean?,
        category: String?,
        source: String?,
    ): String = JSONObject()
        .put("event", "insert")
        .put("pkg", pkg)
        .put("field_view_id", viewId ?: JSONObject.NULL)
        .put("field_hint", hint ?: JSONObject.NULL)
        .put("field_input_type", inputType ?: JSONObject.NULL)
        .put("field_multi_line", multiLine ?: JSONObject.NULL)
        .put("category", category ?: JSONObject.NULL)
        .put("style_source", source ?: JSONObject.NULL)
        .toString()
}
