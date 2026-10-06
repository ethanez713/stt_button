package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopKeyPlacementTest {
    @Test
    fun given_aButtonAboveTheScreensBottom_then_itsTopIsMeasuredFromThePanelsTop() {
        val key = StopKeyPlacement.inPanel(Bounds(1200, 2100, 1300, 2200), screenBottom = 3120, panelHeight = 1100)!!
        assertEquals(Bounds(1200, 80, 1300, 180), key)
    }

    @Test
    fun given_noButtonYet_then_nullSoThePanelUsesItsCorner() {
        assertNull(StopKeyPlacement.inPanel(null, 3120, 1100))
    }
}
