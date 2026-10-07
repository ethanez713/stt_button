package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldReadTest {
    @Test fun offsetZeroWithShortSidesIsFull() = assertEquals(FieldRead.FULL, FieldRead.classify(0, 16, 16, 16))

    @Test fun offsetMinusOneWithShortSidesIsOffsetUnknown() = assertEquals(FieldRead.OFFSET_UNKNOWN, FieldRead.classify(-1, 50, 50, 58))

    @Test fun aFilledSideIsPartialWhateverTheOffset() {
        assertEquals(FieldRead.PARTIAL, FieldRead.classify(0, FieldRead.WINDOW, FieldRead.WINDOW, 3 * FieldRead.WINDOW))
        assertEquals(FieldRead.PARTIAL, FieldRead.classify(-1, 10, 10, 10 + FieldRead.WINDOW))
    }

    @Test fun anotherOffsetIsPartial() = assertEquals(FieldRead.PARTIAL, FieldRead.classify(40, 10, 10, 60))

    @Test fun anUnknownSelectionIsPartial() = assertEquals(FieldRead.PARTIAL, FieldRead.classify(0, -1, -1, 5))

    @Test fun aPositionTreatsAnUnknownOffsetAsZero() {
        assertEquals(50, FieldRead.position(-1, 50))
        assertEquals(12, FieldRead.position(0, 12))
    }
}
