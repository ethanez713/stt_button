package com.ezhou.sttbutton

class PreviewWord(val text: String, val startS: Double)

class BubbleContent(val settled: String, val preview: String)

/**
 * The preview bubble's text, the port of `stt_button.bubble.Bubble`: cleaned chunks, then the
 * recognizer's text for slices not yet cleaned, then the streaming model's words for the audio no slice
 * has covered. The worker and the preview thread both feed it; [onChange] runs on the feeding thread.
 */
class BubbleText : TakeSink {
    private var region: String? = null

    @Volatile var onChange: (() -> Unit)? = null
    private var hypothesis = emptyList<PreviewWord>()
    private val slices = mutableListOf<String>()
    private var coveredS = 0.0
    private val chunks = mutableListOf<Pair<String, String>>()

    fun preview(words: List<PreviewWord>) = update { hypothesis = words }

    /** The text of the take's region, which replaces the slices and chunks this holds. */
    fun region(text: String) = update { region = text }

    fun covered(endS: Double) = update { coveredS = maxOf(coveredS, endS) }

    override fun sliceLanded(endS: Double, text: String) = update {
        slices += text
        coveredS = maxOf(coveredS, endS)
    }

    override fun chunkCleaned(raw: String, cleaned: String) = update { chunks += raw to cleaned }

    @Synchronized
    fun text(): BubbleContent {
        region?.let { return BubbleContent(it, previewAfterDecodes()) }
        val raw = Pipeline.joinSlices(slices)
        var position = 0
        val blocks = mutableListOf<String>()
        for ((chunkRaw, cleaned) in chunks) {
            val found = raw.indexOf(chunkRaw, position)
            if (found < 0) continue
            position = found + chunkRaw.length
            if (cleaned.isNotEmpty()) blocks += cleaned
        }
        val remainder = raw.substring(position).trim()
        if (remainder.isNotEmpty()) blocks += remainder
        return BubbleContent(blocks.joinToString("\n\n"), previewAfterDecodes())
    }

    private fun previewAfterDecodes() = hypothesis.filter { it.startS >= coveredS }.joinToString(" ") { it.text }

    private fun update(change: () -> Unit) {
        synchronized(this) { change() }
        onChange?.invoke()
    }

    companion object {
        /** Mirrors `stt_button.bubble.words_from_tokens`: a token that starts with a space starts a word. */
        fun wordsFromTokens(tokens: Array<String>, timestamps: FloatArray): List<PreviewWord> {
            val words = mutableListOf<Pair<StringBuilder, Double>>()
            for ((i, token) in tokens.withIndex()) {
                if (token.startsWith(" ") || words.isEmpty()) words += StringBuilder(token.trim()) to timestamps[i].toDouble()
                else words.last().first.append(token)
            }
            return words.filter { it.first.isNotEmpty() }.map { PreviewWord(it.first.toString(), it.second) }
        }
    }
}
