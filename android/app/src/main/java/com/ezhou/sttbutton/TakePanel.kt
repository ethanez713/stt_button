package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/** The panel this app's keyboard shows during a take: a stop key where the overlay button was, on the keyboard background. */
@SuppressLint("ViewConstructor")
class TakePanel(context: Context, private val style: () -> ButtonStyle, private val onStop: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val background = Paint().apply { color = BACKGROUND }
    private val key = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLYPH }
    private var pressed = false

    private fun keyBounds(): Rect {
        val size = (KEY_DP * density).toInt()
        val wanted = ImeLink.stopKey ?: Rect(width - size - size / 4, size / 4, width - size / 4, size / 4 + size)
        val w = wanted.width().coerceAtMost(width)
        val h = wanted.height().coerceAtMost(height)
        val left = wanted.left.coerceIn(0, (width - w).coerceAtLeast(0))
        val top = wanted.top.coerceIn(0, (height - h).coerceAtLeast(0))
        return Rect(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val bounds = keyBounds()
        val mode = if (ImeLink.busy) ButtonMode.BUSY else ButtonMode.RECORDING
        key.color = style().color(mode, pressed)
        canvas.drawOval(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), key)
        val half = bounds.width().coerceAtMost(bounds.height()) * STOP_GLYPH_FRACTION
        canvas.drawRect(bounds.exactCenterX() - half, bounds.exactCenterY() - half, bounds.exactCenterX() + half, bounds.exactCenterY() + half, glyph)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val fallback = (DEFAULT_HEIGHT_DP * density).toInt()
        val wanted = ImeLink.panelHeightPx.takeIf { it > 0 } ?: fallback
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), wanted)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inside = keyBounds().contains(event.x.toInt(), event.y.toInt())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (inside) { pressed = true; invalidate() } else return false
            MotionEvent.ACTION_UP -> {
                val fire = pressed && inside && !ImeLink.busy
                pressed = false
                invalidate()
                if (fire) onStop()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }

    companion object {
        const val DEFAULT_HEIGHT_DP = 280
        private const val KEY_DP = 48
        private const val BACKGROUND = 0xFF1C2222.toInt()
        private const val GLYPH = 0xFFFFFFFF.toInt()
        private const val STOP_GLYPH_FRACTION = 0.2f
    }
}
