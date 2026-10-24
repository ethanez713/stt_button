package com.ezhou.sttbutton

enum class DocumentKind { FIELD, BUFFER }

object Documents {
    /** A take writes into the field when the port read all of it, and otherwise into memory to insert at the stop. */
    fun choose(read: FieldText?): DocumentKind = if (read?.whole == true) DocumentKind.FIELD else DocumentKind.BUFFER

    /**
     * What the dictated stretch should read at the end of a field take: the sign-off dropped, the casual rule when the
     * field held nothing before and holds nothing but the dictation now, no line breaks in a single-line field, and
     * the punctuation at the very end removed.
     */
    fun finalText(dictated: String, fieldBefore: String, fieldNow: String, multiLine: Boolean, cfg: PipelineConfig): String {
        val signedOff = Pipeline.withoutSignOff(dictated)
        val casual = if (fieldBefore.isEmpty() && fieldNow == dictated) Pipeline.casual(signedOff, cfg) else signedOff
        return Pipeline.trimMessageEnd(Pipeline.forField(casual, multiLine))
    }
}
