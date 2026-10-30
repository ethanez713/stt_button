package com.ezhou.sttbutton

/** One decode: [junctionS] is where the slice starts inside context audio, null for a plain decode. [settle] is a window of the stop's whole-take pass. */
class RecognizeRequest(val samples: FloatArray, val junctionS: Double? = null, val settle: Boolean = false)

/** The decoded words; [mark] is the closing punctuation the recognizer gave the context before a junction. */
class Recognized(val text: String, val mark: String? = null)

interface Recognizer {
    fun recognize(request: RecognizeRequest): Recognized
}

/** [target] is the text to clean. */
class CleanRequest(val target: String)

class CleanResult(val text: String, val outTokens: Int)

interface Cleaner {
    fun countTokens(text: String): Int

    /** The token counter once the cleaner has loaded, null before; unlike [countTokens] it never waits. */
    val tokenCounter: ((String) -> Int)? get() = ::countTokens

    /** Null when [cancel] was called while generating. */
    fun clean(request: CleanRequest): CleanResult?
    fun cancel()
}
