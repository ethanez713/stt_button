package com.ezhou.sttbutton

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class DropZonesView(context: Context) : View(context) {
    var highlighted: DropZone? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 16 * density
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val gap = 12 * density
        val half = width / 2f
        drawZone(canvas, DropZone.HIDE, gap, half - gap / 2, context.getString(R.string.zone_hide), 0xCCC62828.toInt())
        drawZone(canvas, DropZone.RESET, half + gap / 2, width - gap, context.getString(R.string.zone_reset), 0xCC00838F.toInt())
    }

    private fun drawZone(canvas: Canvas, zone: DropZone, left: Float, right: Float, text: String, color: Int) {
        val gap = 12 * density
        box.set(left, gap, right, height - gap)
        fill.color = if (zone == highlighted) color else color and 0x66FFFFFF
        canvas.drawRoundRect(box, 20 * density, 20 * density, fill)
        canvas.drawText(text, box.centerX(), box.centerY() - (label.ascent() + label.descent()) / 2, label)
    }
}
