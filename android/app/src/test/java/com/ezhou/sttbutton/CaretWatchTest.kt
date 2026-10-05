package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

class CaretWatchTest {
    @Test
    fun given_a_surrounding_text_window_when_the_selection_is_inside_it_then_the_caret_is_absolute() {
        assertEquals(Caret(12, 12), CaretWatch.absolute(10, 2, 2))
    }

    @Test
    fun given_an_unknown_selection_when_made_absolute_then_it_stays_unknown() {
        assertEquals(Caret.UNKNOWN, CaretWatch.absolute(0, -1, -1))
    }
}
