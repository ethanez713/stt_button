package com.ezhou.sttbutton

import org.json.JSONObject

/** The values `config/pipeline.json` shares with the host library, plus the functions that apply them. */
class PipelineConfig(json: JSONObject) {
    val takeCapS = json.getInt("take_cap_s")
    val sliceMaxS = json.getDouble("slice_max_s")
    val sliceSearchS = json.getDouble("slice_search_s")
    val pauseS: Double? = if (json.isNull("pause_s")) null else json.getDouble("pause_s")
    val stopSilenceS: Double? = if (json.isNull("stop_silence_s")) null else json.getDouble("stop_silence_s")
    val stopSilenceDbfs = json.getDouble("stop_silence_dbfs")
    val sliceMinS = json.getDouble("slice_min_s")
    val contextS = json.getDouble("context_s")
    val junctionSqueezeS: Double? = if (json.isNull("junction_squeeze_s")) null else json.getDouble("junction_squeeze_s")
    val pauseMarginDb = json.getDouble("pause_margin_db")
    val chunkMinTokens = json.getInt("chunk_min_tokens")
    val chunkMaxTokens = json.getInt("chunk_max_tokens")
    val skipMaxWords = json.getInt("skip_max_words")
    val fieldRevise: String = json.optString("field_revise", "stop")
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
    private val OPENING_FILLERS = setOf("uh", "um", "oh", "or", "and", "but", "so", "well", "okay")
    private const val JUDGEABLE_WORDS = 3
    private val COMMA_OUTSIDE_NUMBER = Regex("(?<![0-9]),|,(?![0-9])")
    private val FINAL_PERIOD = Regex("(?<!\\.)\\.(\\s*)$")
    private val NOT_SPOKEN = Regex("[^\\p{L}\\p{N}_\\s]")
    private val TRAILING_FILLERS = setOf("um", "umm", "uh", "hmm")
    private val SIGN_OFFS = setOf("okay", "ok", "yeah", "yep", "alright")
    private val NOT_WORD_EDGE = Regex("^[^\\p{L}\\p{N}_']+|[^\\p{L}\\p{N}_']+$")
    private val NON_SPACE = Regex("(?U)\\S+")
    private val TRAILING_MARK = Regex("[.,?!;:]$")
    private const val CONTEXT_LEAD_S = 0.1
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

    /** Mirrors `stt_button.cleaner.without_sign_off`. */
    fun withoutSignOff(text: String): String {
        var current = text
        while (true) current = withoutOneSignOff(current) ?: return current
    }

    private fun withoutOneSignOff(text: String): String? {
        val spans = NON_SPACE.findAll(text).map { it.range }.toList()
        val words = spans.map { text.substring(it.first, it.last + 1) }
        val cores = words.map { NOT_WORD_EDGE.replace(it, "").lowercase() }
        if (words.size < 2) return null
        if (cores.last() in TRAILING_FILLERS) {
            val kept = text.substring(0, spans[spans.size - 2].last + 1).trimEnd(',', ';', ':')
            val ending = words.last().last().let { if (it in ".?!") it.toString() else "" }
            return if (kept.lastOrNull()?.let { it in ".?!" } == true) kept else kept + ending
        }
        for (size in 2 downTo 1) {
            if (words.size > size && cores.takeLast(size).all { it in SIGN_OFFS } &&
                words[words.size - size - 1].last() in ".?!" && words.last().last() !in "?!") {
                return text.substring(0, spans[spans.size - size - 1].last + 1)
            }
        }
        return null
    }

    /** Mirrors `stt_button.cleaner.casual`. */
    fun casual(text: String, cfg: PipelineConfig): String =
        if (wordCount(text) > cfg.skipMaxWords) text
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

    private const val CLOSING_MARKS = "\")]}\u201D\u2019'"

    /**
     * True when the text before the caret is the middle of a sentence: its last word is not a bare symbol such
     * as a bullet, and what ends it is neither a sentence end, an opening mark nor a line break.
     */
    fun continuesSentence(before: String, caret: Int): Boolean {
        val head = before.substring(0, if (caret in 0..before.length) caret else before.length)
        val text = head.trimEnd()
        if ('\n' in head.substring(text.length)) return false
        val word = text.takeLastWhile { !it.isWhitespace() }
        if (word.none { it.isLetterOrDigit() }) return false
        val ending = text.trimEnd { it in CLOSING_MARKS }.lastOrNull() ?: return false
        return ending !in ".?!" && ending !in OPENING_MARKS
    }

