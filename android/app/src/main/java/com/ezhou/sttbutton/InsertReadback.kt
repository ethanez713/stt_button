package com.ezhou.sttbutton

/** What a read of the field showed after a write was attempted. */
object InsertReadback {
    enum class Verdict { LANDED, UNCHANGED, DIVERGED, BLIND }

    /** [readable] is false when the editor exposed no text, which is not the same as an empty field. */
    data class View(val text: String, val readable: Boolean)

    /**
     * The write landed when the field holds the utterance more times than it did before. Holding it
     * at all proves nothing: a short phrase ("see", "that") is usually in the field already.
     * Over 500 characters, gaining the first or last 400 counts as well: a field that rewrites line
     * breaks, or a caret window that cannot hold the utterance, still shows an end.
     */
    fun judge(before: View, after: View, needle: String): Verdict = measure(before, after, needle).verdict

    /**
     * Copies the verdict's view gained: the node when its verdict is [combine]'s, otherwise the caret
     * text. Null when the read is blind.
     */
    fun landedCount(nodeBefore: View, nodeAfter: View, caretBefore: View, caretAfter: View, needle: String): Int? {
        val node = measure(nodeBefore, nodeAfter, needle)
        val caret = measure(caretBefore, caretAfter, needle)
        val verdict = combine(node.verdict, caret.verdict)
        if (verdict == Verdict.BLIND) return null
        return if (node.verdict == verdict) node.gained else caret.gained
    }

    private class Measured(val verdict: Verdict, val gained: Int?)

    private fun measure(before: View, after: View, needle: String): Measured {
        val wanted = needle.trim()
        if (wanted.isEmpty()) return Measured(Verdict.LANDED, null)
        if (!after.readable) return Measured(Verdict.BLIND, null)
        val gained = copiesGained(before, after, wanted)
        val verdict = when {
            gained > 0 -> Verdict.LANDED
            before.readable && before.text == after.text -> Verdict.UNCHANGED
            else -> Verdict.DIVERGED
        }
        return Measured(verdict, gained)
    }

    private fun copiesGained(before: View, after: View, wanted: String): Int {
        val full = delta(before, after, wanted)
        if (full > 0 || wanted.length <= LONG_UTTERANCE) return full
        val byEnd = maxOf(
            delta(before, after, wanted.take(END_CHARS)),
            delta(before, after, wanted.takeLast(END_CHARS)),
        )
        return if (byEnd > 0) byEnd else full
    }

    private fun delta(before: View, after: View, wanted: String): Int {
        val had = if (before.readable) occurrences(before.text, wanted) else 0
        return occurrences(after.text, wanted) - had
    }

    private fun occurrences(text: String, wanted: String): Int {
        var count = 0
        var at = text.indexOf(wanted, ignoreCase = true)
        while (at >= 0) {
            count++
            at = text.indexOf(wanted, at + wanted.length, ignoreCase = true)
        }
        return count
    }

    /**
     * Either source seeing the text is enough. A readable node that did not change wins over the
     * caret window, because moving the caret slides that window without the field changing.
     */
    fun combine(node: Verdict, caretText: Verdict): Verdict = when {
        node == Verdict.LANDED || caretText == Verdict.LANDED -> Verdict.LANDED
        node == Verdict.UNCHANGED -> Verdict.UNCHANGED
        node == Verdict.DIVERGED -> Verdict.DIVERGED
        caretText == Verdict.DIVERGED -> Verdict.DIVERGED
        caretText == Verdict.UNCHANGED -> Verdict.UNCHANGED
        else -> Verdict.BLIND
    }

    /**
     * The next write to try, or null when another write would duplicate a commit the field hid,
     * or would replace an edit. [commitMissing] means nothing was committed, so a blind field may
     * still be pasted into. Set-text needs a readable node, and an empty one only when the caret
     * text agrees the field is empty, so a node that hides its text is not wiped.
     */
    fun nextPath(
        verdict: Verdict, commitMissing: Boolean, allowSetText: Boolean, triedPaste: Boolean, triedSetText: Boolean,
    ): String? {
        val open = verdict == Verdict.UNCHANGED || (verdict == Verdict.BLIND && commitMissing)
        if (!open) return null
        if (!triedPaste) return "paste"
        if (!triedSetText && allowSetText && verdict == Verdict.UNCHANGED) return "set_text"
        return null
    }

    fun allowSetText(node: View, caretText: View): Boolean {
        if (!node.readable) return false
        if (node.text.isNotEmpty()) return true
        return caretText.readable && caretText.text.isEmpty()
    }

    private const val LONG_UTTERANCE = 500
    private const val END_CHARS = 400
}
