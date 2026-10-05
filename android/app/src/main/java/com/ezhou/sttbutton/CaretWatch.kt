package com.ezhou.sttbutton

/** Where an editor's caret is. Indices are absolute positions in the field, or -1 when unknown. */
data class Caret(val start: Int, val end: Int) {
    val known: Boolean get() = start >= 0 && end >= 0
    val collapsed: Boolean get() = known && start == end

    companion object {
        val UNKNOWN = Caret(-1, -1)
    }
}

object CaretWatch {
    /**
     * [selectionStart] and [selectionEnd] are indices into the surrounding-text window,
     * and [offset] is where that window starts in the field.
     */
    fun absolute(offset: Int, selectionStart: Int, selectionEnd: Int): Caret {
        if (selectionStart < 0 || selectionEnd < 0) return Caret.UNKNOWN
        return Caret(offset + selectionStart, offset + selectionEnd)
    }
}