    /** [body] as written after [before] at [caret]: a take that carries on a sentence opens in lower case, except for "I" words and names with a capital inside. */
    fun continuing(body: String, before: String, caret: Int): String {
        if (!continuesSentence(before, caret)) return body
        val token = body.takeWhile { !it.isWhitespace() }
        val lead = token.takeWhile { !it.isLetterOrDigit() }
        val word = token.drop(lead.length).trimEnd { !it.isLetterOrDigit() && it != '\'' && it != '\u2019' }.replace('\u2019', '\'')
        if (word.isEmpty() || word.lowercase() in FIRST_PERSON || word.drop(1) != word.drop(1).lowercase()) return body
        return body.substring(0, lead.length) + body[lead.length].lowercaseChar() + body.substring(lead.length + 1)
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

    private fun spokenWords(text: String): List<String> =
        NOT_SPOKEN.replace(text.lowercase(), "").trim().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Mirrors `stt_button.cleaner.opens_with_cue`. */
    fun opensWithCue(sentence: String, cues: List<String>): Boolean {
        val words = spokenWords(sentence).dropWhile { it in OPENING_FILLERS }
        return cues.any { cue -> spokenWords(cue).let { it.isNotEmpty() && words.take(it.size) == it } }
    }

    fun isCorrectionUnit(chunk: String, cues: List<String>): Boolean =
        splitSentences(chunk).drop(1).any { opensWithCue(it, cues) }

    /** Mirrors `stt_button.cleaner.live_chunks`. */
    fun liveChunks(
        text: String, countTokens: (String) -> Int, minTokens: Int, maxTokens: Int, final: Boolean, cues: List<String> = emptyList(),
    ): List<String> {
        var sentences = splitSentences(text)
        var withheld: String? = null
        if (!final) {
            withheld = sentences.lastOrNull()
            sentences = sentences.dropLast(1)
        }
        val units = mutableListOf<Pair<String, Boolean>>()
        for (piece in pieces(sentences, countTokens, maxTokens)) {
            if (cues.isNotEmpty() && units.isNotEmpty() && opensWithCue(piece, cues)) units[units.lastIndex] = "${units.last().first} $piece" to true
            else units += piece to false
        }
        if (cues.isNotEmpty() && !final && units.isNotEmpty() &&
            (withheld == null || spokenWords(withheld).size < JUDGEABLE_WORDS || opensWithCue(withheld, cues))
        ) units.removeAt(units.lastIndex)
        val chunks = mutableListOf<String>()
        var current = ""
        for ((unit, corrects) in units) {
            if (corrects) {
                if (current.isNotEmpty()) chunks += current
                chunks += unit
                current = ""
                continue
            }
            current = if (current.isEmpty()) unit else "$current $unit"
            if (countTokens(current) >= minTokens) {
                chunks += current
                current = ""
            }
        }
        if (final && current.isNotEmpty()) chunks += current
        return chunks
    }

    /** Mirrors `stt_button.cleaner.unresolved_corrections`. */
    fun unresolvedCorrections(chunks: List<String>, outputs: List<String>, cues: List<String>): Pair<Int, Int> {
        val units = chunks.zip(outputs).filter { isCorrectionUnit(it.first, cues) }.map { it.second }
        return units.size to units.count { isCorrectionUnit(it, cues) }
    }

    /**
     * What sets a cleaned chunk off from the cleaned text before it, as `stt_button.cleaner.join_cleaned` does: nothing
     * for the first, a space inside a thought ([inline], or a correction [unit]) unless a list ends just before the
     * seam, and a paragraph break otherwise.
     */
    fun joinSeparator(cleanedBefore: String, output: String, inline: Boolean, unit: Boolean): String = when {
        output.isEmpty() || cleanedBefore.isEmpty() -> ""
        (inline || unit) && !cleanedBefore.substringAfterLast('\n').startsWith("- ") -> " "
        else -> "\n\n"
    }

    /** Mirrors `stt_button.cleaner.join_cleaned`. */
    fun joinCleaned(chunks: List<String>, outputs: List<String>, cues: List<String>): String {
        val text = StringBuilder()
        var inline = false
        for ((chunk, output) in chunks.zip(outputs)) {
            val unit = isCorrectionUnit(chunk, cues)
            text.append(joinSeparator(text.toString(), output, inline, unit)).append(output)
            inline = unit
        }
        return text.toString()
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
