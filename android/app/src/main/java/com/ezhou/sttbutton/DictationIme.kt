package com.ezhou.sttbutton

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * The keyboard a take switches to, so the take owns the screen's bottom while it records. It shows
 * the stop key and nothing else.
 */
class DictationIme : InputMethodService() {
    private var panel: View? = null

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
        val onPick = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        return TakePanel(this, { ButtonStyle.load(prefs) }, { ImeLink.onStop?.invoke() }, onPick).also { panel = it }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val swap = ImeLink.swap
        val stranded = swap == null || swap.onViewStart(SystemClock.uptimeMillis()) == ViewStart.STRANDED
        if (stranded) ImeLink.onStranded?.invoke()
        refreshPanel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        ImeLink.onViewFinish?.invoke()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (FieldPolicy.exclusion(password = false, attribute.inputType) != null) ImeLink.onExcludedField?.invoke()
    }

    fun refreshPanel() {
        panel?.requestLayout()
        panel?.invalidate()
    }
}
