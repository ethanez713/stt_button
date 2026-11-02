package com.ezhou.sttbutton

import android.text.InputType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakeStyleTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val style = TakeStyle(Fixtures.config.appCategories)

    private fun classify(pkg: String?, inputType: Int, multiLine: Boolean, hint: String? = null, viewId: String? = null) =
        style.classify(pkg, inputType, multiLine, hint, viewId)

    @Test
    fun given_a_uri_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text or InputType.TYPE_TEXT_VARIATION_URI, multiLine = true))
    }

    @Test
    fun given_an_email_address_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, multiLine = true))
    }

    @Test
    fun given_an_email_subject_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, multiLine = true))
    }

    @Test
    fun given_a_person_name_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, multiLine = true))
    }

    @Test
    fun given_a_postal_address_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, multiLine = true))
    }

    @Test
    fun given_a_filter_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text or InputType.TYPE_TEXT_VARIATION_FILTER, multiLine = true))
    }

    @Test
    fun given_a_listed_app_when_classified_then_it_uses_its_category() {
        assertEquals(
            mapOf(
                "com.google.android.apps.messaging" to Category.CHAT,
                "com.samsung.android.messaging" to Category.CHAT,
                "com.discord" to Category.CHAT,
                "com.facebook.orca" to Category.CHAT,
                "com.whatsapp" to Category.CHAT,
                "org.telegram.messenger" to Category.CHAT,
                "com.instagram.android" to Category.CHAT,
                "com.snapchat.android" to Category.CHAT,
                "com.google.android.gm" to Category.EMAIL,
                "com.microsoft.office.outlook" to Category.EMAIL,
                "com.google.android.keep" to Category.NOTES,
                "com.samsung.android.app.notes" to Category.NOTES,
            ),
            Fixtures.config.appCategories,
        )
        assertEquals(Category.CHAT, classify("com.whatsapp", multi, multiLine = true))
        assertEquals(Category.EMAIL, classify("com.google.android.gm", multi, multiLine = true))
        assertEquals(Category.NOTES, classify("com.google.android.keep", multi, multiLine = true))
        assertEquals(Category.CHAT, classify("com.discord", text, multiLine = true))
    }

    @Test
    fun given_a_short_message_field_when_classified_then_it_is_chat() {
        assertEquals(Category.CHAT, classify(null, text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, multiLine = true))
    }

    @Test
    fun given_a_single_line_field_when_classified_then_it_is_a_form() {
        assertEquals(Category.FORM, classify(null, text, multiLine = false))
        assertEquals(Category.FORM, classify("com.example.unknown", multi, multiLine = false))
    }

    @Test
    fun given_a_multi_line_field_in_an_unknown_app_when_classified_then_it_is_the_default() {
        assertEquals(Category.DEFAULT, classify(null, text, multiLine = true))
        assertEquals(Category.DEFAULT, classify("com.example.unknown", multi, multiLine = true))
        assertEquals(Category.DEFAULT, classify(null, 0, multiLine = true))
    }

    @Test
    fun given_a_form_variation_in_a_known_app_when_classified_then_the_variation_wins() {
        assertEquals(Category.FORM, classify("com.whatsapp", text or InputType.TYPE_TEXT_VARIATION_URI, multiLine = true))
        assertEquals(Category.FORM, classify("com.google.android.gm", text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, multiLine = false))
    }

    @Test
    fun given_a_known_app_with_a_short_message_field_when_classified_then_the_app_wins() {
        assertEquals(Category.EMAIL, classify("com.microsoft.office.outlook", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, multiLine = false))
        assertEquals(Category.NOTES, classify("com.samsung.android.app.notes", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, multiLine = true))
    }

    @Test
    fun given_a_known_app_with_a_single_line_field_when_classified_then_the_app_wins() {
        assertEquals(Category.CHAT, classify("com.snapchat.android", text, multiLine = false))
        assertEquals(Category.EMAIL, classify("com.google.android.gm", text, multiLine = false))
    }

    @Test
    fun given_a_single_line_short_message_when_classified_then_short_message_wins() {
        assertEquals(Category.CHAT, classify("com.example.unknown", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, multiLine = false))
    }

    @Test
    fun given_a_hint_and_a_view_id_when_classified_then_they_do_not_decide() {
        assertEquals(Category.CHAT, classify("com.whatsapp", text, multiLine = true, hint = "Email address", viewId = "subject"))
        assertEquals(
            classify(null, text, multiLine = false),
            classify(null, text, multiLine = false, hint = "message", viewId = "com.example:id/chat"),
        )
    }

    @Test
    fun given_a_non_text_field_whose_variation_bits_match_a_form_when_classified_then_it_is_not_a_form() {
        val numberPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertEquals(Category.DEFAULT, classify(null, numberPassword, multiLine = true))
    }

    @Test
    fun given_each_category_when_the_control_line_is_rendered_then_it_uses_that_styles_wording() {
        val cfg = Fixtures.config
        val lists = "[Styling: semi-formal] [Structure: lists] [Context: general]"
        assertEquals(lists, cfg.controlLine)
        assertEquals(lists, cfg.controlLine(Category.DEFAULT))
        assertEquals("[Styling: semi-casual] [Structure: lists] [Context: general]", cfg.controlLine(Category.CHAT))
        assertEquals(lists, cfg.controlLine(Category.EMAIL))
        assertEquals(lists, cfg.controlLine(Category.NOTES))
        assertEquals("[Styling: semi-formal] [Structure: prose] [Context: general]", cfg.controlLine(Category.FORM))
    }

    @Test
    fun given_the_style_table_when_read_then_each_category_keeps_its_rules() {
        val styles = Fixtures.config.styles
        assertEquals(Style("semi-formal", "lists", casual = true, lowercaseStarts = false, keepEnd = false), styles.getValue(Category.DEFAULT))
        assertEquals(Style("semi-casual", "lists", casual = true, lowercaseStarts = true, keepEnd = false), styles.getValue(Category.CHAT))
        assertEquals(Style("semi-formal", "lists", casual = false, lowercaseStarts = false, keepEnd = true), styles.getValue(Category.EMAIL))
        assertEquals(Style("semi-formal", "lists", casual = false, lowercaseStarts = false, keepEnd = true), styles.getValue(Category.NOTES))
        assertEquals(Style("semi-formal", "prose", casual = false, lowercaseStarts = false, keepEnd = false), styles.getValue(Category.FORM))
    }

    @Test
    fun given_a_cleaner_without_a_control_line_when_rendered_then_it_is_absent() {
        val cfg = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).put("cleaner", "qwen3.5-2b"))
        assertNull(cfg.controlLine)
        assertNull(cfg.controlLine(Category.CHAT))
    }
}

