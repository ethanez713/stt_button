package com.ezhou.sttbutton

import android.text.InputType

object FieldPolicy {
    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    fun isDictatable(editable: Boolean, password: Boolean, inputType: Int): Boolean {
        if (!editable || password) return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> false
            else -> (inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)) !in PASSWORD_VARIATIONS
        }
    }
}
