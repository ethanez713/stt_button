package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

/** Window bounds as the SM-S938U reports them: 3120 px tall, the keyboard resting at 2018..3120. */
class KeyboardSlideTest {
    private val screenBottom = 3120
    private val height = 1102

    private fun KeyboardSlide.at(top: Int) = read(top, top + height, screenBottom)

    @Test
    fun given_topsRising_when_theKeyboardSlidesIn_then_arrivingUntilItsBottomMeetsTheScreens() {
        val slide = KeyboardSlide()
        for (top in listOf(3084, 2680, 2124, 2091, 2042, 2030)) assertEquals(KeyboardMotion.ARRIVING, slide.at(top))
        assertEquals(KeyboardMotion.RESTING, slide.at(2018))
    }

    @Test
    fun given_restingKeyboard_when_itsTopFallsThreePixels_then_leavingAtOnce() {
        val slide = KeyboardSlide()
        slide.at(2018)
        assertEquals(KeyboardMotion.LEAVING, slide.at(2021))
        assertEquals(KeyboardMotion.LEAVING, slide.at(2741))
    }

    @Test
    fun given_leavingKeyboard_when_theSameBoundsAreReadAgain_then_stillLeaving() {
        val slide = KeyboardSlide()
        slide.at(2018)
        slide.at(2741)
        assertEquals(KeyboardMotion.LEAVING, slide.at(2741))
    }

    @Test
    fun given_leavingKeyboard_when_itsTopRisesAgain_then_arriving() {
        val slide = KeyboardSlide()
        slide.at(2018)
        slide.at(2741)
        assertEquals(KeyboardMotion.ARRIVING, slide.at(2400))
    }

    @Test
    fun given_restingKeyboard_when_theEmojiPanelMakesItTaller_then_stillResting() {
        val slide = KeyboardSlide()
        slide.at(2018)
        assertEquals(KeyboardMotion.RESTING, slide.read(1621, screenBottom, screenBottom))
        assertEquals(KeyboardMotion.RESTING, slide.at(2018))
    }

    @Test
    fun given_keyboardGone_when_firstSeenMidSlide_then_arriving() {
        val slide = KeyboardSlide()
        slide.at(2018)
        slide.at(2741)
        slide.gone()
        assertEquals(KeyboardMotion.ARRIVING, slide.at(2680))
    }
}
