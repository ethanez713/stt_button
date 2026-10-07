package com.ezhou.sttbutton

/**
 * A [Document] that is a text field, through an [EditorPort]. Every change to the field that the take
 * did not make reaches [onEdit] through [sync], whoever made it: the keys, a swipe, an autocorrection, a paste.
 */
class FieldDocument(private val port: EditorPort) : Document {
    override var onEdit: ((Edit) -> Unit)? = null
    private var mirror: FieldMirror? = null

    override fun read(): DocState? = whole()?.let { DocState(it.text, it.selectionStart, it.selectionEnd) }

    /** Reads the field and reports what changed since the last read; false when the field is gone or only partly readable. */
    fun sync(): Boolean {
        val field = whole() ?: return false
        reportChange(field)
        return true
    }

    override fun replace(from: Int, to: Int, text: String): Boolean {
        val field = whole() ?: return false
        if (from < 0 || to < from || to > field.text.length) return false
        reportChange(field)
        val start = moved(field.selectionStart, from, to, text.length)
        val end = moved(field.selectionEnd, from, to, text.length)
        if (!port.replace(from, to, text, start, end)) return false
        mirror?.wrote(field.text.replaceRange(from, to, text), start, end)
        return true
    }

    private fun whole(): FieldText? = port.read()?.takeIf { it.whole }

    private fun reportChange(field: FieldText) {
        val seen = mirror
        if (seen == null) {
            mirror = FieldMirror(field.text, field.selectionStart, field.selectionEnd)
            return
        }
        seen.sync(field.text, field.selectionStart, field.selectionEnd)?.let { onEdit?.invoke(it) }
    }

    /** A caret at or after [to] shifts with the text; one inside the replaced span goes to the span's new end. */
    private fun moved(offset: Int, from: Int, to: Int, length: Int) = when {
        offset >= to -> offset + length - (to - from)
        offset <= from -> offset
        else -> from + length
    }
}
