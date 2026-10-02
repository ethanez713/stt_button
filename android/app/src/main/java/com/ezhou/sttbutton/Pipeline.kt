package com.ezhou.sttbutton

import org.json.JSONObject

/** The values `config/pipeline.json` shares with the host library, plus the functions that apply them. */
class PipelineConfig(json: JSONObject) {
    val takeCapS = json.getInt("take_cap_s")
    val sliceMaxS = json.getDouble("slice_max_s")
    val sliceSearchS = json.getDouble("slice_search_s")
    val pauseS: Double? = if (json.isNull("pause_s")) null else json.getDouble("pause_s")
    val insertPauseS: Double? = if (json.isNull("insert_pause_s")) null else json.getDouble("insert_pause_s")
    val sliceMinS = json.getDouble("slice_min_s")
    val contextS = json.getDouble("context_s")
    val junctionSqueezeS: Double? = if (json.isNull("junction_squeeze_s")) null else json.getDouble("junction_squeeze_s")
    val pauseMarginDb = json.getDouble("pause_margin_db")
    val chunkMinTokens = json.getInt("chunk_min_tokens")
    val chunkMaxTokens = json.getInt("chunk_max_tokens")
    val skipMaxWords = json.getInt("skip_max_words")
    val cleanerLoadAfterS = json.getDouble("cleaner_load_after_s")
    val selfCorrectionCues: List<String> = json.getJSONArray("self_correction_cues").let { a -> List(a.length(), a::getString) }
    private val cleaner = json.getJSONObject("cleaners").getJSONObject(json.getString("cleaner"))
    val systemPrompt: String = cleaner.getString("system_prompt")
    val controlLine: String? = if (cleaner.isNull("control_line")) null else cleaner.getString("control_line")
    val maxNewTokensRatio = cleaner.getDouble("max_new_tokens_ratio")
    val maxNewTokensPad = cleaner.getInt("max_new_tokens_pad")
}

object Pipeline {
    private const val ASSISTANT_NO_THINK = "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    private const val END_OF_TURN = "<|im_end|>"
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val COMMA_OUTSIDE_NUMBER = Regex("(?<![0-9]),|,(?![0-9])")
    private val FINAL_PERIOD = Regex("(?<!\\.)\\.(\\s*)$")
    private val NOT_SPOKEN = Regex("[^\\p{L}\\p{N}_\\s]")
    private val TRAILING_MARK = Regex("[.,?!;:]$")
    private const val CONTEXT_LEAD_S = 0.1
    const val SENTENCE_MARKS = ".,?!;:"
    val SENTENCE_ENDS = listOf(".", "?", "!")
    private const val OPENING_MARKS = "([{\u201C\u2018"
    private val SENTENCE_ENDS_ASKED = listOf("?", "!")

    fun renderPrompt(systemPrompt: String, userTurn: String): String =
        "<|im_start|>system\n$systemPrompt$END_OF_TURN\n<|im_start|>user\n$userTurn$END_OF_TURN\n$ASSISTANT_NO_THINK"

    fun promptFor(cfg: PipelineConfig, chunk: String): String =
        renderPrompt(cfg.systemPrompt, cfg.controlLine?.let { "$it\n$chunk" } ?: chunk)

    fun maxNewTokens(cfg: PipelineConfig, inputTokens: Int): Int =
        (cfg.maxNewTokensRatio * inputTokens + cfg.maxNewTokensPad).toInt()

    fun joinSlices(texts: List<String>): String = texts.filter { it.isNotEmpty() }.joinToString(" ")

    fun splitSentences(text: String): List<String> = SENTENCE_BREAK.split(text.trim()).filter { it.isNotEmpty() }

    fun wordCount(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }

    fun matchedCue(text: String, cues: List<String>): String? {
        val spoken = NOT_SPOKEN.replace(text.lowercase(), "")
        return cues.firstOrNull { cue ->
            val words = cue.trim().split(WHITESPACE).joinToString("\\s+") { Regex.escape(it) }
            Regex("(?<![a-z])$words(?![a-z])").containsMatchIn(spoken)
        }
    }

    fun skipsCleaner(text: String, cfg: PipelineConfig): Boolean =
        wordCount(text) <= cfg.skipMaxWords && matchedCue(text, cfg.selfCorrectionCues) == null

    /** Mirrors `stt_button.cleaner.casual`. */
    fun casual(text: String, cfg: PipelineConfig, judged: String = text): String =
        if (wordCount(judged) > cfg.skipMaxWords) text
        else FINAL_PERIOD.replace(COMMA_OUTSIDE_NUMBER.replace(text, ""), "$1")

