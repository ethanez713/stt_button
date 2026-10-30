package com.ezhou.sttbutton

/**
 * A field take is cleaned once, after the stop's settle has left the region uncleaned. A slice or a pause
 * cleans nothing. At the finish the uncleaned tail is cleaned in [Pipeline.wholeChunks] pieces, first piece
 * first, as ordinary chunk plans. A region the user edited is left as they left it. A take under the skip
 * rule is only converted.
 */
class WholePolicy(private val cfg: PipelineConfig) : CleanPolicy {
    override val name = "whole"

    override fun next(view: PolicyView, moment: Moment): CleanPlan? {
        if (moment != Moment.FINISH) return null
        if (view.editsInside > 0) return null
        if (!view.cleanedAny && Pipeline.skipsCleaner(view.tail, cfg)) {
            return if (view.tail.isNotBlank()) {
                CleanPlan(view.tailStart, view.tailStart + view.tail.length, view.tail, local = true)
            } else {
                null
            }
        }
        val pieces = Pipeline.wholeChunks(view.tail, view.countTokens, cfg.chunkMaxTokens)
        val piece = pieces.firstOrNull() ?: return null
        val end = endOf(view.tail, piece) ?: return null
        val to = if (view.tail.trim() == piece) view.tail.length else end
        return CleanPlan(view.tailStart, view.tailStart + to, piece)
    }

    /** Where [piece]'s last word ends in [tail]. Whitespace the chunk joins with a single space does not matter. */
    private fun endOf(tail: String, piece: String): Int? {
        var at = 0
        var matched = false
        for (word in piece.split(WHITESPACE).filter { it.isNotEmpty() }) {
            while (at < tail.length && tail[at].isWhitespace()) at++
            if (!tail.startsWith(word, at)) return null
            at += word.length
            matched = true
        }
        return if (matched) at else null
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
