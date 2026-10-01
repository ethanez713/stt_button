package com.ezhou.sttbutton

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The preview bubble: a display-only overlay spanning the keyboard's width, directly above its top edge,
 * showing the last four lines of the take's text with the preview model's words lighter.
 */
class BubbleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: TextView? = null
    private var anchor: Rect? = null
    private var content = BubbleContent("", "")

    fun setAnchor(imeBounds: Rect) {
        if (anchor == imeBounds) return
        anchor = Rect(imeBounds)
        view?.let { windowManager.updateViewLayout(it, params()) }
        render()
    }

    fun show() {
        if (view != null) return
        view = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setTextColor(SETTLED)
            val pad = (PAD_DP * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = PAD_DP * density
            }
            visibility = View.GONE
        }.also { windowManager.addView(it, params()) }
        render()
    }

    fun update(next: BubbleContent) {
        content = next
        render()
    }

    fun hide() {
        view?.let(windowManager::removeView)
        view = null
        content = BubbleContent("", "")
    }

    private fun render() {
        val v = view ?: return
        val width = (anchor?.width() ?: 0) - v.paddingLeft - v.paddingRight
        val full = listOf(content.settled, content.preview).filter { it.isNotEmpty() }.joinToString(" ")
        if (full.isEmpty() || width <= 0) {
            v.visibility = View.GONE
            return
        }
        val layout = StaticLayout.Builder.obtain(full, 0, full.length, v.paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val start = layout.getLineStart(maxOf(0, layout.lineCount - MAX_LINES))
        val shown = SpannableStringBuilder(full.substring(start))
        if (content.preview.isNotEmpty()) {
            val previewStart = (full.length - content.preview.length - start).coerceAtLeast(0)
            shown.setSpan(ForegroundColorSpan(PREVIEW), previewStart, shown.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        v.text = shown
        v.visibility = View.VISIBLE
    }

    // Anchored by its bottom edge so the wrapped height needs no measuring.
    private fun params(): WindowManager.LayoutParams {
        val screen = windowManager.currentWindowMetrics.bounds
        val ime = anchor ?: Rect(0, screen.height(), screen.width(), screen.height())
        return WindowManager.LayoutParams(
            ime.width(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = ime.left
            y = screen.height() - ime.top
            setFitInsetsTypes(0)
        }
    }

    private companion object {
        const val MAX_LINES = 4
        const val TEXT_SP = 16f
        const val PAD_DP = 10
        const val BACKGROUND = 0xE6202124.toInt()
        const val SETTLED = 0xFFFFFFFF.toInt()
        const val PREVIEW = 0xFFA0A4A8.toInt()
    }
}
