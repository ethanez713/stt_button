package com.ezhou.sttbutton

/**
 * The policy of a take written into a text field. Chunks are cleaned at slices as [ChunkPolicy] does, and
 * at a pause the section the user paused after is cleaned in the field, abandonable when speech resumes and
 * joined to what follows by a space, since a pause is not a paragraph. Once nothing is left uncleaned, a
 * region that several cleans wrote is cleaned again as one piece, when `field_revise` says so and the cleaner
 * can take it whole. A region the user edited is left alone, since a clean would re-punctuate a mark they fixed.
 */
class RevisePolicy(private val cfg: PipelineConfig, private val chunks: CleanPolicy = ChunkPolicy(cfg)) : CleanPolicy {
    override val name = "revise"

    override fun next(view: PolicyView, moment: Moment): CleanPlan? {
        revision(view, moment)?.let { return it }
        val plan = chunks.next(view, moment) ?: return null
        if (moment != Moment.PAUSE) return plan
        return CleanPlan(plan.from, plan.to, plan.target, abandonable = true, speculative = false, local = plan.local, spaceAfter = true)
    }

    private fun revision(view: PolicyView, moment: Moment): CleanPlan? {
        val region = view.region
        val allowed = when (cfg.fieldRevise) {
            "pause" -> moment != Moment.SLICE
            "stop" -> moment == Moment.FINISH
            else -> false
        }
        if (!allowed || view.tail.isNotBlank() || region.revisionDropped || region.text.isBlank()) return null
        if (region.cleans <= 1 || region.editsInside > 0) return null
        if (view.countTokens(region.text) > cfg.chunkMaxTokens) return null
        return CleanPlan(region.start, region.start + region.text.length, region.text, abandonable = moment == Moment.PAUSE, region = true)
    }
}
