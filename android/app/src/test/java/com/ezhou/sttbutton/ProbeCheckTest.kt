package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeCheckTest {
    private val text = "alpha beta gamma"

    @Test fun replacementBeforeTheCaretMovesIt() = assertEquals("alpha BETAS gamma" to 17, ProbeCheck.expected(text, 16, 6, 10, "BETAS"))
    @Test fun replacementContainingTheCaretLandsItAtTheStart() = assertEquals("alpha BETA gamma" to 6, ProbeCheck.expected(text, 8, 6, 10, "BETA"))
    @Test fun replacementAfterTheCaretLeavesIt() = assertEquals("alpha beta GAMMA" to 3, ProbeCheck.expected(text, 3, 11, 16, "GAMMA"))
    @Test fun replacementOfAnotherLengthMovesTheCaretByTheDifference() = assertEquals("alpha b gamma" to 13, ProbeCheck.expected(text, 16, 6, 10, "b"))
    @Test fun firstDiffFindsTheOffset() {
        assertEquals(6, ProbeCheck.firstDiff("alpha beta", "alpha Beta"))
        assertNull(ProbeCheck.firstDiff("same", "same"))
    }
}
