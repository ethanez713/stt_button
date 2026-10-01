package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * Shows the button unless something positively rules it out; an unresolved keyboard language or
 * an unreadable field is not a reason to hide it.
 */
class DictationService : AccessibilityService() {
    private lateinit var overlay: Overlay
    /** true English, false another language, null unresolved. */
    private var english: Boolean? = null
    private var lastState: String? = null
    private var imeTreeLoggedFor: Int? = null
    private val placement = MicPlacement()
    private val handler = Handler(Looper.getMainLooper())
    private val reread = Runnable { reconcile() }
    private val imePrefs by lazy { getSharedPreferences("ime", MODE_PRIVATE) }
    /** Whether this install has ever found Gboard's voice key; until then its absence proves nothing. */
    private var voiceKeySeen = false

    // TYPE_WINDOWS_CHANGED does not fire for a globe or space-bar language switch; this does.
    private val imeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            english = resolveEnglish()
            reconcile()
        }
    }

    override fun onServiceConnected() {
        overlay = Overlay(
            this,
            onTap = { Log.i(TAG, "tap (no-op)") },
            onPlacementChanged = ::reconcile,
        )
        for (key in listOf(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, Settings.Secure.DEFAULT_INPUT_METHOD)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, imeObserver)
        }
        voiceKeySeen = imePrefs.getBoolean(KEY_VOICE_KEY_SEEN, false)
        english = resolveEnglish()
        reconcile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = reconcile()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(imeObserver)
        handler.removeCallbacks(reread)
        if (::overlay.isInitialized) overlay.remove()
        super.onDestroy()
    }

    private fun resolveEnglish(): Boolean? {
        val value = Settings.Secure.getString(contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE)?.toIntOrNull()
        val imeId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imm = getSystemService(InputMethodManager::class.java)
        val info = imm.inputMethodList.firstOrNull { it.id == imeId }
        if (value == null || value == -1 || info == null) {
            Log.i(TAG, "subtype unresolved: value=$value ime=$imeId imeVisible=${info != null}")
            return null
        }
        val subtypes = (0 until info.subtypeCount).map(info::getSubtypeAt) +
            imm.getEnabledInputMethodSubtypeList(info, true)
        // hashCode() is the explicit subtype id when one was set, and the settings value is that same number.
        val subtype = subtypes.firstOrNull { it.hashCode() == value }
        if (subtype == null) {
            Log.i(TAG, "subtype $value not among ${subtypes.size} subtypes of $imeId")
            return null
        }
        @Suppress("DEPRECATION")
        val subtag = ImeLanguage.subtag(subtype.languageTag, subtype.locale) ?: return null
        return subtag == "en"
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
        if (overlay.isHidden) return removed("hidden from the drop zone")
        if (english == false) return removed("keyboard not English")
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return removed("no keyboard window")
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field != null) {
            if (field.packageName == packageName) return removed("own app")
            FieldPolicy.exclusion(field.isPassword, field.inputType)?.let { return removed(it) }
        }
        val root = ime.root
        val mic = root?.let(MicLocator::find)
        if (mic != null && !voiceKeySeen) {
            voiceKeySeen = true
            imePrefs.edit().putBoolean(KEY_VOICE_KEY_SEEN, true).apply()
        }
        // A readable keyboard without the voice key it has shown before is on another panel.
        if (mic == null && root != null && voiceKeySeen) return removed("keyboard panel without a voice key")
        if (mic == null && root != null && imeTreeLoggedFor != ime.id) {
            imeTreeLoggedFor = ime.id
            Log.i(TAG, "voice key not found; IME descriptions: ${MicLocator.descriptions(root)}")
        }
        show(mic ?: fallbackBounds(ime))
        val placement = when {
            overlay.isDragged -> "shown-dragged"
            mic != null -> "shown-on-node $mic"
            else -> "shown-fallback"
        }
        val fieldShape = field?.let { "pkg=${it.packageName} editable=${it.isEditable} inputType=0x${it.inputType.toString(16)}" }
            ?: "no focused node"
        return "$placement language=${english ?: "unresolved"} $fieldShape"
    }

    // The keyboard slides in over a few hundred ms, so the button waits at the last settled spot
    // until the bounds stop changing; a dragged button ignores the microphone.
    private fun show(bounds: Rect) {
        if (overlay.isDragged) {
            placement.reset()
            overlay.show(bounds)
            return
        }
        val read = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val wanted = placement.read(SystemClock.uptimeMillis(), read, overlay.rememberedBounds())
        wanted.show?.let { overlay.show(Rect(it.left, it.top, it.right, it.bottom)) }
        wanted.persist?.let(overlay::remember)
        wanted.settle?.let {
            Log.i(TAG, "settle remembered=${it.remembered} reads=${it.reads} settled_ms=${it.settledMs} moved_px=${it.movedPx}")
        }
        handler.removeCallbacks(reread)
        if (wanted.reread) handler.postDelayed(reread, REREAD_MS)
    }

    private fun removed(reason: String): String {
        handler.removeCallbacks(reread)
        placement.reset()
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
        private const val REREAD_MS = 100L
        private const val KEY_VOICE_KEY_SEEN = "voice_key_seen"
    }
}
