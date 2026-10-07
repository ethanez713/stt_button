package com.ezhou.sttbutton

class PreviewWord(val text: String, val startS: Double)

class BubbleContent(val settled: String, val preview: String)

/**
 * The preview bubble's text: the take's region as the engine last wrote it, then the streaming model's
 * words for the audio no decode has covered. The engine and the preview thread both feed it; [onChange]
 * runs on the feeding thread.
 */
class BubbleText {
    @Volatile var onChange: (() -> Unit)? = null
    private var hypothesis = emptyList<PreviewWord>()
    private var region = ""
    private var coveredS = 0.0

    fun preview(words: List<PreviewWord>) = update { hypothesis = words }

    /** The text of the take's region, which is the text the stop will insert. */
    fun region(text: String) = update { region = text }

    /** Decodes have covered the audio up to [endS]. */
    fun covered(endS: Double) = update { coveredS = maxOf(coveredS, endS) }

    @Synchronized
    fun text(): BubbleContent =
        BubbleContent(region, hypothesis.filter { it.startS >= coveredS }.joinToString(" ") { it.text })

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
