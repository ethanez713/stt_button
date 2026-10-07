package com.ezhou.sttbutton

object SpanLocator {
    /** Where [inserted] sits in [fieldText]: ending at [caretAfter] when the text there matches, else its one occurrence, else null. */
    fun find(fieldText: String, inserted: String, caretAfter: Int?): Span? {
        if (inserted.isEmpty()) return null
        if (caretAfter != null) {
            val start = caretAfter - inserted.length
            if (start >= 0 && caretAfter <= fieldText.length && fieldText.startsWith(inserted, start)) return Span(start, caretAfter)
        }
        val first = fieldText.indexOf(inserted)
        if (first < 0 || fieldText.indexOf(inserted, first + 1) >= 0) return null
        return Span(first, first + inserted.length)
    }
}
