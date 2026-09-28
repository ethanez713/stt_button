package com.ezhou.sttbutton

/** Targets along the bottom edge that a dragged button can be dropped on. */
enum class DropZone {
    /** Put the button back on Gboard's microphone. */
    RESET,

    /** Hide the button until it is turned back on in the app. */
    HIDE;

    companion object {
        fun at(x: Float, y: Float, screenWidth: Int, screenHeight: Int, zoneHeight: Int): DropZone? = when {
            y < screenHeight - zoneHeight -> null
            x < screenWidth / 2f -> HIDE
            else -> RESET
        }
    }
}
