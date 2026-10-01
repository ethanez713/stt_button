package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicPlacementTest {
    private val settled = Bounds(900, 1500, 1000, 1600)
    private val sliding = Bounds(900, 2200, 1000, 2300)
    private val halfway = Bounds(900, 1800, 1000, 1900)

    @Test
    fun given_rememberedRect_when_keyboardAppears_then_buttonShowsThereAtOnce() {
        val placement = MicPlacement().read(0, sliding, remembered = settled)
        assertEquals(settled, placement.show)
        assertTrue(placement.reread)
    }

    @Test
    fun given_changingReads_when_sliding_then_buttonStaysPut() {
        val p = MicPlacement()
        p.read(0, sliding, settled)
        val mid = p.read(100, halfway, settled)
        assertNull(mid.show)
        assertTrue(mid.reread)
    }

    @Test
    fun given_stableRead_when_slideEnds_then_buttonMovesAndRemembers() {
        val p = MicPlacement()
        p.read(0, sliding, settled)
        p.read(100, halfway, settled)
        p.read(200, Bounds(900, 1520, 1000, 1620), settled)
        val end = p.read(300, Bounds(900, 1520, 1000, 1620), settled)
        assertEquals(Bounds(900, 1520, 1000, 1620), end.show)
        assertEquals(Bounds(900, 1520, 1000, 1620), end.persist)
        assertFalse(end.reread)
        assertEquals(20, end.settle?.movedPx)
    }

    @Test
    fun given_readsThatNeverSettle_when_capReached_then_latestReadIsTaken() {
        val p = MicPlacement()
        p.read(0, sliding, settled)
        var last = Placement()
        var latest = settled
        for (t in 100..1500 step 100) {
            latest = Bounds(900, 1700 + t, 1000, 1800 + t)
            last = p.read(t.toLong(), latest, settled)
        }
        assertEquals(latest, last.show)
    }

    @Test
    fun given_noRememberedRect_when_keyboardAppears_then_firstReadShowsAndSettles() {
        val p = MicPlacement()
        val first = p.read(0, settled, remembered = null)
        assertEquals(settled, first.show)
        val second = p.read(100, settled, remembered = null)
        assertNull(second.show)
        assertEquals(settled, second.persist)
        assertFalse(second.settle!!.remembered)
    }

    @Test
    fun given_rememberedMatchesRead_when_keyboardAppears_then_noRereadNeeded() {
        val first = MicPlacement().read(0, settled, remembered = settled)
        assertEquals(settled, first.show)
        assertFalse(first.reread)
    }

    @Test
    fun given_settledButton_when_micMovesLater_then_itSettlesBeforeFollowing() {
        val p = MicPlacement()
        p.read(0, settled, settled)
        val moved = Bounds(900, 1000, 1000, 1100)
        assertNull(p.read(500, moved, settled).show)
        assertEquals(moved, p.read(600, moved, settled).show)
    }

    @Test
    fun given_reset_when_keyboardReturns_then_rememberedRectShowsAgain() {
        val p = MicPlacement()
        p.read(0, settled, settled)
        p.reset()
        assertEquals(settled, p.read(5000, sliding, settled).show)
    }
}
