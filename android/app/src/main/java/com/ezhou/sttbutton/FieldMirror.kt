package com.ezhou.sttbutton

/** One change to a text: [deleted] units at [at] replaced by [inserted] units. Offsets are UTF-16, as the field's are. */
data class Edit(val at: Int, val deleted: Int, val inserted: Int)

/**
 * The field's text and selection as last seen; [sync] reports what changed since as one [Edit]. When
 * repeated characters let several positions explain a change, the one that ends at the selection's start is
 * reported, which is where typing and deleting leave the caret.
 */
class FieldMirror(private var text: String, selectionStart: Int = -1, selectionEnd: Int = selectionStart) {
    var selectionStart = selectionStart
        private set
    var selectionEnd = selectionEnd
        private set

    fun sync(now: String, selectionStart: Int? = null, selectionEnd: Int? = selectionStart): Edit? {
        val edit = if (now == text) null else explain(text, now, selectionStart)
        text = now
        selectionStart?.let { this.selectionStart = it }
        selectionEnd?.let { this.selectionEnd = it }
        return edit
    }

    /** The take's own write: what the field holds now, without reporting it as an edit. */
    fun wrote(now: String, selectionStart: Int, selectionEnd: Int) {
        text = now
        this.selectionStart = selectionStart
        this.selectionEnd = selectionEnd
    }

    private fun explain(old: String, now: String, caret: Int?): Edit {
        val limit = minOf(old.length, now.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == now[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == now[now.length - 1 - suffix]) suffix++
        val deleted = old.length - prefix - suffix
        val inserted = now.length - prefix - suffix
        var at = prefix
        if (caret != null) {
            var candidate = prefix
            while (true) {
                if (candidate + inserted == caret) {
                    at = candidate
                    break
                }
                if (candidate == 0 || old[candidate - 1 + deleted] != now[candidate - 1 + inserted]) break
                candidate--
            }
        }
        return Edit(at, deleted, inserted)
    }
}
