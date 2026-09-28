package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.content.Context
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
class Overlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onPlacementChanged: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private var view: View? = null
    private var zones: DropZonesView? = null
    private val params = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

    val isDragged: Boolean get() = prefs.contains(KEY_X)
    val isHidden: Boolean get() = isHidden(context)

    fun show(bounds: Rect) {
        val dragged = draggedPosition()
        params.width = bounds.width()
        params.height = bounds.height()
        params.x = dragged?.x ?: bounds.left
        params.y = dragged?.y ?: bounds.top
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

    fun remove() {
        zones?.let(windowManager::removeView)
        zones = null
        view?.let(windowManager::removeView)
        view = null
    }

    private fun draggedPosition(): Point? =
        if (isDragged) Point(prefs.getInt(KEY_X, 0), prefs.getInt(KEY_Y, 0)) else null

    private fun screen(): Rect = windowManager.currentWindowMetrics.bounds

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
    private fun createView(): View = View(context).apply {
        val idle = oval(0x6600BCD4)
        val pressed = oval(0xCC00BCD4.toInt())
        background = idle
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
                    v.background = pressed
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
                    v.background = idle
                    if (dragging) drop(zoneAt(event)) else onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.background = idle
                    if (dragging) drop(null)
                }
            }
            true
        }
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((1.5f * density).toInt(), 0xCC00838F.toInt())
    }

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
        private const val PREFS = "overlay"
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
