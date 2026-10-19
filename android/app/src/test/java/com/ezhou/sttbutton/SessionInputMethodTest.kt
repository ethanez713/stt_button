package com.ezhou.sttbutton

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInputMethodTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun given_the_same_package_and_a_plain_text_field_when_compared_then_they_agree() =
        assertTrue(session().agrees("app.one", false, text))

    @Test
    fun given_the_same_package_when_only_the_text_flags_differ_then_they_agree() =
        assertTrue(session().agrees("app.one", false, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))

    @Test
    fun given_the_same_package_when_only_the_node_is_flagged_password_then_they_disagree() =
        assertFalse(session().agrees("app.one", true, text))

    @Test
    fun given_the_same_package_when_both_verdicts_are_password_then_they_agree() =
        assertTrue(
            session(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD).agrees("app.one", true, text),
        )

    @Test
    fun given_different_packages_when_compared_then_they_disagree() =
        assertFalse(session().agrees("app.two", false, text))

    @Test
    fun given_the_same_package_when_the_field_classes_differ_then_they_disagree() =
        assertFalse(session(InputType.TYPE_CLASS_NUMBER).agrees("app.one", false, InputType.TYPE_CLASS_PHONE))

    @Test
    fun given_no_session_and_no_field_when_compared_then_they_agree() =
        assertTrue(InputSession.EMPTY.agrees(null, false, 0))

    @Test
    fun given_a_session_and_no_field_when_compared_then_they_disagree() =
        assertFalse(session().agrees(null, false, 0))

    @Test
    fun given_no_session_and_a_field_when_compared_then_they_disagree() =
        assertFalse(InputSession.EMPTY.agrees("app.one", false, text))

    private fun session(inputType: Int = text, fieldId: Int = 7) = InputSession("app.one", inputType, starts = 1, finishes = 0, fieldId = fieldId)

    @Test
    fun given_a_restart_of_the_same_editor_when_input_starts_then_the_field_has_not_been_left() =
        assertFalse(FieldId("app.one", 7).leftBy(session(), restarting = true))

    @Test
    fun given_the_same_editor_again_when_input_starts_then_the_field_has_not_been_left() =
        assertFalse(FieldId("app.one", 7).leftBy(session(), restarting = false))

    @Test
    fun given_another_field_of_the_app_when_input_starts_then_the_field_has_been_left() =
        assertTrue(FieldId("app.one", 7).leftBy(session(fieldId = 8), restarting = false))

    @Test
    fun given_another_app_when_input_starts_then_the_field_has_been_left() =
        assertTrue(FieldId("app.two", 7).leftBy(session(), restarting = false))
}
