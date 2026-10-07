package com.ezhou.sttbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * Keeps the button on screen exactly while Gboard's microphone is there to be used: the keyboard is
 * up on a panel that has the voice key, and it is typing into a field of the focused window.
 */
class DictationService : AccessibilityService() {
    private lateinit var overlay: Overlay
    private lateinit var bubble: BubbleOverlay
    private lateinit var controller: TakeController
    lateinit var swapper: ImeSwapper
        private set
    /** true English, false another language, null unresolved. */
    private var english: Boolean? = null
    private var lastState: String? = null
    private var imeTreeLoggedFor: Int? = null
    private val placement = MicPlacement()
    private val slide = KeyboardSlide()
    private val handler = Handler(Looper.getMainLooper())
    private val reread = Runnable { reconcile() }
    private val imePrefs by lazy { getSharedPreferences("ime", MODE_PRIVATE) }
    /** Whether this install has ever found Gboard's voice key; until then its absence proves nothing. */
    private var voiceKeySeen = false
    private var gboardAtRest = false
    private var gboardProbed = false
    private var ownImeHeight: Int? = null

    // TYPE_WINDOWS_CHANGED does not fire for a globe or space-bar language switch; this does.
    private val imeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (::swapper.isInitialized) swapper.noteDefaultChanged()
            english = resolveEnglish()
            if (english == false && ::controller.isInitialized && !controller.swapHoldsTake) controller.onLanguageLeft()
            reconcile()
        }
    }

    override fun onServiceConnected() {
        live = this
        overlay = Overlay(
            this,
            onTap = { controller.onTap() },
            onPlacementChanged = ::reconcile,
        )
        bubble = BubbleOverlay(this)
        controller = TakeController(this, overlay, bubble).also {
            it.logProcessExits()
        }
        swapper = ImeSwapper(this) { controller.swapHoldsTake }.also {
            it.enableOnConnect()
            it.returnIfStranded()
        }
        ImeLink.host = controller
        for (key in listOf(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, Settings.Secure.DEFAULT_INPUT_METHOD)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, imeObserver)
        }
        voiceKeySeen = imePrefs.getBoolean(KEY_VOICE_KEY_SEEN, false)
        english = resolveEnglish()
        reconcile()
    }

    override fun onCreateInputMethod(): InputMethod = SessionInputMethod(this)

    /** `adb shell setprop log.tag.SttTrace DEBUG` logs what each event found: the take, the focused field, every window, and the input session beside the field placement read. */
    private fun trace(event: AccessibilityEvent) {
        if (!Log.isLoggable(TRACE, Log.DEBUG)) return
        val wins = windows.joinToString("; ") { w ->
            val type = when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                else -> "T${w.type}"
            }
            val root = w.root
            val mic = if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) " mic=${root?.let(MicLocator::find)}" else ""
            "$type#${w.id} ${root?.packageName ?: "noroot"} ${Rect().also(w::getBoundsInScreen).toShortString()}" +
                "${if (w.isFocused) " F" else ""}${if (w.isActive) " A" else ""}$mic"
        }
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focus = node?.let { "${it.packageName}/${it.className} id=${it.viewIdResourceName} editable=${it.isEditable} focused=${it.isFocused} vis=${it.isVisibleToUser} win#${it.windowId}" }
        val session = (inputMethod as? SessionInputMethod)?.snapshot() ?: InputSession.EMPTY
        // The field place() reads: the focused node, otherwise the editor standing in for one.
        val agrees = if (node != null) {
            session.agrees(node.packageName?.toString(), node.isPassword, node.inputType)
        } else {
            val editor = unreadField()
            session.agrees(editor?.packageName, false, editor?.inputType ?: 0)
        }
        Log.d(TRACE, "ev=${AccessibilityEvent.eventTypeToString(event.eventType)} changes=0x${event.windowChanges.toString(16)} pkg=${event.packageName}" +
            " | take=${controller.stateName} | focus=${focus ?: "none"} | wins=[$wins]" +
            " | session pkg=${session.packageName} inputType=0x${session.inputType.toString(16)} starts=${session.starts} finishes=${session.finishes} agrees=$agrees")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::controller.isInitialized) return
        trace(event)
        controller.onAccessibilityEvent(event)
        if (event.eventType and FIELD_EVENTS == 0) reconcile()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) probeGboard()
    }

    fun a11yConnection(): Boolean = inputMethod?.currentInputConnection != null

    /** The height Gboard's window had at rest on this screen, or null before one was seen. */
    fun rememberedImeHeight(): Int? = imePrefs.getInt(imeHeightKey(), 0).takeIf { it > 0 }

    /** This app's keyboard window's height at rest minus Gboard's, null when either is unknown. */
    fun imeHeightDelta(): Int? {
        val own = ownImeHeight ?: return null
        val gboard = rememberedImeHeight() ?: return null
        return own - gboard
    }

    private fun imeHeightKey(): String = overlay.screen().let { "ime_height_${it.width()}x${it.height()}" }

    /** One SttTrace line, once per process, with Gboard's window and every labelled node in it: the evidence for matching its layout. */
    private fun probeGboard() {
        if (gboardProbed || !gboardAtRest) return
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return
        val root = ime.root ?: return
        gboardProbed = true
        val nodes = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo) {
            val label = node.text?.toString()?.takeIf { it.isNotEmpty() } ?: node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            if (label != null) nodes += "$label@${Rect().also(node::getBoundsInScreen).toShortString()}"
            for (i in 0 until node.childCount) if (nodes.size < PROBE_MAX_NODES) node.getChild(i)?.let(::walk)
        }
        walk(root)
        Log.i(TRACE, "gboard layout window=${Rect().also(ime::getBoundsInScreen).toShortString()} nodes=${nodes.size} [${nodes.joinToString("; ")}]")
    }

    /** A take changed state or its button has been away a while; either changes what belongs on screen. */
    fun recheck() = reconcile()

    fun focusedField(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** TYPE_VIEW_TEXT_CHANGED is only worth its event traffic while a field this app wrote into is tracked. */
    fun watchTextChanges(on: Boolean) = watch(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, on)

    private fun watch(eventType: Int, on: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (on) info.eventTypes or eventType else info.eventTypes and eventType.inv()
        serviceInfo = info
    }

    override fun onInterrupt() = Unit

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::controller.isInitialized) controller.onTrimMemory(level)
    }

    override fun onDestroy() {
        if (live === this) live = null
        ImeLink.host = null
        contentResolver.unregisterContentObserver(imeObserver)
        handler.removeCallbacks(reread)
        if (::controller.isInitialized) controller.shutdown()
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
        val subtype = ImeSubtypes.find(imm, imeId, value)
        if (subtype == null) {
            Log.i(TAG, "subtype $value not among the subtypes of $imeId")
            return null
        }
        @Suppress("DEPRECATION")
        val subtag = ImeLanguage.subtag(subtype.languageTag, subtype.locale) ?: return null
        return subtag == "en"
    }

    private fun reconcile() {
        if (!::controller.isInitialized) return
        val state = place()
        if (state != lastState) {
            Log.i(TAG, state)
            lastState = state
        }
        controller.onButtonShown(overlay.isShowing)
    }

    private fun place(): String {
        ModelFiles.missing(filesDir).takeIf { it.isNotEmpty() }?.let { return removed("model files missing: ${it.size}") }
        if (overlay.isHidden) return removed("hidden from the drop zone")
        if (english == false) return removed("keyboard not English")
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime == null) {
            slide.gone()
            return removed("no keyboard window")
        }
        val imeBounds = Rect().also(ime::getBoundsInScreen)
        val motion = slide.read(imeBounds.top, imeBounds.bottom, overlay.screen().bottom)
        if (motion == KeyboardMotion.LEAVING) return removed("keyboard sliding out")
        bubble.setAnchor(imeBounds)
        val imePackage = ime.root?.packageName
        gboardAtRest = motion == KeyboardMotion.RESTING && imePackage == MicLocator.GBOARD
        if (motion == KeyboardMotion.RESTING) {
            if (imePackage == MicLocator.GBOARD) imePrefs.edit().putInt(imeHeightKey(), imeBounds.height()).apply()
            else if (imePackage == packageName) ownImeHeight = imeBounds.height()
        }
        if (imePackage == packageName) return removed("own keyboard")
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val fieldShape = if (field != null) {
            if (field.packageName == packageName && field.viewIdResourceName != TRY_FIELD_ID) return removed("own app")
            FieldPolicy.exclusion(field.isPassword, field.inputType)?.let { return removed(it) }
            "pkg=${field.packageName} editable=${field.isEditable} inputType=0x${field.inputType.toString(16)}"
        } else {
            // On the way out of an app the keyboard outlives the field, still showing its microphone.
            val editor = unreadField() ?: return removed("no text field")
            FieldPolicy.exclusion(password = false, editor.inputType)?.let { return removed(it) }
            "editor pkg=${editor.packageName} inputType=0x${editor.inputType.toString(16)}"
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
        show(mic ?: fallbackBounds(ime), atRest = motion == KeyboardMotion.RESTING)
        val placement = when {
            overlay.isDragged -> "shown-dragged"
            mic != null -> "shown-on-node $mic"
            else -> "shown-fallback"
        }
        val button = overlay.lastShown?.takeIf { overlay.isShowing }?.let { " button=${it.left},${it.top},${it.right},${it.bottom}" } ?: ""
        return "$placement$button language=${english ?: "unresolved"} $fieldShape"
    }

    /** The editor the keyboard is typing into when its field has no accessibility node, or null when there is none. */
    private fun unreadField(): EditorInfo? {
        val input = inputMethod ?: return null
        val editor = input.currentInputEditorInfo ?: return null
        return editor.takeIf { FieldPolicy.editorIsField(input.currentInputStarted, it.inputType, it.packageName, activePackage()) }
    }

    // The keyboard slides in over a few hundred ms, so the button waits at the last settled spot
    // until the keyboard is at rest; a dragged button ignores the microphone.
    private fun show(bounds: Rect, atRest: Boolean) {
        if (overlay.isDragged) {
            placement.reset()
            overlay.show(bounds)
            return
        }
        val read = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val wanted = placement.read(SystemClock.uptimeMillis(), read, overlay.rememberedBounds(), atRest)
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

        /** The connected service, for the debug self-test that runs in this process. */
        @Volatile
        var live: DictationService? = null
            private set

        private const val TRACE = "SttTrace"
        private const val FALLBACK_DP = 48
        private const val TRY_FIELD_ID = "com.ezhou.sttbutton:id/try_field"
        private const val REREAD_MS = 100L
        private const val PROBE_MAX_NODES = 300
        private const val KEY_VOICE_KEY_SEEN = "voice_key_seen"
        private const val FIELD_EVENTS = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }
}
