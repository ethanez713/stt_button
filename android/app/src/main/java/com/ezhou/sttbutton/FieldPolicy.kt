package com.ezhou.sttbutton

import android.text.InputType

/**
 * Fields the button stays off. Only a positive identification hides it: a node that reports no
 * input type or no editability still gets the button, because web fields often report neither.
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
}
