package com.ezhou.sttbutton

/**
 * Tells a microphone that has stopped delivering from one that is merely slow. A blocking
 * `AudioRecord.read` returns a negative code once the recorder is dead, and returns 0 at once,
 * every time, when the system took the microphone away; either would spin the recorder thread.
 */
class ReadHealth(private val zeroReadLimit: Int = 50) {
    private var zeroReads = 0

    /** Why capture cannot go on after a read returned [n], or null while it can. */
    fun failure(n: Int): String? {
        if (n < 0) return "AudioRecord.read returned $n"
        zeroReads = if (n == 0) zeroReads + 1 else 0
        return if (zeroReads >= zeroReadLimit) "AudioRecord.read returned no audio $zeroReads times in a row" else null
    }
}
