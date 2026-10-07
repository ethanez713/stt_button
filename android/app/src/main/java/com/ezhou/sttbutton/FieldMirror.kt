package com.ezhou.sttbutton

/** One change to a text: [deleted] units at [at] replaced by [inserted] units. Offsets are UTF-16, as the field's are. */
data class Edit(val at: Int, val deleted: Int, val inserted: Int)

/** The field's text as last seen; [sync] reports what changed since as one [Edit]. */
class FieldMirror(private var text: String) {
    fun sync(now: String): Edit? {
        if (now == text) return null
        val limit = minOf(text.length, now.length)
        var prefix = 0
        while (prefix < limit && text[prefix] == now[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && text[text.length - 1 - suffix] == now[now.length - 1 - suffix]) suffix++
        val edit = Edit(prefix, text.length - prefix - suffix, now.length - prefix - suffix)
        text = now
        return edit
    }
}
