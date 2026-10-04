package com.ezhou.sttbutton

/** Where an editor's caret is. Indices are absolute positions in the field, or -1 when unknown. */
data class Caret(val start: Int, val end: Int) {
    val known: Boolean get() = start >= 0 && end >= 0
    val collapsed: Boolean get() = known && start == end

    companion object {
        val UNKNOWN = Caret(-1, -1)
    }
}

/** Identity of an editable that survives a refreshed accessibility node. */
data class EditorId(val windowId: Int, val viewId: String?, val className: String?)

object CaretWatch {
    /**
     * [selectionStart] and [selectionEnd] are indices into the surrounding-text window,
     * and [offset] is where that window starts in the field.
     */
    fun absolute(offset: Int, selectionStart: Int, selectionEnd: Int): Caret {
        if (selectionStart < 0 || selectionEnd < 0) return Caret.UNKNOWN
        return Caret(offset + selectionStart, offset + selectionEnd)
    }

    /** The user's caret after text of [textLength] is inserted at [insertAt]. A caret at the insert point stays put. */
    fun restored(userCaret: Int, insertAt: Int, textLength: Int): Int {
        if (userCaret < 0 || insertAt < 0) return userCaret
        return if (userCaret <= insertAt) userCaret else userCaret + textLength
    }

    /**
     * The user moved the caret or selected text. Carets in [ignored] are ones this take just wrote,
     * and they count only while [inGrace]: a late report of our own insert is not the user, and a
     * different caret during that window is.
     */
    fun userMoved(accepted: Caret, observed: Caret, ignored: Set<Int>, inGrace: Boolean): Boolean {
        if (!observed.known || !accepted.known) return false
        if (observed == accepted) return false
        if (inGrace && observed.collapsed && observed.start in ignored) return false
        if (inGrace && !observed.collapsed && (observed.start in ignored || observed.end in ignored)) return false
        return true
    }

    /** A different window or a different view id. Two nodes with no view id in one window are not distinguishable. */
    fun differentBox(was: EditorId, now: EditorId): Boolean {
        if (was.windowId != now.windowId) return true
        if (was.viewId != null && now.viewId != null) return was.viewId != now.viewId
        return false
    }

    fun sameEditor(a: EditorId, b: EditorId): Boolean {
        if (a.windowId != b.windowId) return false
        if (a.viewId != null && b.viewId != null) return a.viewId == b.viewId
        return a.className != null && a.className == b.className
    }
}
