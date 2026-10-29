package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot

/**
 * The button window, plus the drop zones shown while it is dragged. It is drawn above the IME,
 * so it has no FLAG_ALT_FOCUSABLE_IM: combined with FLAG_NOT_FOCUSABLE that flag would put it
 * behind the keyboard.
 */
enum class ButtonMode { IDLE, RECORDING, BUSY, CLEANING, FAILED }

class Overlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onPlacementChanged: () -> Unit,
    private val level: () -> Double?,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private var view: WaveButtonView? = null
    private var mode = ButtonMode.IDLE
    private var zones: DropZonesView? = null
    private var style = ButtonStyle.load(prefs)
    private val styleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in ButtonStyle.KEYS) {
            style = ButtonStyle.load(prefs)
            view?.let {
                it.setArcColor(arcColor())
                it.background = oval(style.color(mode))
            }
            onPlacementChanged()
        }
    }.also(prefs::registerOnSharedPreferenceChangeListener)
    private val params = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

    val isDragged: Boolean get() = prefs.contains(KEY_X)
    val isHidden: Boolean get() = isHidden(context)
    val isShowing: Boolean get() = view != null

    /** Where the button was last drawn, in screen coordinates; it stays after the button is removed. */
    var lastShown: Bounds? = null
        private set

    /** Where the microphone last settled with this screen size, or null before the first settle. */
    fun rememberedBounds(): Bounds? =
        prefs.getString(settledKey(), null)?.split(",")?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 4 }?.let { Bounds(it[0], it[1], it[2], it[3]) }

    fun remember(bounds: Bounds) {
        prefs.edit().putString(settledKey(), "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}").apply()
    }

    private fun settledKey(): String = screen().let { "settled_${it.width()}x${it.height()}" }

    fun show(bounds: Rect) {
        val dragged = draggedPosition()
        val styled = style.bounds(Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
        params.width = styled.right - styled.left
        params.height = styled.bottom - styled.top
        params.x = dragged?.x ?: styled.left
        params.y = dragged?.y ?: styled.top
        lastShown = Bounds(params.x, params.y, params.x + params.width, params.y + params.height)
        val current = view
        if (current == null) {
            // Zones first, so they sit under the button. Adding or re-adding a window during a drag
            // cancels the touch stream, so the zones exist before any drag and only change visibility.
            zones = DropZonesView(context).apply { visibility = View.INVISIBLE }
                .also { windowManager.addView(it, zoneParams()) }
            view = createView().also { windowManager.addView(it, params) }
        } else {
            zones?.let { windowManager.updateViewLayout(it, zoneParams()) }
            windowManager.updateViewLayout(current, params)
        }
    }

    fun setMode(next: ButtonMode) {
        mode = next
        // A take is spoken, not touched, so nothing else keeps the screen from timing out under it.
        params.flags = if (next == ButtonMode.IDLE) params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        view?.let {
            it.setMode(mode)
            it.setArcColor(arcColor())
            it.background = oval(style.color(mode))
            windowManager.updateViewLayout(it, params)
        }
    }

    fun remove() {
        zones?.let(windowManager::removeView)
        zones = null
        view?.let(windowManager::removeView)
        view = null
    }

    private fun draggedPosition(): Point? =
        if (isDragged) Point(prefs.getInt(KEY_X, 0), prefs.getInt(KEY_Y, 0)) else null

    fun screen(): Rect = windowManager.currentWindowMetrics.bounds

    private fun zoneHeight(): Int = (ZONE_HEIGHT_DP * density).toInt()

    private fun zoneAt(event: MotionEvent): DropZone? {
        val screen = screen()
        return DropZone.at(event.rawX, event.rawY, screen.width(), screen.height(), zoneHeight())
    }

    // Not touchable: the drag's touch stream stays with the button that received ACTION_DOWN.
    private fun zoneParams(): WindowManager.LayoutParams {
        val screen = screen()
        return overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            width = screen.width()
            height = zoneHeight()
            x = 0
            y = screen.height() - zoneHeight()
        }
    }

    private fun showZones() {
        zones?.visibility = View.VISIBLE
    }

    private fun hideZones() {
        zones?.apply {
            highlighted = null
            visibility = View.INVISIBLE
        }
    }

    private fun drop(zone: DropZone?) {
        hideZones()
        when (zone) {
            DropZone.RESET -> prefs.edit().remove(KEY_X).remove(KEY_Y).apply()
            DropZone.HIDE -> prefs.edit().remove(KEY_X).remove(KEY_Y).putBoolean(KEY_HIDDEN, true).apply()
            null -> prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
        }
        if (zone != null) onPlacementChanged()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arcColor() = style.recordingRgb or (0xFF shl 24)

    private fun createView(): WaveButtonView = WaveButtonView(context, level, mode).apply {
        setArcColor(arcColor())
        background = oval(style.color(mode))
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    v.background = oval(style.color(mode, pressed = true))
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        showZones()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(v, params)
                        zones?.highlighted = zoneAt(event)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.background = oval(style.color(mode))
                    if (dragging) drop(zoneAt(event)) else if (mode != ButtonMode.FAILED) onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.background = oval(style.color(mode))
                    if (dragging) drop(null)
                }
            }
            true
        }
    }

    private fun oval(color: Int) = buttonDrawable(color, density)

    private fun overlayParams(flags: Int) = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Accessibility node bounds are screen coordinates; with no inset fitting, so are x and y.
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    companion object {
        /** The button's look, shared with the preview in the app. */
        fun buttonDrawable(color: Int, density: Float) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * density).toInt(), (color and 0xFFFFFF) or (0xCC shl 24))
        }

        private const val PREFS = ButtonStyle.PREFS
        private const val KEY_X = "dragged_x"
        private const val KEY_Y = "dragged_y"
        private const val KEY_HIDDEN = "hidden"
        private const val ZONE_HEIGHT_DP = 112

        fun isHidden(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false)

        fun unhide(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HIDDEN).apply()
        }
    }
}
