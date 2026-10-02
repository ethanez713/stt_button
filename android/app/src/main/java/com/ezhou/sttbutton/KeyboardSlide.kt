package com.ezhou.sttbutton

enum class KeyboardMotion { RESTING, ARRIVING, LEAVING, SCALED }

/**
 * Reads the keyboard's motion off successive bounds of its window. The window rests with its
 * bottom edge on the screen's; while that edge is below the screen it is sliding, in when its top
 * rises and out when its top falls. While that edge is above the screen's the window is drawn
 * smaller than it rests, as it is while the app under it animates open or closed, and its top
 * edge says nothing about which way it is going.
 */
class KeyboardSlide {
    private var lastTop: Int? = null
    private var motion = KeyboardMotion.RESTING

    fun gone() {
        lastTop = null
        motion = KeyboardMotion.RESTING
    }

    fun read(top: Int, bottom: Int, screenBottom: Int): KeyboardMotion {
        val previous = lastTop
        lastTop = top
        motion = when {
            bottom == screenBottom -> KeyboardMotion.RESTING
            bottom < screenBottom -> KeyboardMotion.SCALED
            previous == null || top < previous -> KeyboardMotion.ARRIVING
            top > previous -> KeyboardMotion.LEAVING
            // The same bounds read again mid-slide: still going the way it was.
            motion == KeyboardMotion.LEAVING -> KeyboardMotion.LEAVING
            else -> KeyboardMotion.ARRIVING
        }
        return motion
    }
}
