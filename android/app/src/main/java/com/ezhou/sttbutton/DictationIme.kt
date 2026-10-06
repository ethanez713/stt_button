package com.ezhou.sttbutton

import android.inputmethodservice.InputMethodService
import android.view.View

/**
 * The keyboard a take switches to, so the take owns the screen's bottom while it records. It shows
 * the stop key and nothing else.
 */
class DictationIme : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        ImeLink.current = this
    }

    override fun onDestroy() {
        if (ImeLink.current === this) ImeLink.current = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val prefs = getSharedPreferences(ButtonStyle.PREFS, MODE_PRIVATE)
        return TakePanel(this, { ButtonStyle.load(prefs) }) { ImeLink.onStop?.invoke() }
    }
}
