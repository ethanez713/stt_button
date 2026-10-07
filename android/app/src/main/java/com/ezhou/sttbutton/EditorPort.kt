package com.ezhou.sttbutton

/** A field's text and selection; [whole] says the text is all of it. */
class FieldText(val text: String, val selectionStart: Int, val selectionEnd: Int, val whole: Boolean)

/**
 * This app's view of an input connection. A replacement is `setSelection(from, to)`, `commitText(text, 1)`
 * and `setSelection(selectionAfter)`; the connection has no batch edit and no composing region.
 */
interface EditorPort {
    /** Null when the field cannot be read at all. */
    fun read(): FieldText?

    /** False when the field is gone. */
    fun replace(from: Int, to: Int, text: String, selectionStart: Int, selectionEnd: Int): Boolean
}
