package com.ezhou.sttbutton

/** What a field should hold after a replacement, by the same offset rule as [Span]. */
object ProbeCheck {
    fun expected(text: String, caret: Int, from: Int, to: Int, replacement: String): Pair<String, Int> {
        val edit = Edit(from, to - from, replacement.length)
        val moved = when {
            caret <= edit.at -> caret
            caret >= edit.at + edit.deleted -> caret + edit.inserted - edit.deleted
            else -> edit.at
        }
        return text.replaceRange(from, to, replacement) to moved
    }

    fun firstDiff(a: String, b: String): Int? {
        if (a == b) return null
        val limit = minOf(a.length, b.length)
        var at = 0
        while (at < limit && a[at] == b[at]) at++
        return at
    }
}
