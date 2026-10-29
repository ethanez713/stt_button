package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonStyleTest {
    private val mic = Bounds(900, 1500, 1000, 1600)

    @Test
    fun given_the_defaults_when_placed_then_the_button_covers_the_microphone() {
        assertEquals(mic, ButtonStyle().bounds(mic))
    }

    @Test
    fun given_left_placement_when_placed_then_the_button_ends_where_the_microphone_begins() {
        assertEquals(Bounds(800, 1500, 900, 1600), ButtonStyle(placement = ButtonPlacement.LEFT_OF_MIC).bounds(mic))
    }

    @Test
    fun given_a_gap_when_placed_left_then_the_button_ends_that_many_pixels_from_the_microphone() {
        assertEquals(Bounds(750, 1500, 850, 1600), ButtonStyle(placement = ButtonPlacement.LEFT_OF_MIC, gapPx = 50).bounds(mic))
    }

    @Test
    fun given_a_gap_when_placed_over_the_microphone_then_it_is_ignored() {
        assertEquals(mic, ButtonStyle(gapPx = 80).bounds(mic))
    }

    @Test
    fun given_a_size_when_placed_then_the_button_scales_about_the_microphones_centre() {
        assertEquals(Bounds(925, 1525, 975, 1575), ButtonStyle(sizePct = 50).bounds(mic))
        assertEquals(Bounds(750, 1475, 900, 1625), ButtonStyle(ButtonPlacement.LEFT_OF_MIC, sizePct = 150).bounds(mic))
    }

    @Test
    fun given_an_opacity_when_colored_then_it_is_the_alpha_except_while_pressed_or_busy_grey() {
        val style = ButtonStyle(opacityPct = 40, idleRgb = 0x112233, recordingRgb = 0x445566)

        assertEquals(0x66112233, style.color(ButtonMode.IDLE))
        assertEquals(0x66445566, style.color(ButtonMode.RECORDING))
        assertEquals(0xFF112233.toInt(), style.color(ButtonMode.IDLE, pressed = true))
        assertEquals(0x669E9E9E, style.color(ButtonMode.BUSY))
        assertEquals(style.color(ButtonMode.BUSY), style.color(ButtonMode.CLEANING))
        assertEquals(style.color(ButtonMode.BUSY, pressed = true), style.color(ButtonMode.CLEANING, pressed = true))
    }

    @Test
    fun given_any_chosen_colors_when_failed_then_the_color_is_the_fixed_red_orange() {
        val faded = ButtonStyle(opacityPct = 5, idleRgb = 0x112233, recordingRgb = 0x445566)
        val solid = ButtonStyle(opacityPct = 100, idleRgb = 0x00FF00, recordingRgb = 0x0000FF)
        assertEquals(0xFFFF5722.toInt(), faded.color(ButtonMode.FAILED))
        assertEquals(faded.color(ButtonMode.FAILED), solid.color(ButtonMode.FAILED))
        assertEquals(faded.color(ButtonMode.FAILED), faded.color(ButtonMode.FAILED, pressed = true))
    }
}
