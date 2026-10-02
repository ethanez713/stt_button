package com.ezhou.sttbutton

import android.text.InputType

/**
 * Which fields get the button. A field's kind hides it only on a positive identification: a node
 * that reports no input type or no editability still gets the button, because web fields often
 * report neither.
 */
object FieldPolicy {
    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    /** Why the button must stay off this field, or null when nothing rules it out. */
    fun exclusion(password: Boolean, inputType: Int): String? = when {
        password -> "password field"
        (inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)) in PASSWORD_VARIATIONS -> "password field"
        else -> when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> "number field"
            InputType.TYPE_CLASS_PHONE -> "phone field"
            InputType.TYPE_CLASS_DATETIME -> "date field"
            else -> null
        }
    }

    /**
     * Whether the editor the keyboard is attached to stands in for a focused field the service
     * could not read. It has to belong to the focused window: on the way out of an app the
     * keyboard stays attached to the app left behind for up to a second, and a window without an
     * editor reports one of TYPE_NULL.
     */
    fun editorIsField(inputStarted: Boolean, inputType: Int, editorPackage: String?, focusedPackage: String?): Boolean =
        inputStarted && inputType != InputType.TYPE_NULL && editorPackage != null && editorPackage == focusedPackage
}
