package com.ezhou.sttbutton

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsTest {
    private val cfg = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")))

    private fun style(category: Category) = cfg.styles.getValue(category)

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
    fun given_a_chat_take_when_finished_then_sentence_starts_are_lower_and_commas_and_the_final_period_go() {
        val dictated = "Yes, I'll go. I'm here. I've seen it. I'd rather. I know."

        assertEquals(
            "yes I'll go. I'm here. I've seen it. I'd rather. I know",
            Documents.finalText(dictated, "", dictated, true, cfg, style(Category.CHAT)),
        )
    }

    @Test
    fun given_a_chat_take_added_to_a_field_when_finished_then_commas_stay_and_sentence_starts_are_lower() {
        val dictated = "Yes, I'll go. I'm here. I've seen it. I'd rather. I know."

        assertEquals(
            "yes, I'll go. I'm here. I've seen it. I'd rather. I know",
            Documents.finalText(dictated, "Hi. ", "Hi. $dictated", true, cfg, style(Category.CHAT)),
        )
    }

    @Test
    fun given_an_email_take_when_finished_then_the_period_and_commas_stay() {
        val dictated = "Yes, I'll go. I'm here. I've seen it. I'd rather. I know."

        assertEquals(dictated, Documents.finalText(dictated, "", dictated, true, cfg, style(Category.EMAIL)))
    }

    @Test
    fun given_a_default_take_when_finished_then_it_is_unchanged() {
        val dictated = "Yes, I'll go. I'm here. I've seen it. I'd rather. I know."
        val today = Pipeline.trimMessageEnd(Pipeline.forField(Pipeline.casual(Pipeline.withoutSignOff(dictated), cfg), true))

        assertEquals("Yes I'll go. I'm here. I've seen it. I'd rather. I know", today)
        assertEquals(today, Documents.finalText(dictated, "", dictated, true, cfg, style(Category.DEFAULT)))
        assertEquals(today, Documents.finalText(dictated, "", dictated, true, cfg))
        assertEquals(
            "Yes, I'll go. I'm here. I've seen it. I'd rather. I know",
            Documents.finalText(dictated, "Hi. ", "Hi. $dictated", true, cfg, style(Category.DEFAULT)),
        )
    }

    @Test
    fun given_a_chat_take_over_skip_max_words_when_styled_then_it_is_untouched() {
        val text = "Hello, " + List(cfg.skipMaxWords) { "word" }.joinToString(" ") + "."
        assertTrue(Pipeline.wordCount(text) > cfg.skipMaxWords)

        assertEquals(text, Pipeline.styled(text, cfg.styles.getValue(Category.CHAT), cfg))
        assertEquals(Pipeline.trimMessageEnd(text), Documents.finalText(text, "", text, true, cfg, style(Category.CHAT)))
    }

    @Test
    fun given_a_single_line_field_when_finished_then_line_breaks_become_spaces() {
        val dictated = "First part of a long dictation that goes on and on and on for quite a few words here.\n\nSecond part of it."

        assertEquals(dictated.replace("\n\n", " ").removeSuffix("."), Documents.finalText(dictated, "x", "x$dictated", false, cfg))
    }

    @Test
    fun given_a_style_when_finished_then_its_knobs_shape_the_text() {
        val dictated = "Yes, I'll go. I'm here."
        val style = Style("semi-formal", "prose", casual = false, lowercaseStarts = true, keepEnd = true)

        assertEquals("yes, I'll go. I'm here.", Documents.finalText(dictated, "", dictated, true, cfg, style))
    }
}