class LowercaseStartsTest {
    @Test
    fun given_i_words_when_sentence_starts_are_lowered_then_they_stay() {
        assertEquals(
            "hello. I'll go. I'm here. I've seen it. I'd rather. I know. yes.",
            Pipeline.lowercaseStarts("Hello. I'll go. I'm here. I've seen it. I'd rather. I know. Yes."),
        )
        assertEquals("I’m here.", Pipeline.lowercaseStarts("I’m here."))
    }

    @Test
    fun given_all_caps_and_mixed_case_when_sentence_starts_are_lowered_then_they_stay() {
        assertEquals(
            "say NASA. iPhone works. MacBook stays. yes.",
            Pipeline.lowercaseStarts("Say NASA. iPhone works. MacBook stays. Yes."),
        )
    }

    @Test
    fun given_a_sentence_after_a_question_mark_when_starts_are_lowered_then_it_opens_lower() {
        assertEquals("ready? yes now.", Pipeline.lowercaseStarts("Ready? Yes now."))
        assertEquals("ready. yes! now.", Pipeline.lowercaseStarts("Ready. Yes! Now."))
    }

    @Test
    fun given_an_empty_string_when_sentence_starts_are_lowered_then_it_stays_empty() {
        assertEquals("", Pipeline.lowercaseStarts(""))
    }
}
