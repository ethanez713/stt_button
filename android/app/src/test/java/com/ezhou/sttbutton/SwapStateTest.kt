package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapStateTest {
    @Test
    fun given_aSwitchThatReturnedFalse_when_started_then_failedAndNothingToReturn() {
        val swap = SwapState.started(switchReturned = false, nowMs = 0)
        assertEquals(SwapPhase.FAILED, swap.phase)
        assertFalse(swap.returnOwed)
    }

    @Test
    fun given_pending_when_theViewStartsInsideASecond_then_live() {
        val swap = SwapState.started(true, 100)
        assertEquals(SwapPhase.PENDING, swap.phase)
        assertEquals(ViewStart.LIVE, swap.onViewStart(1_099))
        assertEquals(SwapPhase.LIVE, swap.phase)
    }

    @Test
    fun given_pending_when_aSecondPasses_then_pollFailsItOnceAndOnlyOnce() {
        val swap = SwapState.started(true, 100)
        assertFalse(swap.poll(1_099))
        assertTrue(swap.poll(1_100))
        assertEquals(SwapPhase.FAILED, swap.phase)
        assertFalse(swap.poll(2_000))
    }

    @Test
    fun given_pendingPastItsTimeout_when_theViewStarts_then_strandedNotLive() {
        val swap = SwapState.started(true, 0)
        assertEquals(ViewStart.STRANDED, swap.onViewStart(1_000))
        assertEquals(SwapPhase.FAILED, swap.phase)
    }

    @Test
    fun given_failed_when_aViewStartsLate_then_stranded() {
        val swap = SwapState.started(true, 0)
        swap.poll(1_000)
        assertEquals(ViewStart.STRANDED, swap.onViewStart(5_000))
    }

    @Test
    fun given_live_when_theViewStartsAgain_then_stayed() {
        val swap = SwapState.started(true, 0)
        swap.onViewStart(10)
        assertEquals(ViewStart.STAYED, swap.onViewStart(20_000))
        assertEquals(SwapPhase.LIVE, swap.phase)
    }

    @Test
    fun given_off_when_aViewStarts_then_stranded() {
        assertEquals(ViewStart.STRANDED, SwapState.off().onViewStart(0))
    }

    @Test
    fun given_aSwitchThatSucceeded_when_returned_then_noLongerOwed() {
        val swap = SwapState.started(true, 0)
        swap.poll(1_000)
        assertTrue(swap.returnOwed)
        swap.markReturned()
        assertFalse(swap.returnOwed)
    }

    @Test
    fun given_eachPhase_then_onlyPendingAndLiveHoldTheTake() {
        assertFalse(SwapState.off().holdsTake)
        assertFalse(SwapState.started(false, 0).holdsTake)
        val swap = SwapState.started(true, 0)
        assertTrue(swap.holdsTake)
        swap.onViewStart(1)
        assertTrue(swap.holdsTake)
        val timedOut = SwapState.started(true, 0).also { it.poll(1_000) }
        assertFalse(timedOut.holdsTake)
    }
}
