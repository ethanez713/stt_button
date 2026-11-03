package com.ezhou.sttbutton

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Four dBFS bands of a 512-sample window. Each band is its share of the samples' power, so a sine
 * inside one band reads as that sine's own RMS, and the upper bands are tilted up because speech
 * power falls with frequency.
 */
class Spectrum {
    private val hann = DoubleArray(N)
    private val bitRev = IntArray(N)
    private val bandOf = IntArray(N / 2 + 1)
    private val real = DoubleArray(N)
    private val imag = DoubleArray(N)
    private val bandPower = DoubleArray(BANDS)

    init {
        var i = 0
        while (i < N) {
            bitRev[i] = reverseBits(i)
            // Periodic Hann: a tone on a bin spreads into exactly three bins.
            hann[i] = 0.5 * (1.0 - cos(2.0 * PI * i / N))
            i++
        }
        var k = 0
        while (k < bandOf.size) {
            val hz = k * SAMPLE_RATE.toDouble() / N
            var band = -1
            var b = 0
            while (b < BANDS) {
                val low = EDGES[b]
                val high = EDGES[b + 1]
                val belowTop = if (b == BANDS - 1) hz <= high else hz < high
                if (hz >= low && belowTop) band = b
                b++
            }
            bandOf[k] = band
            k++
        }
    }

    /** [samples] is the latest [N] samples, oldest first. */
    fun bands(samples: ShortArray, into: DoubleArray) {
        var sumSquares = 0.0
        var i = 0
        while (i < N) {
            val sample = samples[i].toDouble()
            sumSquares += sample * sample
            real[i] = sample * hann[i]
            imag[i] = 0.0
            i++
        }
        transform()
        bandPower.fill(0.0)
        var totalPower = 0.0
        val nyquist = N shr 1
        var k = 0
        while (k <= nyquist) {
            val mag2 = real[k] * real[k] + imag[k] * imag[k]
            val power = if (k == 0 || k == nyquist) mag2 else mag2 * 2.0
            totalPower += power
            val band = bandOf[k]
            if (band >= 0) bandPower[band] += power
            k++
        }
        val meanSquare = sumSquares / N
        var b = 0
        while (b < BANDS) {
            val bandMeanSquare = if (totalPower == 0.0) 0.0 else bandPower[b] / totalPower * meanSquare
            into[b] = if (bandMeanSquare == 0.0) {
                DBFS_FLOOR
            } else {
                (10.0 * log10(bandMeanSquare / FULL_SCALE_SQ) + TILT[b]).coerceAtLeast(DBFS_FLOOR)
            }
            b++
        }
    }

    private fun transform() {
        var i = 0
        while (i < N) {
            val j = bitRev[i]
            if (i < j) {
                val swapReal = real[i]
                val swapImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = swapReal
                imag[j] = swapImag
            }
            i++
        }
        var len = 2
        while (len <= N) {
            val half = len shr 1
            val angle = -2.0 * PI / len
            val stepReal = cos(angle)
            val stepImag = sin(angle)
            var start = 0
            while (start < N) {
                var twiddleReal = 1.0
                var twiddleImag = 0.0
                var k = 0
                while (k < half) {
                    val even = start + k
                    val odd = even + half
                    val mixedReal = real[odd] * twiddleReal - imag[odd] * twiddleImag
                    val mixedImag = real[odd] * twiddleImag + imag[odd] * twiddleReal
                    val evenReal = real[even]
                    val evenImag = imag[even]
                    real[even] = evenReal + mixedReal
                    imag[even] = evenImag + mixedImag
                    real[odd] = evenReal - mixedReal
                    imag[odd] = evenImag - mixedImag
                    val nextReal = twiddleReal * stepReal - twiddleImag * stepImag
                    twiddleImag = twiddleReal * stepImag + twiddleImag * stepReal
                    twiddleReal = nextReal
                    k++
                }
                start += len
            }
            len = len shl 1
        }
    }

    private fun reverseBits(index: Int): Int {
        var x = index
        var y = 0
        var n = N
        while (n > 1) {
            y = (y shl 1) or (x and 1)
            x = x shr 1
            n = n shr 1
        }
        return y
    }

    companion object {
        const val N = 512
        const val BANDS = 4
        const val SAMPLE_RATE = 16000
        const val DBFS_FLOOR = -120.0

        private const val FULL_SCALE_SQ = 32768.0 * 32768.0
        private val EDGES = doubleArrayOf(100.0, 251.0, 632.0, 1589.0, 4000.0)
        private val TILT = doubleArrayOf(0.0, 3.0, 6.0, 9.0)
    }
}
