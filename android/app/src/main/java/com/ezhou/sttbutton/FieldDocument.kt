package com.ezhou.sttbutton

/**
 * A [Document] that is a text field, through an [EditorPort]. Every change to the field that the take
 * did not make reaches [onEdit] through [sync], whoever made it: the keys, a swipe, an autocorrection, a paste.
 */
class FieldDocument(private val port: EditorPort, private val clock: () -> Long = { 0L }) : Document {
    override var onEdit: ((Edit) -> Unit)? = null
    private var mirror: FieldMirror? = null
    private var lastOutsideEditAt: Long? = null
    private var outsideSinceWrite = false

    /** Writes the take made, and changes it did not make. */
    var writes = 0
        private set
    var outsideEdits = 0
        private set

    /**
     * Gboard owes a space after a swiped word and drops it when the field changes under it, so a write waits
     * while the user's last change is under [TYPING_QUIET_MS] old, and while the caret follows a letter or
     * digit the user put there. A write never times out into a forced one. An unreadable field holds too: a
     * keyboard restart passes, and a field that is really gone is found by the take's input-session checks.
     */
    override fun writesHeld(): Boolean {
        val field = whole() ?: return true
        reportChange(field)
        val edited = lastOutsideEditAt
        if (edited != null && clock() - edited < TYPING_QUIET_MS) return true
        return outsideSinceWrite && field.text.getOrNull(field.selectionStart - 1)?.isLetterOrDigit() == true
    }

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
        outsideSinceWrite = false
        writes++
        return true
    }

    private fun whole(): FieldText? = port.read()?.takeIf { it.whole }

    private fun reportChange(field: FieldText) {
        val seen = mirror
        if (seen == null) {
            mirror = FieldMirror(field.text, field.selectionStart, field.selectionEnd)
            return
        }
        seen.sync(field.text, field.selectionStart, field.selectionEnd)?.let {
            lastOutsideEditAt = clock()
            outsideSinceWrite = true
            outsideEdits++
            onEdit?.invoke(it)
        }
    }

    /** A caret at or after [to] shifts with the text; one inside the replaced span goes to the span's new end. */
    private fun moved(offset: Int, from: Int, to: Int, length: Int) = when {
        offset >= to -> offset + length - (to - from)
        offset <= from -> offset
        else -> from + length
    }

    companion object {
        const val TYPING_QUIET_MS = 700L
    }
}
