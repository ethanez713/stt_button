package com.ezhou.sttbutton

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Where this app's input method and its accessibility service meet. Both run in one process, so they find
 * each other here and never bind: the service sets the [host], the keyboard sets [current].
 */
object ImeLink {
    private val hostRef = AtomicReference<KeyboardHost?>()
    private var ime = WeakReference<DictationIme>(null)

    var host: KeyboardHost?
        get() = hostRef.get()
        set(value) = hostRef.set(value)

    var current: DictationIme?
        get() = ime.get()
        set(value) {
            ime = WeakReference(value)
        }
}
