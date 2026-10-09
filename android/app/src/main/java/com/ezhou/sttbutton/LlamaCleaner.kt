package com.ezhou.sttbutton

/** S1-mini through llama.cpp with prompt-lookup decoding. [clean] blocks; [cancel] may come from any thread. */
class LlamaCleaner(modelPath: String, threads: Int = THREADS) : AutoCloseable {
    private val handle = LlamaNative.load(modelPath, threads)

    init {
        require(handle != 0L) { "cannot load $modelPath" }
    }

    fun countTokens(text: String): Int = LlamaNative.countTokens(handle, text.toByteArray())

    /**
     * Null when cancelled. Special tokens are read in [head] and [tail] only, so [text], the transcript, cannot
     * spell its way out of the user turn.
     */
    fun clean(head: String, text: String, tail: String, maxTokens: Int): String? =
        LlamaNative.clean(handle, head.toByteArray(), text.toByteArray(), tail.toByteArray(), maxTokens)?.toString(Charsets.UTF_8)

    fun cancel() = LlamaNative.cancel(handle)

    fun stats(): Stats = LlamaNative.lastStats(handle).let { Stats(it[0], it[1], it[2], it[3]) }

    override fun close() = LlamaNative.release(handle)

    data class Stats(val outTokens: Int, val drafted: Int, val accepted: Int, val threads: Int)

    companion object {
        const val THREADS = 6
    }
}
