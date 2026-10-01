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
