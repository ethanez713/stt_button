package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanPolicyTest {
    private val cfg = PipelineConfig(org.json.JSONObject(Fixtures.resource("pipeline.json")).apply {
        put("chunk_min_tokens", 15)
        put("skip_max_words", 5)
    })
    private val policy = ChunkPolicy(cfg)

    private fun sentences(prefix: String, count: Int) =
        (1..count).joinToString(" ") { i -> List(9) { "$prefix$i" }.joinToString(" ") + "." }

    private fun view(tail: String, start: Int = 0, cleanedAny: Boolean = false, kept: Set<String> = emptySet()) =
        PolicyView(tail, start, cleanedAny, kept, Pipeline::wordCount)

    private fun chunks(text: String, final: Boolean) =
        Pipeline.liveChunks(text, Pipeline::wordCount, cfg.chunkMinTokens, cfg.chunkMaxTokens, final, cfg.selfCorrectionCues)

    /** What the engine does at a moment: plan, then the tail after the plan's stretch, until no plan is left. */
    private fun drain(text: String, moment: Moment): List<CleanPlan> {
        val plans = mutableListOf<CleanPlan>()
        var tail = text
        var start = 0
        var kept = emptySet<String>()
        while (plans.size < 50) {
            val plan = policy.next(view(tail, start, cleanedAny = plans.isNotEmpty(), kept = kept), moment) ?: break
            plans += plan
            if (moment == Moment.PAUSE) kept = kept + plan.target
            else {
                tail = tail.substring(plan.to - start)
                start = plan.to
            }
        }
        return plans
    }

    @Test
    fun given_a_slice_when_a_chunk_has_closed_then_the_plan_is_the_first_closed_chunk_and_applied() {
        val text = sentences("a", 4)
        val plan = policy.next(view(text), Moment.SLICE)!!

        assertEquals(chunks(text, final = false).first(), plan.target)
        assertEquals(0, plan.from)
        assertEquals(plan.target, text.substring(plan.from, plan.to))
        assertFalse(plan.speculative)
        assertFalse(plan.abandonable)
    }

    @Test
    fun given_a_slice_when_no_chunk_has_closed_then_there_is_no_plan() {
        assertNull(policy.next(view(sentences("a", 1) + " " + sentences("b", 1).replace("b1", "b1")), Moment.SLICE))
        assertNull(policy.next(view(sentences("a", 2)), Moment.SLICE))
    }

    @Test
    fun given_slices_one_after_another_when_drained_then_the_plans_are_the_closed_chunks_in_order() {
        val text = sentences("a", 9)

        assertEquals(chunks(text, final = false), drain(text, Moment.SLICE).map { it.target })
    }

    @Test
    fun given_a_finish_when_drained_then_the_plans_are_every_final_chunk_and_applied() {
        val text = sentences("a", 7) + " Actually no, make that Wednesday. " + sentences("b", 2)
        val plans = drain(text, Moment.FINISH)

        assertEquals(chunks(text, final = true), plans.map { it.target })
        assertTrue(plans.none { it.speculative || it.abandonable })
    }

    @Test
    fun given_a_pause_when_drained_with_each_answer_kept_then_the_plans_are_every_final_chunk_speculative_and_abandonable() {
        val text = sentences("a", 7)
        val plans = drain(text, Moment.PAUSE)

        assertEquals(chunks(text, final = true), plans.map { it.target })
        assertTrue(plans.all { it.speculative && it.abandonable })
    }

    @Test
    fun given_a_pause_when_the_first_chunk_is_kept_then_the_plan_is_the_next_and_starts_where_it_does() {
        val text = sentences("a", 5)
        val all = chunks(text, final = true)
        val plan = policy.next(view(text, kept = setOf(all[0])), Moment.PAUSE)!!

        assertEquals(all[1], plan.target)
        assertEquals(all[1], text.substring(plan.from, plan.to))
    }

    @Test
    fun given_a_tail_that_follows_cleaned_text_when_planned_then_offsets_are_in_the_document() {
        val text = "CLEANED. " + sentences("a", 3)
        val plan = policy.next(view(text.substring(9), start = 9, cleanedAny = true), Moment.FINISH)!!

        assertEquals(9, plan.from)
        assertEquals(plan.target, text.substring(plan.from, plan.to))
    }

    @Test
    fun given_a_tail_with_irregular_whitespace_when_planned_then_the_stretch_still_covers_the_chunk() {
        val text = "one two three four five six seven.\n\n  eight nine ten eleven twelve thirteen fourteen fifteen."
        val plan = policy.next(view(text), Moment.FINISH)!!

        assertEquals(text.length, plan.to)
        assertEquals(0, plan.from)
    }

    @Test
    fun given_a_take_under_the_skip_rule_when_planned_then_only_the_finish_converts_it() {
        assertNull(policy.next(view("Meet me at noon."), Moment.SLICE))
        assertNull(policy.next(view("Meet me at noon."), Moment.PAUSE))
        val plan = policy.next(view("Meet me at noon."), Moment.FINISH)!!

        assertTrue(plan.local)
        assertEquals(0, plan.from)
        assertEquals(16, plan.to)
    }

    @Test
    fun given_a_skip_rule_take_with_a_cue_when_planned_then_it_is_cleaned() {
        assertFalse(policy.next(view("Meet Tuesday, actually Wednesday."), Moment.FINISH)!!.local)
    }

    @Test
    fun given_cleaned_text_before_a_short_tail_when_planned_then_the_skip_rule_does_not_apply() {
        assertFalse(policy.next(view("Short tail.", start = 20, cleanedAny = true), Moment.FINISH)!!.local)
    }

    @Test
    fun given_nothing_in_the_tail_when_planned_then_there_is_no_plan() {
        assertNull(policy.next(view("", cleanedAny = true), Moment.FINISH))
        assertNull(policy.next(view("  "), Moment.FINISH))
    }
}
