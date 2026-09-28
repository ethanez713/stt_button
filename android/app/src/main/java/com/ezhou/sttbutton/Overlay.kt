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
 * The button window. It is drawn above the IME, so it has no FLAG_ALT_FOCUSABLE_IM: combined
 * with FLAG_NOT_FOCUSABLE that flag would put it behind the keyboard.
 */
class Overlay(private val context: Context, private val onTap: () -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var view: View? = null
    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Accessibility node bounds are screen coordinates; with no inset fitting, so are x and y.
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    val isDragged: Boolean get() = prefs.contains(KEY_X)

    fun show(bounds: Rect) {
        val dragged = draggedPosition()
        params.width = bounds.width()
        params.height = bounds.height()
        params.x = dragged?.x ?: bounds.left
        params.y = dragged?.y ?: bounds.top
        val current = view
        if (current == null) {
            view = createView().also { windowManager.addView(it, params) }
        } else {
            windowManager.updateViewLayout(current, params)
        }
    }

    fun remove() {
        view?.let(windowManager::removeView)
        view = null
    }

    private fun draggedPosition(): Point? =
        if (isDragged) Point(prefs.getInt(KEY_X, 0), prefs.getInt(KEY_Y, 0)) else null

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x6600BCD4)
            setStroke(3, 0xCC00838F.toInt())
        }
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
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        onTap()
                    }
                }
            }
            true
        }
    }

    companion object {
        private const val PREFS = "overlay"
        private const val KEY_X = "dragged_x"
        private const val KEY_Y = "dragged_y"

        fun resetPosition(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
