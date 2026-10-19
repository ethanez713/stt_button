package com.ezhou.sttbutton

import android.accessibilityservice.InputMethod.AccessibilityInputConnection

/**
 * The focused field through the accessibility service's input connection, for as long as [sameField] says the
 * keyboard is still on the editor the take began in. The connection has no batch edit, so a replacement is
 * three calls.
 */
class A11yEditorPort(
    private val connection: () -> AccessibilityInputConnection?,
    private val sameField: () -> Boolean,
) : EditorPort {
    override fun read(): FieldText? {
        val surrounding = try {
            live()?.getSurroundingText(FieldRead.WINDOW, FieldRead.WINDOW, 0)
        } catch (e: Exception) {
            null
        } ?: return null
        val text = surrounding.text?.toString() ?: return null
        val kind = FieldRead.classify(surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd, text.length)
        return FieldText(text, surrounding.selectionStart, surrounding.selectionEnd, whole = kind in FieldRead.WHOLE)
    }

    override fun replace(from: Int, to: Int, text: String, selectionStart: Int, selectionEnd: Int): Boolean {
        val input = live() ?: return false
        return try {
            input.setSelection(from, to)
            input.commitText(text, 1, null)
            input.setSelection(selectionStart, selectionEnd)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun live(): AccessibilityInputConnection? = if (sameField()) connection() else null
}
