package com.ezhou.sttbutton

import android.content.SharedPreferences

enum class ButtonPlacement { OVER_MIC, LEFT_OF_MIC }

/**
 * How the button looks and where it sits relative to Gboard's microphone, as the user set it in the
 * app. Over the microphone it replaces Gboard's dictation; left of it both stay tappable.
 */
data class ButtonStyle(
    val placement: ButtonPlacement = ButtonPlacement.OVER_MIC,
    val sizePct: Int = 100,
    val opacityPct: Int = 60,
    val gapPx: Int = 0,
    val idleRgb: Int = PRESETS[0],
    val recordingRgb: Int = PRESETS[1],
) {
    /** The button's bounds for a microphone at [mic]: scaled about its centre, or ending [gapPx] left of the microphone. */
    fun bounds(mic: Bounds): Bounds {
        val width = (mic.right - mic.left) * sizePct / 100
        val height = (mic.bottom - mic.top) * sizePct / 100
        val left = if (placement == ButtonPlacement.LEFT_OF_MIC) mic.left - gapPx - width else (mic.left + mic.right - width) / 2
        val top = (mic.top + mic.bottom - height) / 2
        return Bounds(left, top, left + width, top + height)
    }

    fun color(mode: ButtonMode, pressed: Boolean = false): Int {
        val rgb = when (mode) {
            ButtonMode.IDLE -> idleRgb
            ButtonMode.RECORDING -> recordingRgb
            ButtonMode.BUSY -> BUSY_RGB
        }
        val alpha = if (pressed) 255 else 255 * opacityPct / 100
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_PLACEMENT, placement.name).putInt(KEY_SIZE, sizePct).putInt(KEY_OPACITY, opacityPct)
            .putInt(KEY_GAP, gapPx)            .putInt(KEY_IDLE, idleRgb).putInt(KEY_RECORDING, recordingRgb).apply()
    }

    companion object {
        const val PREFS = "overlay"
        val SIZE_RANGE = 20..150
        val OPACITY_RANGE = 5..100
        val GAP_RANGE = 0..200
        val PRESETS = listOf(0x00BCD4, 0xE53935, 0x43A047, 0xFB8C00, 0x8E24AA, 0x1E88E5, 0xFDD835, 0x546E7A)
        private const val BUSY_RGB = 0x9E9E9E
        private const val KEY_PLACEMENT = "style_placement"
        private const val KEY_SIZE = "style_size_pct"
        private const val KEY_OPACITY = "style_opacity_pct"
        private const val KEY_GAP = "style_gap_px"
        private const val KEY_IDLE = "style_idle_rgb"
        private const val KEY_RECORDING = "style_recording_rgb"
        val KEYS = setOf(KEY_PLACEMENT, KEY_SIZE, KEY_OPACITY, KEY_GAP, KEY_IDLE, KEY_RECORDING)

        fun load(prefs: SharedPreferences): ButtonStyle {
            val defaults = ButtonStyle()
            return ButtonStyle(
                ButtonPlacement.entries.firstOrNull { it.name == prefs.getString(KEY_PLACEMENT, null) } ?: defaults.placement,
                prefs.getInt(KEY_SIZE, defaults.sizePct).coerceIn(SIZE_RANGE),
                prefs.getInt(KEY_OPACITY, defaults.opacityPct).coerceIn(OPACITY_RANGE),
                prefs.getInt(KEY_GAP, defaults.gapPx).coerceIn(GAP_RANGE),
                prefs.getInt(KEY_IDLE, defaults.idleRgb),
                prefs.getInt(KEY_RECORDING, defaults.recordingRgb),
            )
        }
    }
}
