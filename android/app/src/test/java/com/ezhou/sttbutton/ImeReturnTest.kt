package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeReturnTest {
    private val gboard = "com.google.android.inputmethod.latin/.LatinIME"

    @Test
    fun given_savedIdAndSubtype_when_ourKeyboardIsAlive_then_subtypeThenPreviousThenController() {
        val steps = ImeReturn.plan(SavedIme(gboard, 42), gboardId = "other/.Ime", imeAlive = true)
        assertEquals(
            listOf(
                ReturnStep(ReturnMethod.SUBTYPE, gboard, 42),
                ReturnStep(ReturnMethod.PREVIOUS, null, null),
                ReturnStep(ReturnMethod.CONTROLLER, gboard, null),
            ),
            steps,
        )
    }

    @Test
    fun given_savedIdWithoutSubtype_then_theSwitchCarriesNoSubtype() {
        val steps = ImeReturn.plan(SavedIme(gboard, null), gboardId = null, imeAlive = true)
        assertEquals(ReturnStep(ReturnMethod.SUBTYPE, gboard, null), steps.first())
    }

    @Test
    fun given_nothingSavedAndGboardEnabled_then_gboardIsTheTarget() {
        val steps = ImeReturn.plan(null, gboardId = gboard, imeAlive = true)
        assertEquals(listOf(gboard, null, gboard), steps.map { it.imeId })
    }

    @Test
    fun given_nothingSavedAndNoGboard_then_onlyPreviousIsLeft() {
        assertEquals(listOf(ReturnMethod.PREVIOUS), ImeReturn.plan(null, null, imeAlive = true).map { it.method })
    }

    @Test
    fun given_nothingToTryAtAll_then_theListIsEmpty() {
        assertEquals(emptyList<ReturnStep>(), ImeReturn.plan(null, null, imeAlive = false))
    }

    @Test
    fun given_ourKeyboardIsGone_then_onlyTheControllerIsLeft() {
        val steps = ImeReturn.plan(SavedIme(gboard, 42), gboardId = gboard, imeAlive = false)
        assertEquals(listOf(ReturnStep(ReturnMethod.CONTROLLER, gboard, null)), steps)
    }
}