    private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_]+$")
    private val FIRST_PERSON = setOf("i", "i'm", "i'll", "i've", "i'd")
    private val SENTENCE_END = Regex("[.?!]$")
    private const val DRIFT_MAX_EDITS = 2

    private fun factTokens(text: String): Map<String, Int> {
        val facts = mutableMapOf<String, Int>()
        var opens = true
        for (word in text.trim().split(WHITESPACE).filter { it.isNotEmpty() }) {
            val token = EDGE_PUNCTUATION.replace(word, "")
            if (token.isNotEmpty() && token.lowercase() !in FIRST_PERSON && (token.any { it.isDigit() } || (token[0].isUpperCase() && !opens))) {
                facts.merge(token.lowercase(), 1, Int::plus)
            }
            opens = SENTENCE_END.containsMatchIn(word)
        }
        return facts
    }

    private fun edits(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val above = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = above
            }
        }
        return row[b.length]
    }

    /** Mirrors `stt_button.cleaner.fact_drift`. */
    fun factDrift(raw: String, cleaned: String): List<Pair<String, String>> {
        val before = factTokens(raw)
        val after = factTokens(cleaned)
        val lost = before.keys.filter { t ->
            if (t.any { it.isDigit() }) (after[t] ?: 0) < before.getValue(t) else t !in after
        }.sorted()
        val gained = after.keys.filter { t ->
            if (t.any { it.isDigit() }) after.getValue(t) > (before[t] ?: 0) else t !in before
        }.sorted()
        return lost.flatMap { m ->
            gained.filter { n ->
                m.length > 1 && n.length > 1 && edits(m, n) <= (if (minOf(m.length, n.length) > 3) DRIFT_MAX_EDITS else 1)
            }.map { n -> m to n }
        }
    }

    fun trailingMark(text: String): String = TRAILING_MARK.find(text)?.value ?: ""

    fun withoutTrailingMark(text: String): String = TRAILING_MARK.replace(text, "")

    /**
     * What a take's first insert starts with: a space when the character before the caret is neither
     * white space nor an opening bracket or quote, so dictation after existing text does not run into it.
     */
    fun takeOpener(before: String, caret: Int): String {
        val at = if (caret in 0..before.length) caret else before.length
        val previous = before.getOrNull(at - 1) ?: return ""
        return if (previous.isWhitespace() || previous in OPENING_MARKS) "" else " "
    }

    /** Mirrors `stt_button.asr.settled_mark`. */
    fun settledMark(sliceMark: String, contextMark: String): String =
        if (sliceMark in SENTENCE_ENDS_ASKED && contextMark in SENTENCE_ENDS) sliceMark else contextMark

    /** Mirrors `stt_button.asr.context_audio`; [read] returns the samples in [from, to). */
    fun contextAudio(
        read: (Int, Int) -> FloatArray, speechBefore: Int, speechAfter: Int, end: Int, contextS: Double, squeezeS: Double?,
    ): Pair<FloatArray, Double> {
        val rate = Slicer.SAMPLE_RATE
        val before = read(maxOf(0, speechBefore - (contextS * rate).toInt()), speechBefore)
        val afterStart = maxOf(speechBefore, speechAfter - (CONTEXT_LEAD_S * rate).toInt())
        var gap = read(speechBefore, afterStart)
        if (squeezeS != null) gap = gap.copyOf(minOf(gap.size, (squeezeS * rate).toInt()))
        val audio = before + gap + read(afterStart, end)
        return audio to (before.size + gap.size).toDouble() / rate
    }

    /** Mirrors `stt_button.asr.split_at_junction`: the context's closing punctuation and the slice's words. */
    fun splitAtJunction(tokens: List<String>, times: List<Double>, junctionS: Double): Pair<String, String> {
        if (times.size != tokens.size) return "" to ""
        val words = BubbleText.wordsFromTokens(tokens.toTypedArray(), FloatArray(times.size) { times[it].toFloat() })
        val context = words.filter { it.startS < junctionS }
        return trailingMark(context.lastOrNull()?.text ?: "") to words.filter { it.startS >= junctionS }.joinToString(" ") { it.text }
    }

    /**
     * A segment that continues the sentence before it keeps the raw words' lower case: the cleaner
     * capitalizes the first word of every chunk it is given.
     */
    fun continueSentence(cleaned: String, raw: String): String {
        val cleanedWord = cleaned.trimStart().split(WHITESPACE).firstOrNull().orEmpty()
        val rawWord = raw.trimStart().split(WHITESPACE).firstOrNull().orEmpty()
        val key = { word: String -> word.filter { it.isLetterOrDigit() }.lowercase() }
        val first = cleaned.indexOfFirst { !it.isWhitespace() }
        if (first < 0 || rawWord.isEmpty() || key(cleanedWord) != key(rawWord)) return cleaned
        val rawLetter = rawWord.firstOrNull { it.isLetter() } ?: return cleaned
        return if (rawLetter.isLowerCase() && cleaned[first].isUpperCase()) cleaned.replaceRange(first, first + 1, cleaned[first].lowercase()) else cleaned
    }

    /** Mirrors `stt_button.cleaner.live_chunks`. */
    fun liveChunks(text: String, countTokens: (String) -> Int, minTokens: Int, maxTokens: Int, final: Boolean): List<String> {
        var sentences = splitSentences(text)
        if (!final) sentences = sentences.dropLast(1)
        val chunks = mutableListOf<String>()
        var current = ""
        for (piece in pieces(sentences, countTokens, maxTokens)) {
            current = if (current.isEmpty()) piece else "$current $piece"
            if (countTokens(current) >= minTokens) {
                chunks += current
                current = ""
            }
        }
        if (final && current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun pieces(sentences: List<String>, countTokens: (String) -> Int, maxTokens: Int): List<String> =
        sentences.flatMap { if (countTokens(it) <= maxTokens) listOf(it) else splitWords(it, countTokens, maxTokens) }

    private fun splitWords(sentence: String, countTokens: (String) -> Int, limit: Int): List<String> {
        val parts = mutableListOf<String>()
        var current = ""
        for (word in sentence.trim().split(WHITESPACE).filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && countTokens(candidate) > limit) {
                parts += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }
}
