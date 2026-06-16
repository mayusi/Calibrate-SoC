package io.github.mayusi.calibratesoc.data.boost

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.ThermalKillEvaluator
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Unit tests for [BoostGuard] — the pure time-box + thermal-trip revert logic for a
 * Game Boost session. No Android, no device.
 *
 * Confirms:
 *  - the session continues within the time box at safe temps,
 *  - the time box fires (and only after it elapses),
 *  - a sustained over-temp trips (reusing ThermalKillEvaluator's debounce + grace),
 *  - thermal takes PRIORITY over the time box.
 */
class BoostGuardTest {

    private fun telemetry(tempC: Int) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = emptyList(),
        perCoreLoadPct = emptyList(),
        gpuLoadPct = null,
        gpuFreqHz = null,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu-0", tempC * 1000)),
        ramTotalKb = 0L,
        ramAvailableKb = 0L,
        batteryTempDeciC = null,
        batteryCurrentUa = null,
        batteryVoltageUv = null,
        fanRpm = null,
    )

    private val start = 1_000_000L

    /** A zero-grace, 1-consecutive evaluator so thermal-trip tests are deterministic. */
    private fun eagerThermal() = ThermalKillEvaluator(
        killThresholdMilliC = 105_000,
        requiredConsecutive = 1,
        graceSamples = 0,
    )

    // ─── Continue path ───────────────────────────────────────────────────────────

    @Test
    fun `continues within time box at safe temperature`() {
        val guard = BoostGuard(
            timeBoxMillis = 30 * 60_000L,
            sessionStartEpochMs = start,
            thermalKill = eagerThermal(),
        )
        val d = guard.evaluate(start + 60_000L, telemetry(70))
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.NONE)
        assertThat(d.reason).isNull()
    }

    // ─── Time box ────────────────────────────────────────────────────────────────

    @Test
    fun `does not fire time box one ms before expiry`() {
        val box = 10 * 60_000L
        val guard = BoostGuard(box, start, eagerThermal())
        val d = guard.evaluate(start + box - 1, telemetry(70))
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.NONE)
    }

    @Test
    fun `fires time box at and after expiry`() {
        val box = 10 * 60_000L
        val guard = BoostGuard(box, start, eagerThermal())
        val d = guard.evaluate(start + box, telemetry(70))
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.TIME_BOX)
        assertThat(d.reason).contains("Time box")
    }

    // ─── Thermal trip ────────────────────────────────────────────────────────────

    @Test
    fun `trips on sustained over-threshold temperature`() {
        val guard = BoostGuard(30 * 60_000L, start, eagerThermal())
        val d = guard.evaluate(start + 5_000L, telemetry(106))
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.THERMAL)
        assertThat(d.reason).contains("Thermal kill")
    }

    @Test
    fun `does not trip below threshold`() {
        val guard = BoostGuard(30 * 60_000L, start, eagerThermal())
        val d = guard.evaluate(start + 5_000L, telemetry(100))
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.NONE)
    }

    @Test
    fun `default thermal evaluator honours debounce (single spike does not trip)`() {
        // Default config: 2 consecutive required, 3-sample grace. One hot sample after
        // grace should NOT trip.
        val guard = BoostGuard(30 * 60_000L, start) // default ThermalKillEvaluator
        // Burn the 3-sample grace with safe temps.
        repeat(3) { guard.evaluate(start + it * 1_000L, telemetry(70)) }
        val d = guard.evaluate(start + 4_000L, telemetry(106)) // first hot sample post-grace
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.NONE) // needs a 2nd consecutive
    }

    // ─── Priority ────────────────────────────────────────────────────────────────

    @Test
    fun `thermal trip takes priority over an expired time box`() {
        // Time box already elapsed AND temperature over threshold — thermal wins.
        val guard = BoostGuard(1_000L, start, eagerThermal())
        val d = guard.evaluate(start + 10_000L, telemetry(106))
        assertThat(d.stop).isEqualTo(BoostGuard.BoostStop.THERMAL)
    }
}
