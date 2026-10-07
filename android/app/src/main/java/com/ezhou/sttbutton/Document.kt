package com.ezhou.sttbutton

class DocState(val text: String, val selectionStart: Int, val selectionEnd: Int)

/** What a take writes to. [replace] is the take's own write and is not reported; every other change is. */
interface Document {
    /** The text and selection, or null when the document is gone. */
    fun read(): DocState?

    /** False when the document is gone or the range is outside it. */
    fun replace(from: Int, to: Int, text: String): Boolean

    var onEdit: ((Edit) -> Unit)?
}

/** A string and a caret in memory: the document of a take whose text is inserted at the stop. */
class BufferDocument(initial: String = "") : Document {
    private var text = initial
    private var caret = initial.length
    override var onEdit: ((Edit) -> Unit)? = null

    override fun read() = DocState(text, caret, caret)

    override fun replace(from: Int, to: Int, text: String): Boolean {
        if (from < 0 || to < from || to > this.text.length) return false
        this.text = this.text.replaceRange(from, to, text)
        caret = when {
            caret <= from -> caret
            caret >= to -> caret + text.length - (to - from)
            else -> from + text.length
        }
        return true
    }

    /** A change the take did not make, as when the user types: the caret follows it and [onEdit] hears of it. */
    fun userEdit(from: Int, to: Int, text: String) {
        require(from in 0..to && to <= this.text.length)
        this.text = this.text.replaceRange(from, to, text)
        caret = from + text.length
        onEdit?.invoke(Edit(from, to - from, text.length))
    }
}
