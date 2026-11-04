package com.ezhou.sttbutton

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class StyleBookTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val shortMessage = text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
    private val cfg = Fixtures.config
    private val chat = cfg.styles.getValue(Category.CHAT)
    private val form = cfg.styles.getValue(Category.FORM)
    private val email = cfg.styles.getValue(Category.EMAIL)
    private val notes = cfg.styles.getValue(Category.NOTES)
    private val defaultStyle = cfg.styles.getValue(Category.DEFAULT)

    private fun resolve(
        pkg: String?, inputType: Int, multiLine: Boolean, hint: String? = null, viewId: String? = null,
        overrides: StyleOverrides = StyleOverrides(),
    ) = StyleBook(cfg, overrides).resolve(pkg, inputType, multiLine, hint, viewId)

    private fun url(viewId: String? = null, overrides: StyleOverrides = StyleOverrides()) =
        resolve("com.whatsapp", text or InputType.TYPE_TEXT_VARIATION_URI, multiLine = true, viewId = viewId, overrides = overrides)

    @Test
    fun given_a_field_override_on_a_url_field_when_resolved_then_the_field_wins() {
        val overrides = StyleOverrides(
            apps = mapOf("com.whatsapp" to Category.EMAIL),
            fields = mapOf(StyleOverrides.fieldKey("com.whatsapp", "com.whatsapp:id/entry") to Category.NOTES),
            knobs = mapOf(Category.NOTES to KnobOverride(cleanShort = true)),
        )

        val resolved = url(viewId = "com.whatsapp:id/entry", overrides = overrides)

        assertEquals(Category.NOTES, resolved.category)
        assertEquals(StyleSource.FIELD, resolved.source)
        assertEquals(notes.copy(cleanShort = true), resolved.style)
    }

    @Test
    fun given_a_user_app_override_on_a_url_field_when_resolved_then_the_input_type_wins() {
        val resolved = url(overrides = StyleOverrides(apps = mapOf("com.whatsapp" to Category.EMAIL)))

        assertEquals(Category.FORM, resolved.category)
        assertEquals(StyleSource.INPUT_TYPE, resolved.source)
        assertEquals(form, resolved.style)
    }

    @Test
    fun given_each_form_variation_when_resolved_then_the_source_is_input_type() {
        assertEquals(StyleSource.INPUT_TYPE, url().source)
        assertEquals(StyleSource.INPUT_TYPE, resolve(null, text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, multiLine = true).source)
        assertEquals(StyleSource.INPUT_TYPE, resolve(null, text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, multiLine = true).source)
        assertEquals(StyleSource.INPUT_TYPE, resolve(null, text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, multiLine = true).source)
        assertEquals(StyleSource.INPUT_TYPE, resolve(null, text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, multiLine = true).source)
        assertEquals(StyleSource.INPUT_TYPE, resolve(null, text or InputType.TYPE_TEXT_VARIATION_FILTER, multiLine = true).source)
    }

    @Test
    fun given_a_user_app_override_when_resolved_then_the_source_is_app() {
        val resolved = resolve("com.example.unknown", multi, multiLine = true, overrides = StyleOverrides(apps = mapOf("com.example.unknown" to Category.NOTES)))

        assertEquals(Category.NOTES, resolved.category)
        assertEquals(StyleSource.APP, resolved.source)
        assertEquals(notes, resolved.style)
    }

    @Test
    fun given_a_user_app_override_on_a_shipped_app_when_resolved_then_the_user_wins() {
        val resolved = resolve("com.whatsapp", multi, multiLine = true, overrides = StyleOverrides(apps = mapOf("com.whatsapp" to Category.EMAIL)))

        assertEquals(Category.EMAIL, resolved.category)
        assertEquals(StyleSource.APP, resolved.source)
        assertEquals(email, resolved.style)
    }

    @Test
    fun given_a_user_app_override_on_a_short_message_when_resolved_then_the_app_wins() {
        val resolved = resolve(
            "com.example.unknown", shortMessage, multiLine = true,
            overrides = StyleOverrides(apps = mapOf("com.example.unknown" to Category.NOTES)),
        )

        assertEquals(Category.NOTES, resolved.category)
        assertEquals(StyleSource.APP, resolved.source)
    }

    @Test
    fun given_a_user_app_override_on_a_single_line_field_when_resolved_then_the_app_wins() {
        val resolved = resolve(
            "com.example.unknown", text, multiLine = false,
            overrides = StyleOverrides(apps = mapOf("com.example.unknown" to Category.EMAIL)),
        )

        assertEquals(Category.EMAIL, resolved.category)
        assertEquals(StyleSource.APP, resolved.source)
    }

    @Test
    fun given_a_shipped_app_when_resolved_then_the_source_is_builtin_app() {
        val resolved = resolve("com.whatsapp", multi, multiLine = true)

        assertEquals(Category.CHAT, resolved.category)
        assertEquals(StyleSource.BUILTIN_APP, resolved.source)
        assertEquals(chat, resolved.style)
    }

    @Test
    fun given_a_shipped_app_with_a_short_message_when_resolved_then_the_source_is_builtin_app() {
        val resolved = resolve("com.google.android.gm", shortMessage, multiLine = false)

        assertEquals(Category.EMAIL, resolved.category)
        assertEquals(StyleSource.BUILTIN_APP, resolved.source)
        assertEquals(email, resolved.style)
    }

    @Test
    fun given_a_shipped_app_with_a_single_line_field_when_resolved_then_the_source_is_builtin_app() {
        val resolved = resolve("com.snapchat.android", text, multiLine = false)

        assertEquals(Category.CHAT, resolved.category)
        assertEquals(StyleSource.BUILTIN_APP, resolved.source)
    }

    @Test
    fun given_a_shipped_app_with_no_input_type_when_resolved_then_the_source_is_builtin_app() {
        val resolved = resolve("com.discord", 0, multiLine = false)

        assertEquals(Category.CHAT, resolved.category)
        assertEquals(StyleSource.BUILTIN_APP, resolved.source)
    }

    @Test
    fun given_a_short_message_field_when_resolved_then_the_source_is_short_message() {
        val resolved = resolve(null, shortMessage, multiLine = true)

        assertEquals(Category.CHAT, resolved.category)
        assertEquals(StyleSource.SHORT_MESSAGE, resolved.source)
        assertEquals(chat, resolved.style)
    }

    @Test
    fun given_a_short_message_on_one_line_when_resolved_then_short_message_wins() {
        val resolved = resolve("com.example.unknown", shortMessage, multiLine = false)

        assertEquals(Category.CHAT, resolved.category)
        assertEquals(StyleSource.SHORT_MESSAGE, resolved.source)
    }

    @Test
    fun given_a_single_line_field_when_resolved_then_the_source_is_single_line() {
        val resolved = resolve("com.example.unknown", text, multiLine = false)

        assertEquals(Category.FORM, resolved.category)
        assertEquals(StyleSource.SINGLE_LINE, resolved.source)
        assertEquals(form, resolved.style)
    }

    @Test
    fun given_a_multi_line_field_in_an_unknown_app_when_resolved_then_the_source_is_default() {
        val resolved = resolve("com.example.unknown", multi, multiLine = true)

        assertEquals(Category.DEFAULT, resolved.category)
        assertEquals(StyleSource.DEFAULT, resolved.source)
        assertEquals(defaultStyle, resolved.style)
    }

    @Test
    fun given_no_package_when_resolved_then_the_source_is_default() {
        val resolved = resolve(null, multi, multiLine = true)

        assertEquals(Category.DEFAULT, resolved.category)
        assertEquals(StyleSource.DEFAULT, resolved.source)
    }

    @Test
    fun given_a_single_line_field_with_no_input_type_when_resolved_then_the_source_is_default() {
        val resolved = resolve("ai.x.grok", 0, multiLine = false)

        assertEquals(Category.DEFAULT, resolved.category)
        assertEquals(StyleSource.DEFAULT, resolved.source)
    }

    @Test
    fun given_a_non_text_field_when_resolved_then_the_source_is_default() {
        val resolved = resolve(null, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, multiLine = true)

        assertEquals(Category.DEFAULT, resolved.category)
        assertEquals(StyleSource.DEFAULT, resolved.source)
    }

    @Test
    fun given_a_hint_and_a_view_id_without_an_override_when_resolved_then_they_do_not_decide() {
        val plain = resolve("com.whatsapp", text, multiLine = true)

        assertEquals(plain, resolve("com.whatsapp", text, multiLine = true, hint = "Email address", viewId = "subject"))
    }

    @Test
    fun given_two_knob_overrides_when_resolved_then_they_merge_over_the_shipped_style() {
        val overrides = StyleOverrides(
            knobs = mapOf(Category.CHAT to KnobOverride(styling = "formal", casual = false, cleanShort = true)),
        )

        val resolved = resolve("com.whatsapp", multi, multiLine = true, overrides = overrides)

        assertEquals(chat.copy(styling = "formal", casual = false, cleanShort = true), resolved.style)
    }

    @Test
    fun given_a_knob_override_on_another_category_when_resolved_then_the_style_stays_shipped() {
        val overrides = StyleOverrides(knobs = mapOf(Category.EMAIL to KnobOverride(cleanShort = true)))

        val resolved = resolve("com.whatsapp", multi, multiLine = true, overrides = overrides)

        assertEquals(chat, resolved.style)
    }

    @Test
    fun given_a_false_knob_when_saved_then_it_overrides_the_shipped_true() {
        val store = StyleStore().setKnob(Category.DEFAULT, StyleStore.CASUAL, false)

        assertEquals(KnobOverride(casual = false), store.overrides().knobs.getValue(Category.DEFAULT))
        assertEquals(defaultStyle.copy(casual = false), resolve(null, multi, multiLine = true, overrides = store.overrides()).style)
    }

    @Test
    fun given_a_knob_when_saved_then_only_that_knob_is_stored_and_it_merges() {
        val store = StyleStore()
            .setKnob(Category.CHAT, StyleStore.CLEAN_SHORT, true)
            .setKnob(Category.CHAT, StyleStore.STYLING, "formal")

        assertEquals(KnobOverride(styling = "formal", cleanShort = true), store.overrides().knobs.getValue(Category.CHAT))
        assertEquals(chat.copy(styling = "formal", cleanShort = true), resolve("com.whatsapp", multi, multiLine = true, overrides = store.overrides()).style)
    }

    @Test
    fun given_a_knob_when_cleared_then_the_shipped_value_returns() {
        val store = StyleStore().setKnob(Category.CHAT, StyleStore.CLEAN_SHORT, true).clearKnob(Category.CHAT, StyleStore.CLEAN_SHORT)

        assertEquals(StyleOverrides(), store.overrides())
        assertEquals(chat, resolve("com.whatsapp", multi, multiLine = true, overrides = store.overrides()).style)
    }

    @Test
    fun given_an_unknown_knob_when_set_then_it_is_not_stored() {
        assertEquals(StyleOverrides(), StyleStore().setKnob(Category.DEFAULT, "color", "red").overrides())
    }

    @Test
    fun given_an_app_override_when_cleared_then_the_shipped_app_returns() {
        val store = StyleStore().setApp("com.whatsapp", Category.EMAIL).clearApp("com.whatsapp")
        val resolved = resolve("com.whatsapp", multi, multiLine = true, overrides = store.overrides())

        assertEquals(Category.CHAT, resolved.category)
        assertEquals(StyleSource.BUILTIN_APP, resolved.source)
    }

    @Test
    fun given_saved_app_and_field_overrides_when_resolved_then_the_field_beats_a_url_and_the_app_loses() {
        val store = StyleStore()
            .setApp("com.whatsapp", Category.EMAIL)
            .setField("com.whatsapp", "com.whatsapp:id/entry", Category.NOTES)
        val book = StyleBook(cfg, store.overrides())

        val url = book.resolve("com.whatsapp", text or InputType.TYPE_TEXT_VARIATION_URI, true, null, null)
        val field = book.resolve("com.whatsapp", text or InputType.TYPE_TEXT_VARIATION_URI, true, null, "com.whatsapp:id/entry")

        assertEquals(StyleSource.INPUT_TYPE, url.source)
        assertEquals(Category.FORM, url.category)
        assertEquals(StyleSource.FIELD, field.source)
        assertEquals(Category.NOTES, field.category)
        assertEquals(notes, field.style)
    }

    @Test
    fun given_malformed_overrides_when_loaded_then_the_bad_parts_are_ignored() {
        val store = StyleStore(mapOf(
            "style.chat" to "{",
            "style.email" to """{"casual":"no","clean_short":true}""",
            "app.com.example" to "folder",
            "field.a/b" to "chat",
            "not.a.style" to "chat",
        ))
        val book = StyleBook(cfg, store.overrides())

        assertEquals(email.copy(cleanShort = true), book.resolve("com.google.android.gm", multi, true, null, null).style)
        assertEquals(StyleSource.DEFAULT, book.resolve("com.example", multi, true, null, null).source)
        assertEquals(Resolved(Category.CHAT, chat, StyleSource.FIELD), book.resolve("a", text, true, null, "b"))
    }
}
