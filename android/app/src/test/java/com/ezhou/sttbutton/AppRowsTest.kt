package com.ezhou.sttbutton

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRowsTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val shortMessage = text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
    private val uri = text or InputType.TYPE_TEXT_VARIATION_URI

    @Test
    fun given_shipped_seen_and_overridden_packages_when_rows_are_built_then_each_package_is_listed_once() {
        val seen = listOf(
            seen("com.example.seen", "id/box"),
            seen("com.whatsapp", "com.whatsapp:id/entry"),
        )
        val overrides = StyleOverrides(apps = mapOf(
            "com.example.saved" to Category.NOTES,
            "com.whatsapp" to Category.EMAIL,
        ))

        val rows = AppRows.rows(Fixtures.config, seen, overrides)

        assertEquals(
            listOf(
                "com.discord",
                "com.example.saved",
                "com.example.seen",
                "com.facebook.orca",
                "com.google.android.apps.messaging",
                "com.google.android.gm",
                "com.google.android.keep",
                "com.instagram.android",
                "com.microsoft.office.outlook",
                "com.samsung.android.app.notes",
                "com.samsung.android.messaging",
                "com.snapchat.android",
                "com.whatsapp",
                "org.telegram.messenger",
            ),
            rows.map { it.pkg },
        )
    }

    @Test
    fun given_each_kind_of_field_when_rows_are_built_then_the_source_names_the_rule() {
        val seen = listOf(
            seen("com.example.short", "id/s", inputType = shortMessage),
            seen("com.example.line", "id/l", inputType = text, multiLine = false),
            seen("com.example.plain", "id/p"),
            seen("com.example.url", "id/u", hint = "Link", inputType = uri),
            seen("com.example.recent", "id/old"),
            seen("com.example.recent", "id/new", inputType = text, multiLine = false),
        )
        val overrides = StyleOverrides(apps = mapOf(
            "com.example.chosen" to Category.NOTES,
            "com.example.short" to Category.EMAIL,
            "com.example.url" to Category.NOTES,
            "com.whatsapp" to Category.EMAIL,
        ))
        val byPkg = AppRows.rows(Fixtures.config, seen, overrides).associateBy { it.pkg }

        assertEquals(StyleSource.APP, byPkg.getValue("com.example.chosen").source)
        assertEquals(Category.NOTES, byPkg.getValue("com.example.chosen").category)
        assertEquals(emptyList<FieldRow>(), byPkg.getValue("com.example.chosen").fields)
        assertEquals(StyleSource.APP, byPkg.getValue("com.example.short").source)
        assertEquals(Category.EMAIL, byPkg.getValue("com.example.short").category)
        assertEquals(StyleSource.SINGLE_LINE, byPkg.getValue("com.example.line").source)
        assertEquals(Category.FORM, byPkg.getValue("com.example.line").category)
        assertEquals(StyleSource.DEFAULT, byPkg.getValue("com.example.plain").source)
        assertEquals(Category.DEFAULT, byPkg.getValue("com.example.plain").category)
        assertEquals(StyleSource.INPUT_TYPE, byPkg.getValue("com.example.url").source)
        assertEquals(Category.FORM, byPkg.getValue("com.example.url").category)
        assertEquals(StyleSource.SINGLE_LINE, byPkg.getValue("com.example.recent").source)
        assertEquals(Category.FORM, byPkg.getValue("com.example.recent").category)
        assertEquals(
            listOf(StyleSource.DEFAULT, StyleSource.SINGLE_LINE),
            byPkg.getValue("com.example.recent").fields.map { it.source },
        )
        assertEquals(StyleSource.APP, byPkg.getValue("com.whatsapp").source)
        assertEquals(Category.EMAIL, byPkg.getValue("com.whatsapp").category)
        assertEquals(StyleSource.BUILTIN_APP, byPkg.getValue("com.discord").source)
        assertEquals(Category.CHAT, byPkg.getValue("com.discord").category)
        assertEquals(StyleSource.BUILTIN_APP, byPkg.getValue("com.google.android.gm").source)
        assertEquals(Category.EMAIL, byPkg.getValue("com.google.android.gm").category)
    }

    @Test
    fun given_seen_fields_when_rows_are_built_then_each_field_resolves_on_its_own() {
        val seen = listOf(
            seen("com.whatsapp", "com.whatsapp:id/subject", "Subject", text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, true),
            seen("com.whatsapp", null, null, null, null),
            seen("com.whatsapp", "com.whatsapp:id/entry", "Message", multi, true),
        )
        val overrides = StyleOverrides(
            apps = mapOf("com.whatsapp" to Category.EMAIL),
            fields = mapOf(StyleOverrides.fieldKey("com.whatsapp", "com.whatsapp:id/entry") to Category.NOTES),
        )

        val row = AppRows.rows(Fixtures.config, seen, overrides).associateBy { it.pkg }.getValue("com.whatsapp")

        assertEquals(Category.NOTES, row.category)
        assertEquals(StyleSource.FIELD, row.source)
        assertEquals(listOf("com.whatsapp:id/subject", null, "com.whatsapp:id/entry"), row.fields.map { it.viewId })
        assertEquals(listOf("Subject", null, "Message"), row.fields.map { it.hint })
        assertEquals(listOf(StyleSource.INPUT_TYPE, StyleSource.APP, StyleSource.FIELD), row.fields.map { it.source })
        assertEquals(listOf(Category.FORM, Category.EMAIL, Category.NOTES), row.fields.map { it.category })
        assertEquals(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, row.fields[0].inputType)
        assertEquals(0, row.fields[1].inputType)
        assertEquals(true, row.fields[1].multiLine)
        assertEquals(multi, row.fields[2].inputType)
        assertEquals(true, row.fields[2].multiLine)
    }

    private fun seen(
        pkg: String,
        viewId: String?,
        hint: String? = null,
        inputType: Int? = multi,
        multiLine: Boolean? = true,
    ) = SeenField(pkg, viewId, hint, inputType, multiLine, null, null)
}
