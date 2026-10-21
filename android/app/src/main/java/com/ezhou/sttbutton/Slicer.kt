package com.ezhou.sttbutton

import kotlin.math.max
import kotlin.math.min

/**
 * Cuts recognizer slices from growing 16 kHz PCM16 audio; the port of `stt_button.asr.Slicer`, which
 * is the specification (design § Pipeline). Window energies are exact Long sums of squares so the
 * silence test agrees with the host.
 */
class Slicer(
    sliceMaxS: Double,
    searchS: Double,
    private val pauseS: Double? = null,
    sliceMinS: Double = 2.0,
    pauseMarginDb: Double = 10.0,
    loudDbfs: Double = -48.0,
    cutPauseS: Double? = null,
) {
    private val maxLen = (sliceMaxS * SAMPLE_RATE).toInt()
    private val search = (searchS * SAMPLE_RATE).toInt()
    private val pauseWindows = pauseS?.let { Math.round(it / CUT_WINDOW_S).toInt() }
    private val cutWindows = (cutPauseS ?: pauseS)?.let { Math.round(it / CUT_WINDOW_S).toInt() }
    private val minLen = (sliceMinS * SAMPLE_RATE).toInt()
    private val margin = Math.pow(10.0, pauseMarginDb / 10)
    private val loudEnergy = WIN.toDouble() * 32768.0 * 32768.0 * Math.pow(10.0, loudDbfs / 10.0)
    private val floorMin = WIN.toDouble() * 32768.0 * 32768.0 * Math.pow(10.0, NOISE_FLOOR_DBFS / 10.0)

    private var pcm = ShortArray(SAMPLE_RATE * 30)
    private var size = 0
    var start = 0
        private set
    private var energies = 0
    private val sorted = ArrayList<Long>()
    private var lastSpeech = -1
    private var lastLoud = -1
    private val speech = ArrayList<Boolean>()

    private val closed = ArrayList<IntArray>()

    val sampleCount: Int get() = synchronized(this) { size }

    /** Speech has been heard and the last pause_s of audio is silent. */
    val paused: Boolean
        get() = synchronized(this) { pauseWindows != null && lastSpeech >= 0 && energies - 1 - lastSpeech >= pauseWindows }

    /** Sample offset where the last non-silent window ended, or -1. */
    val lastSpeechEnd: Int get() = synchronized(this) { if (lastSpeech < 0) -1 else (lastSpeech + 1) * WIN }

    /** Sample offset where the last window at or above `stop_silence_dbfs` ended, or -1. Independent of the noise floor, so room noise under that level is silence. */
    val lastLoudEnd: Int get() = synchronized(this) { if (lastLoud < 0) -1 else (lastLoud + 1) * WIN }

    /** Appends audio; returns the slices it completed as (start, end) sample offsets. */
    fun feed(chunk: ShortArray, length: Int = chunk.size): List<IntArray> = synchronized(this) {
        append(chunk, length)
        val done = mutableListOf<IntArray>()
        while (cutWindows != null && (energies + 1) * WIN <= size) {
            classifyNextWindow()
            cutAt(energies * WIN, done)
        }
        while (size - start > maxLen) done += hardCut()
        closed += done
        done
    }

    /** Every slice [feed] has completed from the [from]th on, in the order it cut them. */
    fun closedSlices(from: Int): List<IntArray> = synchronized(this) { closed.drop(from) }

    fun finish(): List<IntArray> = synchronized(this) { if (size > start) listOf(intArrayOf(start, size)) else emptyList() }

    /** Where the last non-silent window that ends at or before [sample] ends, or null. */
    fun speechBefore(sample: Int): Int? = synchronized(this) {
        var last = min(sample / WIN, speech.size) - 1
        while (last >= 0 && !speech[last]) last--
        if (last < 0) null else (last + 1) * WIN
    }

    /** Where the first non-silent window that starts at or after [sample] starts, or null. */
    fun speechAfter(sample: Int): Int? = synchronized(this) {
        (((sample + WIN - 1) / WIN) until speech.size).firstOrNull { speech[it] }?.let { it * WIN }
    }

    fun copy(from: Int, to: Int): ShortArray = synchronized(this) { pcm.copyOfRange(from, to) }

    private fun append(chunk: ShortArray, length: Int) {
        if (size + length > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, size + length))
        System.arraycopy(chunk, 0, pcm, size, length)
        size += length
    }

    private fun classifyNextWindow() {
        val a = energies * WIN
        var energy = 0L
        for (i in a until a + WIN) energy += pcm[i].toLong() * pcm[i]
        var at = sorted.binarySearch(energy)
        if (at < 0) at = -at - 1
        sorted.add(at, energy)
        val floor = max(sorted[(sorted.size - 1) / 10].toDouble(), floorMin)
        speech.add(energy.toDouble() >= floor * margin)
        if (speech.last()) lastSpeech = energies
        if (energy.toDouble() >= loudEnergy) lastLoud = energies
        energies++
    }

    private fun cutAt(boundary: Int, done: MutableList<IntArray>) {
        while (true) {
            if (boundary - start > maxLen) {
                done += hardCut()
                continue
            }
            val cut = pauseCut(boundary) ?: return
            done += intArrayOf(start, cut)
            start = cut
        }
    }

    private fun pauseCut(boundary: Int): Int? {
        val speech = lastSpeech
        val need = cutWindows ?: return null
        if (speech < 0 || speech < (start + WIN - 1) / WIN || boundary - start < minLen) return null
        if (energies - 1 - speech < need) return null
        return ((speech + 1) * WIN + boundary) / 2
    }

    private fun hardCut(): IntArray {
        val hardEnd = start + maxLen
        val lo = hardEnd - search
        val energy = DoubleArray(hardEnd - lo + 1)
        for (i in lo until hardEnd) {
            val v = pcm[i] / 32768.0
            energy[i - lo + 1] = energy[i - lo] + v * v
        }
        var best = -1
        var bestEnergy = 0.0
        for (k in 0..energy.size - 1 - WIN) {
            val windowEnergy = energy[k + WIN] - energy[k]
            if (best < 0 || windowEnergy < bestEnergy) {
                best = k
                bestEnergy = windowEnergy
            }
        }
        val cut = lo + best + WIN / 2
        val slice = intArrayOf(start, cut)
        start = cut
        return slice
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CUT_WINDOW_S = 0.1
        private const val WIN = (SAMPLE_RATE * CUT_WINDOW_S).toInt()
        private const val NOISE_FLOOR_DBFS = -70.0

        fun fromConfig(cfg: PipelineConfig) =
            Slicer(cfg.sliceMaxS, cfg.sliceSearchS, cfg.pauseS, cfg.sliceMinS, cfg.pauseMarginDb, cfg.stopSilenceDbfs)

        fun fromConfigForField(cfg: PipelineConfig): Slicer {
            val hard = cfg.fieldSliceMaxS
            val search = min(cfg.sliceSearchS, hard - 1)
            return Slicer(hard, search, cfg.pauseS, cfg.sliceMinS, cfg.pauseMarginDb, cfg.stopSilenceDbfs, cfg.fieldCutPauseS)
        }
    }
}
