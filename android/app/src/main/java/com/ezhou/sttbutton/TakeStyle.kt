package com.ezhou.sttbutton

import android.text.InputType

enum class Category { DEFAULT, CHAT, EMAIL, NOTES, FORM }

data class Style(
    val styling: String,
    val structure: String,
    val casual: Boolean,
    val lowercaseStarts: Boolean,
    val keepEnd: Boolean,
    val cleanShort: Boolean = false,
)

/** Which rule chose a take's category. [StyleBook] adds [StyleSource.FIELD] and [StyleSource.APP] around [TakeStyle]. */
enum class StyleSource { FIELD, INPUT_TYPE, APP, BUILTIN_APP, SHORT_MESSAGE, SINGLE_LINE, DEFAULT }

internal data class Shipped(val category: Category, val source: StyleSource)

class TakeStyle(private val appCategories: Map<String, Category>) {
    /** [hint] and [viewId] are part of the field signal; no current rule reads them. */
    fun classify(pkg: String?, inputType: Int, multiLine: Boolean, hint: String?, viewId: String?): Category =
        shipped(pkg, inputType, multiLine).category

    /** The category the shipped rules give this field, and which rule. User overrides are not this class's. */
    internal fun shipped(pkg: String?, inputType: Int, multiLine: Boolean): Shipped {
        val kind = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        if (kind in FORM_VARIATIONS) return Shipped(Category.FORM, StyleSource.INPUT_TYPE)
        pkg?.let { appCategories[it] }?.let { return Shipped(it, StyleSource.BUILTIN_APP) }
        if (kind == SHORT_MESSAGE) return Shipped(Category.CHAT, StyleSource.SHORT_MESSAGE)
        // Compose text fields report TYPE_NULL and single-line whatever they hold, so neither says it is a form.
        if (!multiLine && inputType != InputType.TYPE_NULL) return Shipped(Category.FORM, StyleSource.SINGLE_LINE)
        return Shipped(Category.DEFAULT, StyleSource.DEFAULT)
    }

    private companion object {
        val FORM_VARIATIONS = setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER,
        )
        val SHORT_MESSAGE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
    }
}
