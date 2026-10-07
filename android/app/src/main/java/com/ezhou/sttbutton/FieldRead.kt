package com.ezhou.sttbutton

/** How completely an input connection's `getSurroundingText` showed a field. */
object FieldRead {
    const val FULL = "full"
    const val OFFSET_UNKNOWN = "offset_unknown"
    const val PARTIAL = "partial"
    const val NONE = "none"
    const val WINDOW = 100_000

    /** Kinds whose text is the whole field, so a position in it is a position in the field. */
    val WHOLE = setOf(FULL, OFFSET_UNKNOWN)

    /**
     * `full` when the text starts at the field's start and neither side filled the [WINDOW] asked for;
     * `offset_unknown` when the app reports no offset (-1) and neither side filled it, which is read as
     * starting at 0; otherwise `partial`.
     */
    fun classify(offset: Int, selectionStart: Int, selectionEnd: Int, textLength: Int): String {
        val short = selectionStart in 0 until WINDOW && textLength - selectionEnd < WINDOW
        return when {
            !short -> PARTIAL
            offset == 0 -> FULL
            offset == -1 -> OFFSET_UNKNOWN
            else -> PARTIAL
        }
    }

    /** The field's position of a selection offset in a read of kind [kind]. */
    fun position(offset: Int, selection: Int): Int = maxOf(offset, 0) + selection
}
