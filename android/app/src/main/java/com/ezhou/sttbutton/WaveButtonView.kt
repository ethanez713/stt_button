package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

@SuppressLint("ViewConstructor")
class WaveButtonView(
    context: Context,
    private val bands: () -> DoubleArray?,
    initialMode: ButtonMode,
) : View(context) {
    private var mode = initialMode
    private var arcColor = Color.WHITE
    private val smoother = BarSmoother()
    private var seconds = 0.0
    private var lastDrawnAt = 0L
    private var scheduled = false
    private val clip = Path()
    private var clipW = -1
    private var clipH = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val frame = Runnable { onFrame() }

    init {
        setWillNotDraw(false)
    }

    fun setArcColor(color: Int) {
        if (arcColor == color) return
        arcColor = color
        if (mode == ButtonMode.CLEANING) invalidate()
    }

    fun setMode(next: ButtonMode) {
        if (mode == next) return
        mode = next
        if (animates()) postFrame() else stopFrames()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animates()) postFrame()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!animates() || width == 0 || height == 0) return
        ensureClip()
        val diameter = width.toFloat()
        val barWidth = BAR_WIDTH * diameter
        val gap = BAR_GAP * diameter
        val total = BARS * barWidth + (BARS - 1) * gap
        var left = (diameter - total) / 2f
        val mid = height * 0.5f
        paint.strokeWidth = barWidth
        paint.color = if (mode == ButtonMode.CLEANING) arcColor else RECORDING_COLOR
        canvas.save()
        canvas.clipPath(clip)
        var i = 0
        while (i < BARS) {
            val half = BAR_HALF * height * smoother.level(i).toFloat()
            val cx = left + barWidth * 0.5f
            canvas.drawLine(cx, mid - half, cx, mid + half, paint)
            left += barWidth + gap
            i++
        }
        canvas.restore()
    }

    private fun animates() = mode == ButtonMode.RECORDING || mode == ButtonMode.CLEANING

    private fun onFrame() {
        scheduled = false
        if (!animates() || !isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawnAt == 0L) 0.0 else (now - lastDrawnAt) / 1000.0
        lastDrawnAt = now
        seconds += dt
        if (mode == ButtonMode.CLEANING) smoothRipple() else smoothBands()
        invalidate()
        postFrame()
    }

    private fun smoothRipple() {
        var i = 0
        while (i < BARS) {
            smoother.update(i, cleaningRipple(seconds, i))
            i++
        }
    }

    private fun smoothBands() {
        val latest = bands()
        var i = 0
        while (i < BARS) {
            val dbfs = if (latest != null && latest.size == Spectrum.BANDS) latest[BAND_OF_BAR[i]] else Spectrum.DBFS_FLOOR
            smoother.update(i, WaveLevel.target(dbfs))
            i++
        }
    }

    private fun postFrame() {
        if (scheduled || !animates() || !isAttachedToWindow) return
        scheduled = true
        postOnAnimationDelayed(frame, MIN_FRAME_MS)
    }

    private fun stopFrames() {
        scheduled = false
        lastDrawnAt = 0L
        seconds = 0.0
        smoother.reset()
        removeCallbacks(frame)
    }

    private fun ensureClip() {
        if (clipW == width && clipH == height) return
        clip.rewind()
        clip.addOval(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        clipW = width
        clipH = height
    }

    private companion object {
        const val MIN_FRAME_MS = 33L
        const val BARS = 7
        const val BAR_WIDTH = 0.07f
        const val BAR_GAP = 0.045f
        const val BAR_HALF = 0.42f
        val RECORDING_COLOR: Int = Color.argb(230, 255, 255, 255)
        val BAND_OF_BAR = intArrayOf(3, 2, 1, 0, 1, 2, 3)
    }
}
