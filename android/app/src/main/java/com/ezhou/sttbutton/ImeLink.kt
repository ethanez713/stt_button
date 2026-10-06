package com.ezhou.sttbutton

import java.lang.ref.WeakReference

/**
 * What this app's input method and its accessibility service share. Both run in one process, so
 * they talk through this object and never bind to each other.
 */
object ImeLink {
    private var ime = WeakReference<DictationIme>(null)

    /** Where the stop key sits in the panel, in the panel's own coordinates; null puts it at the top-right corner. */
    @Volatile var stopKey: Bounds? = null

    /** The panel's height in px, or 0 for the default. */
    @Volatile var panelHeightPx = 0

    /** Called on the main thread when the stop key is tapped. */
    @Volatile var onStop: (() -> Unit)? = null

    /** The open take's swap, or null between takes; the keyboard's callbacks ask it what a view start means. */
    @Volatile var swap: SwapState? = null

    /** Put the user's keyboard back; set by the service, called by the keyboard when it is up with no take to hold it. */
    @Volatile var onStranded: (() -> Unit)? = null

    /** Called on the main thread when the keyboard's view closes. */
    @Volatile var onViewFinish: (() -> Unit)? = null

    /** Called on the main thread when the keyboard attaches to a field the button would not show on. */
    @Volatile var onExcludedField: (() -> Unit)? = null

    /** True when no keyboard could be returned to, so the panel offers the system's keyboard picker. */
    @Volatile var stranded = false

    /** True from the stop until the keyboard returns, so the panel can show the take is finishing. */
    @Volatile var busy = false

    var current: DictationIme?
        get() = ime.get()
        set(value) {
            ime = WeakReference(value)
        }
}
