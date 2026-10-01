package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeCostTest {
    private class FakeProbe : CostProbe {
        var now = 0L
        var currentUa = -300_000
        var counter = 4_000_000
        var charging = false
        var pss = 1_000_000
        var avail = 3_000_000L
        var low = false
        var thermal = 0
        var cpu = 0L
        override fun elapsedMs() = now
        override fun currentNowUa() = currentUa
        override fun chargeCounterUah() = counter
        override fun batteryPct() = 79
        override fun charging() = charging
        override fun pssKb() = pss
        override fun availMemKb() = avail
        override fun lowMemory() = low
        override fun thermalStatus() = thermal
        override fun cpuMs() = cpu
    }

    private fun fields(cost: TakeCost) = cost.lap().toMap()

    @Test
    fun given_a_steady_discharge_when_sampled_then_charge_is_the_integral_in_microamp_hours() {
        val probe = FakeProbe()
        val cost = TakeCost(probe).also { it.start() }
        repeat(10) {
            probe.now += 1000
            cost.sample()
        }
        probe.counter -= 833

        val f = fields(cost)
        assertEquals(833L, f["charge_uah"])
        assertEquals(833, f["counter_used_uah"])
        assertEquals(false, f["plugged"])
        assertEquals(79, f["battery_pct"])
    }

    @Test
    fun given_peaks_and_pressure_when_sampled_then_the_extremes_are_kept() {
        val probe = FakeProbe()
        val cost = TakeCost(probe).also { it.start() }
        probe.now += 1000; probe.pss = 1_900_000; probe.avail = 900_000L; probe.thermal = 2; cost.sample()
        probe.now += 1000; probe.pss = 1_200_000; probe.avail = 2_000_000L; probe.thermal = 1; probe.low = true; cost.sample()
        cost.noteTrim(10)
        cost.noteTrim(5)
        probe.cpu = 4200

        val f = fields(cost)
        assertEquals(1_900_000, f["peak_pss_kb"])
        assertEquals(900_000L, f["avail_mem_min_kb"])
        assertEquals(2, f["thermal_max"])
        assertEquals(true, f["low_memory"])
        assertEquals(10, f["trim_level_max"])
        assertEquals(4200L, f["cpu_ms"])
    }

    @Test
    fun given_charging_at_any_sample_when_lapped_then_the_window_is_plugged() {
        val probe = FakeProbe()
        val cost = TakeCost(probe).also { it.start() }
        probe.now += 1000; probe.charging = true; cost.sample()

        assertTrue(fields(cost)["plugged"] as Boolean)
    }

    @Test
    fun given_two_windows_when_lapped_then_each_reports_only_its_own_cost() {
        val probe = FakeProbe()
        val cost = TakeCost(probe).also { it.start() }
        probe.now += 3600; probe.cpu = 1000; probe.thermal = 3; cost.sample()
        probe.thermal = 0
        val first = fields(cost)
        probe.now += 3600; probe.cpu = 1500; cost.sample()
        val second = fields(cost)

        assertEquals(1000L, first["cpu_ms"])
        assertEquals(3, first["thermal_max"])
        assertEquals(500L, second["cpu_ms"])
        assertEquals(0, second["thermal_max"])
        assertFalse(second["plugged"] as Boolean)
    }

    @Test
    fun given_exit_records_when_filtered_then_only_those_after_the_newest_logged_exit_remain_oldest_first() {
        fun exit(ms: Long) = ExitRecord(ms, "LOW_MEMORY", 0, 100, 1, 1, null)
        val fresh = ExitReasons.fresh(listOf(exit(300), exit(100), exit(200)), 150)

        assertEquals(listOf(200L, 300L), fresh.map { it.timestampMs })
        assertEquals(3, ExitReasons.fresh(listOf(exit(1), exit(2), exit(3)), null).size)
    }

    @Test
    fun given_log_lines_when_scanned_then_the_newest_exit_timestamp_is_found() {
        val lines = listOf(
            """{"event":"insert","ts":"2026-10-01T10:00:00Z"}""",
            """{"event":"exit","ts":"2026-10-01T09:00:00Z","reason":"CRASH"}""",
            """{"event":"exit","ts":"2026-10-01T11:00:00Z","reason":"LOW_MEMORY"}""",
        )

        assertEquals(java.time.Instant.parse("2026-10-01T11:00:00Z").toEpochMilli(), ExitReasons.newestLogged(lines))
        assertNull(ExitReasons.newestLogged(listOf(lines[0])))
    }
}
