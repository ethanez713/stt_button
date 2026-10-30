package com.ezhou.sttbutton

/** `SLICE`: a slice just landed. `PAUSE`: the slicer is paused and no slice waits. `FINISH`: the take has stopped. */
enum class Moment { SLICE, PAUSE, FINISH }

/**
 * What a policy sees of a take's region: the uncleaned [tail] starting at document offset [tailStart],
 * whether any of the region has been cleaned, the [kept] targets a speculative clean has already answered,
 * a token counter, and how many edits the user made inside the region.
 */
class PolicyView(
    val tail: String,
    val tailStart: Int,
    val cleanedAny: Boolean,
    val kept: Set<String>,
    val countTokens: (String) -> Int,
    val editsInside: Int = 0,
)

/**
 * A stretch `[from, to)` of the document to clean. A speculative plan's output is set aside under [target]
 * and not written; a later plan over the same text completes from it. An [abandonable] plan is cancelled when
 * speech resumes. A [local] plan needs no model: the stretch becomes `Spoken.convert(target)`.
 */
class CleanPlan(
    val from: Int,
    val to: Int,
    val target: String,
    val abandonable: Boolean = false,
    val speculative: Boolean = false,
    val local: Boolean = false,
)

/** Decides what is cleaned when; the engine does the cleaning and the writing. */
interface CleanPolicy {
    val name: String
    fun next(view: PolicyView, moment: Moment): CleanPlan?
}

/**
 * Whole sentences in chunks, as the host library's take does: at a slice the first closed chunk of the
 * tail; at a pause each chunk of the tail, speculatively, so what a take inserts depends neither on where
 * its pauses fell nor on how fast its audio arrived; at the finish the same chunks, applied. A take under
 * the skip rule is not cleaned, and its tail is only converted at the finish.
 */
class ChunkPolicy(private val cfg: PipelineConfig) : CleanPolicy {
    override val name = "chunk"

    override fun next(view: PolicyView, moment: Moment): CleanPlan? {
        if (!view.cleanedAny && Pipeline.skipsCleaner(view.tail, cfg)) return skipPlan(view, moment)
        val chunks = chunksOf(view, final = moment != Moment.SLICE)
        val speculative = moment == Moment.PAUSE
        val index = if (speculative) chunks.indexOfFirst { it !in view.kept } else if (chunks.isEmpty()) -1 else 0
        if (index < 0) return null
        val span = spanOf(view.tail, chunks.take(index + 1)) ?: return null
        return CleanPlan(
            from = view.tailStart + if (index == 0) 0 else span.first, to = view.tailStart + span.second,
            target = chunks[index], abandonable = speculative, speculative = speculative,
        )
    }

    private fun skipPlan(view: PolicyView, moment: Moment): CleanPlan? =
        if (moment == Moment.FINISH && view.tail.isNotBlank()) CleanPlan(view.tailStart, view.tailStart + view.tail.length, view.tail, local = true)
        else null

    private fun chunksOf(view: PolicyView, final: Boolean) =
        Pipeline.liveChunks(view.tail, view.countTokens, cfg.chunkMinTokens, cfg.chunkMaxTokens, final, cfg.selfCorrectionCues)

    /** Where the last of [chunks] sits in [tail], by walking their words in order, so whitespace the chunk normalizes does not matter. */
    private fun spanOf(tail: String, chunks: List<String>): Pair<Int, Int>? {
        var at = 0
        var start = 0
        for (chunk in chunks) {
            start = -1
            for (word in chunk.split(WHITESPACE).filter { it.isNotEmpty() }) {
                while (at < tail.length && tail[at].isWhitespace()) at++
                if (!tail.startsWith(word, at)) return null
                if (start < 0) start = at
                at += word.length
            }
        }
        return start to at
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
