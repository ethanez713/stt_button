package com.ezhou.sttbutton

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

const val WAVE_POINT_COUNT = 24

fun fillWaveOffsets(into: DoubleArray, phase: Double, cycles: Double, amplitude: Double) {
    val steps = (into.size - 1).coerceAtLeast(1).toDouble()
    for (i in into.indices) {
        into[i] = amplitude * sin(phase + 2.0 * PI * cycles * (i.toDouble() / steps))
    }
}

@SuppressLint("ViewConstructor")
class WaveButtonView(
    context: Context,
    private val level: () -> Double?,
    initialMode: ButtonMode,
) : View(context) {
    private class Wave(val color: Int, val gain: Double, val cycles: Double, val radiansPerSecond: Double)

    private var mode = initialMode
    private var arcColor = Color.WHITE
    private var spin = 0f
    private val wave = WaveLevel()
    private var shown = 0.0
    private var lastDrawnAt = 0L
    private var scheduled = false
    private val phases = DoubleArray(WAVES.size)
    private val samples = DoubleArray(WAVE_POINT_COUNT)
    private val paths = Array(WAVES.size) { Path().apply { incReserve(WAVE_POINT_COUNT) } }
    private val clip = Path()
    private var clipW = -1
    private var clipH = -1
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
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
        val wasRecording = mode == ButtonMode.RECORDING
        mode = next
        if (wasRecording) wave.reset()
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
        if (mode == ButtonMode.CLEANING) {
            drawSpinner(canvas)
            return
        }
        if (mode != ButtonMode.RECORDING || lastDrawnAt == 0L || width == 0 || height == 0) return
        ensureClip()
        canvas.save()
        canvas.clipPath(clip)
        val mid = height * 0.5f
        val span = (WAVE_POINT_COUNT - 1).toFloat()
        val widthPx = width.toFloat()
        val half = height / 2.0
        for (i in WAVES.indices) {
            val spec = WAVES[i]
            fillWaveOffsets(samples, phases[i], spec.cycles, spec.gain * half * shown)
            val path = paths[i]
            path.rewind()
            path.moveTo(0f, mid + samples[0].toFloat())
            for (p in 1 until WAVE_POINT_COUNT) {
                path.lineTo(widthPx * p / span, mid + samples[p].toFloat())
            }
            paint.color = spec.color
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    private fun animates() = mode == ButtonMode.RECORDING || mode == ButtonMode.CLEANING

    private fun drawSpinner(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val inset = arcPaint.strokeWidth
        arcPaint.color = arcColor
        canvas.drawArc(inset, inset, width - inset, height - inset, spin, SWEEP, false, arcPaint)
    }

    private fun onFrame() {
        scheduled = false
        if (!animates() || !isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawnAt == 0L) 0.0 else (now - lastDrawnAt) / 1000.0
        lastDrawnAt = now
        if (mode == ButtonMode.CLEANING) spin = (spin + SPIN_DEGREES_PER_SECOND * dt.toFloat()) % 360f
        else {
            shown = wave.update(level())
            for (i in WAVES.indices) phases[i] += WAVES[i].radiansPerSecond * dt
        }
        invalidate()
        postFrame()
    }

    private fun postFrame() {
        if (scheduled || !animates() || !isAttachedToWindow) return
        scheduled = true
        postOnAnimationDelayed(frame, MIN_FRAME_MS)
    }

    private fun stopFrames() {
        scheduled = false
        lastDrawnAt = 0L
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
        const val SWEEP = 90f
        const val SPIN_DEGREES_PER_SECOND = 270f

        // Radians per second, each its own so the three sines drift apart.
        val WAVES = arrayOf(
            Wave(Color.argb(230, 255, 255, 255), 1.0, 1.5, 4.4),
            Wave(Color.argb(140, 255, 255, 255), 0.6, 2.0, 7.2),
            Wave(Color.argb(90, 255, 255, 255), 0.35, 2.5, 10.7),
        )
    }
}
