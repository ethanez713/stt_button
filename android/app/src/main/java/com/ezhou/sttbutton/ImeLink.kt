package com.ezhou.sttbutton

import android.graphics.Rect
import java.lang.ref.WeakReference

/**
 * What this app's input method and its accessibility service share. Both run in one process, so
 * they talk through this object and never bind to each other.
 */
object ImeLink {
    private var ime = WeakReference<DictationIme>(null)

    /** Where the stop key sits in the panel, in the panel's own coordinates; null puts it at the top-right corner. */
    @Volatile var stopKey: Rect? = null

    /** The panel's height in px, or 0 for the default. */
    @Volatile var panelHeightPx = 0

    /** Called on the main thread when the stop key is tapped. */
    @Volatile var onStop: (() -> Unit)? = null

    /** True from the stop until the keyboard returns, so the panel can show the take is finishing. */
    @Volatile var busy = false

    var current: DictationIme?
        get() = ime.get()
        set(value) {
            ime = WeakReference(value)
        }
}
