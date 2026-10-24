package com.ezhou.sttbutton

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentsTest {
    private val cfg = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")))

    @Test
    fun given_a_whole_read_when_chosen_then_the_field_is_the_document() =
        assertEquals(DocumentKind.FIELD, Documents.choose(FieldText("abc", 3, 3, whole = true)))

    @Test
    fun given_a_partial_or_null_read_when_chosen_then_the_buffer_is_the_document() {
        assertEquals(DocumentKind.BUFFER, Documents.choose(FieldText("abc", 3, 3, whole = false)))
        assertEquals(DocumentKind.BUFFER, Documents.choose(null))
    }

    @Test
    fun given_an_empty_field_holding_only_the_dictation_when_finished_then_the_casual_rule_applies() {
        val dictated = "Sounds good, see you at five."

        assertEquals("Sounds good see you at five", Documents.finalText(dictated, "", dictated, true, cfg))
    }

    @Test
    fun given_a_field_with_other_text_when_finished_then_commas_stay_and_the_final_period_goes() {
        val dictated = "Sounds good, see you at five."

        assertEquals("Sounds good, see you at five", Documents.finalText(dictated, "Hi. ", "Hi. $dictated", true, cfg))
        assertEquals("Sounds good, see you at five", Documents.finalText(dictated, "", "Hi. $dictated", true, cfg))
    }

    @Test
    fun given_a_question_when_finished_then_its_question_mark_stays() {
        val dictated = "Do you need a subscription for that?"

        assertEquals(dictated, Documents.finalText(dictated, "Hi. ", "Hi. $dictated", true, cfg))
    }

    @Test
    fun given_a_single_line_field_when_finished_then_line_breaks_become_spaces() {
        val dictated = "First part of a long dictation that goes on and on and on for quite a few words here.\n\nSecond part of it."

        assertEquals(dictated.replace("\n\n", " ").removeSuffix("."), Documents.finalText(dictated, "x", "x$dictated", false, cfg))
    }
}
