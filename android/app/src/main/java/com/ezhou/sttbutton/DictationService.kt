package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

class DictationService : AccessibilityService() {
    private lateinit var overlay: Overlay
    private var english = false
    private var lastState: String? = null
    private var imeTreeLoggedFor: Int? = null

    // TYPE_WINDOWS_CHANGED does not fire for a globe or space-bar language switch; this does.
    private val imeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            english = resolveEnglish()
            reconcile()
        }
    }

    override fun onServiceConnected() {
        overlay = Overlay(this) {
            Toast.makeText(this, R.string.noop_tap, Toast.LENGTH_SHORT).show()
        }
        for (key in listOf(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, Settings.Secure.DEFAULT_INPUT_METHOD)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, imeObserver)
        }
        english = resolveEnglish()
        reconcile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = reconcile()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(imeObserver)
        if (::overlay.isInitialized) overlay.remove()
        super.onDestroy()
    }

    private fun resolveEnglish(): Boolean {
        val value = Settings.Secure.getString(contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE)?.toIntOrNull()
        val imeId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imm = getSystemService(InputMethodManager::class.java)
        val info = imm.inputMethodList.firstOrNull { it.id == imeId }
        if (value == null || value == -1 || info == null) {
            Log.i(TAG, "subtype unresolved: value=$value ime=$imeId imeVisible=${info != null}")
            return false
        }
        val subtypes = (0 until info.subtypeCount).map(info::getSubtypeAt) +
            imm.getEnabledInputMethodSubtypeList(info, true)
        // hashCode() is the explicit subtype id when one was set, and the settings value is that same number.
        val subtype = subtypes.firstOrNull { it.hashCode() == value }
        if (subtype == null) {
            Log.i(TAG, "subtype $value not among ${subtypes.size} subtypes of $imeId")
            return false
        }
        @Suppress("DEPRECATION")
        return ImeLanguage.isEnglish(subtype.languageTag, subtype.locale)
    }

    private fun reconcile() {
        if (!::overlay.isInitialized) return
        val state = place()
        if (state != lastState) {
            Log.i(TAG, state)
            lastState = state
        }
    }

    private fun place(): String {
        if (!english) return removed("keyboard not English")
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return removed("no keyboard window")
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return removed("no focused field")
        if (field.packageName == packageName) return removed("own app")
        if (!FieldPolicy.isDictatable(field.isEditable, field.isPassword, field.inputType)) {
            return removed("ineligible field")
        }
        val root = ime.root
        val mic = root?.let(MicLocator::find)
        if (mic == null && root != null && imeTreeLoggedFor != ime.id) {
            imeTreeLoggedFor = ime.id
            Log.i(TAG, "voice key not found; IME descriptions: ${MicLocator.descriptions(root)}")
        }
        overlay.show(mic ?: fallbackBounds(ime))
        return when {
            overlay.isDragged -> "shown-dragged"
            mic != null -> "shown-on-node $mic"
            else -> "shown-fallback"
        }
    }

    private fun removed(reason: String): String {
        overlay.remove()
        return "removed: $reason"
    }

    /** A guess at Gboard's toolbar microphone: the IME window's top-right corner. */
    private fun fallbackBounds(ime: AccessibilityWindowInfo): Rect {
        val window = Rect().also(ime::getBoundsInScreen)
        val size = (FALLBACK_DP * resources.displayMetrics.density).toInt()
        val margin = size / 4
        return Rect(window.right - size - margin, window.top + margin, window.right - margin, window.top + margin + size)
    }

    companion object {
        const val TAG = "SttButton"
        private const val FALLBACK_DP = 48
    }
}
