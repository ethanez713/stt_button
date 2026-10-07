package com.ezhou.sttbutton

/**
 * A stretch `[start, end)` of a field's text that follows the field's edits. Text inserted exactly
 * at either edge falls outside it, and a stretch whose text was deleted collapses to `start == end`.
 */
data class Span(val start: Int, val end: Int) {
    fun apply(edit: Edit): Span {
        val newStart = moveStart(edit)
        return Span(newStart, maxOf(newStart, moveEnd(edit)))
    }

    private fun moveStart(edit: Edit): Int = when {
        start >= edit.at + edit.deleted -> start + edit.inserted - edit.deleted
        start <= edit.at -> start
        else -> edit.at
    }

    private fun moveEnd(edit: Edit): Int = when {
        end <= edit.at -> end
        end >= edit.at + edit.deleted -> end + edit.inserted - edit.deleted
        else -> edit.at
    }
}
