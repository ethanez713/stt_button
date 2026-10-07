package com.ezhou.sttbutton

/** A string and a selection, with switches for a field that cannot be read or only partly. */
class FakeEditor(var text: String, var selectionStart: Int = text.length, var selectionEnd: Int = selectionStart) : EditorPort {
    var gone = false
    var partial = false
    var writes = 0
    var refuseWrites = false

    override fun read(): FieldText? =
        if (gone) null else FieldText(text, selectionStart, selectionEnd, whole = !partial)

    override fun replace(from: Int, to: Int, text: String, selectionStart: Int, selectionEnd: Int): Boolean {
        if (gone || refuseWrites) return false
        this.text = this.text.replaceRange(from, to, text)
        this.selectionStart = selectionStart
        this.selectionEnd = selectionEnd
        writes++
        return true
    }

    /** What the user's hands do: text changes and the caret lands after it. */
    fun type(at: Int, deleted: Int, inserted: String) {
        text = text.replaceRange(at, at + deleted, inserted)
        selectionStart = at + inserted.length
        selectionEnd = selectionStart
    }
}
