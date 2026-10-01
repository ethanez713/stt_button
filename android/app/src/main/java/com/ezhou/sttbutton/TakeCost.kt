package com.ezhou.sttbutton

/** What the cost fields of an insert line are measured from; the Android implementation is [AndroidCostProbe]. */
interface CostProbe {
    fun elapsedMs(): Long
    /** BatteryManager's CURRENT_NOW as reported: by Android's contract, negative while the battery discharges. */
    fun currentNowUa(): Int
    fun chargeCounterUah(): Int
    fun batteryPct(): Int
    fun charging(): Boolean
    fun pssKb(): Int
    fun availMemKb(): Long
    fun lowMemory(): Boolean
    fun thermalStatus(): Int
    fun cpuMs(): Long
}

/**
 * The cost of one window of a take, from its first sample to the end of insertion (plan 07). [lap] returns
 * the window's fields and opens the next one, so a take that inserts several segments reports each
 * segment's own cost.
 */
class TakeCost(private val probe: CostProbe) {
    private var lastSampleMs = 0L
    private var lastDrawUa = 0
    private var chargeUah = 0.0
    private var plugged = false
    private var batteryPct = 0
    private var startCounterUah = 0
    private var endCounterUah = 0
    private var startCpuMs = 0L
    private var peakPssKb = 0
    private var minAvailKb = Long.MAX_VALUE
    private var lowMemory = false
    private var thermalMax = 0
    private var trimMax = 0

    @Synchronized
    fun start() {
        startWindow()
        batteryPct = probe.batteryPct()
    }

    @Synchronized
    fun sample() {
        val now = probe.elapsedMs()
        val draw = -probe.currentNowUa()
        chargeUah += (lastDrawUa + draw) / 2.0 * (now - lastSampleMs) / MS_PER_HOUR
        lastSampleMs = now
        lastDrawUa = draw
        plugged = plugged || probe.charging()
        endCounterUah = probe.chargeCounterUah()
        peakPssKb = maxOf(peakPssKb, probe.pssKb())
        minAvailKb = minOf(minAvailKb, probe.availMemKb())
        lowMemory = lowMemory || probe.lowMemory()
        thermalMax = maxOf(thermalMax, probe.thermalStatus())
    }

    @Synchronized
    fun noteTrim(level: Int) {
        trimMax = maxOf(trimMax, level)
    }

    @Synchronized
    fun lap(): List<Pair<String, Any?>> {
        sample()
        val fields = listOf(
            "charge_uah" to chargeUah.toLong(), "counter_used_uah" to (startCounterUah - endCounterUah),
            "plugged" to plugged, "battery_pct" to batteryPct,
            "peak_pss_kb" to peakPssKb, "avail_mem_min_kb" to minAvailKb, "low_memory" to lowMemory,
            "trim_level_max" to trimMax, "cpu_ms" to probe.cpuMs() - startCpuMs, "thermal_max" to thermalMax,
        )
        startWindow()
        batteryPct = probe.batteryPct()
        return fields
    }

    private fun startWindow() {
        lastSampleMs = probe.elapsedMs()
        lastDrawUa = -probe.currentNowUa()
        chargeUah = 0.0
        plugged = probe.charging()
        startCounterUah = probe.chargeCounterUah()
        endCounterUah = startCounterUah
        startCpuMs = probe.cpuMs()
        peakPssKb = probe.pssKb()
        minAvailKb = probe.availMemKb()
        lowMemory = probe.lowMemory()
        thermalMax = probe.thermalStatus()
        trimMax = 0
    }

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
    }
}

/** Calls [TakeCost.sample] once a second from take start until [stop], so a long decode cannot hide a peak. */
class CostSampler(private val cost: TakeCost, private val intervalMs: Long = 1000) {
    @Volatile private var running = true
    private val thread = Thread {
        while (running) {
            runCatching { cost.sample() }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }.apply { name = "cost-sampler"; isDaemon = true; start() }

    fun stop() {
        running = false
        thread.interrupt()
        thread.join()
    }
}
