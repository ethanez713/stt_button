package com.ezhou.sttbutton

import android.inputmethodservice.InputMethodService
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
        return TakePanel(this, { ButtonStyle.load(prefs) }, { ImeLink.host?.stopKeyTapped() }, onPick, { ImeLink.host?.panel() ?: PanelState.EMPTY })
            .also { panel = it }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ImeLink.host?.viewStarted()
        refreshPanel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        ImeLink.host?.viewFinished()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (FieldPolicy.exclusion(password = false, attribute.inputType) != null) ImeLink.host?.inputStartedOnExcludedField()
    }

    fun refreshPanel() {
        panel?.requestLayout()
        panel?.invalidate()
    }
}
