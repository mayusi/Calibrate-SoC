package io.github.mayusi.calibratesoc.data.monitor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [TempAlertEvaluator] (no Android runtime needed).
 *
 * Covers:
 *   1. Hottest-temp selection across cpu, gpu, battery zones.
 *   2. Threshold crossing true/false.
 *   3. Missing temps: no zones + null battery → not tripped, no crash.
 *   4. Battery-only hottest source.
 *   5. GPU label variants ("gpu", "kgsl").
 *
 * Hysteresis and rate-limiting are stateful and live in [TempAlertMonitor],
 * not in the pure evaluator. Those are exercised in their own test class
 * (TempAlertMonitorTest) which can mock time; not needed here.
 */
class TempAlertEvaluatorTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun zone(label: String, tempC: Float) =
        ZoneTemp(zoneId = 0, label = label, tempMilliC = (tempC * 1000).toInt())

    private fun telemetry(
        zones: List<ZoneTemp> = emptyList(),
        batteryTempDeciC: Int? = null,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = emptyList(),
        perCoreLoadPct = emptyList(),
        gpuLoadPct = null,
        gpuFreqHz = null,
        zoneTempsMilliC = zones,
        ramTotalKb = 0L,
        ramAvailableKb = 0L,
        batteryTempDeciC = batteryTempDeciC,
        batteryCurrentUa = null,
        batteryVoltageUv = null,
        fanRpm = null,
    )

    // ─── No readable temps ────────────────────────────────────────────────────

    @Test
    fun `no zones no battery — not tripped, hottestC is null, no crash`() {
        val result = TempAlertEvaluator.evaluate(telemetry(), thresholdC = 80)
        assertThat(result.tripped).isFalse()
        assertThat(result.hottestC).isNull()
    }

    @Test
    fun `empty zones but battery present — battery is the hottest source`() {
        // 85 °C battery, no zone sensors
        val result = TempAlertEvaluator.evaluate(
            telemetry(batteryTempDeciC = 850),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(85f)
        assertThat(result.sourceLabel).isEqualTo("battery")
    }

    // ─── Threshold boundary ───────────────────────────────────────────────────

    @Test
    fun `temp exactly at threshold — tripped`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(zones = listOf(zone("cpu0", 80f))),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
    }

    @Test
    fun `temp one degree below threshold — not tripped`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(zones = listOf(zone("cpu0", 79f))),
            thresholdC = 80,
        )
        assertThat(result.tripped).isFalse()
    }

    @Test
    fun `temp well above threshold — tripped`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(zones = listOf(zone("CPU-big", 95f))),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(95f)
    }

    // ─── Hottest-temp selection across sensor types ────────────────────────────

    @Test
    fun `cpu zone hotter than gpu and battery — cpu wins`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(
                zones = listOf(
                    zone("cpu0", 90f),
                    zone("gpu0", 70f),
                    zone("cpu1", 80f),
                ),
                batteryTempDeciC = 400, // 40 °C
            ),
            thresholdC = 85,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(90f)
        assertThat(result.sourceLabel).isEqualTo("cpu0")
    }

    @Test
    fun `gpu zone hotter than cpu and battery — gpu wins`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(
                zones = listOf(
                    zone("cpu0", 60f),
                    zone("gpu0", 88f),
                ),
                batteryTempDeciC = 500, // 50 °C
            ),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(88f)
        assertThat(result.sourceLabel).isEqualTo("gpu0")
    }

    @Test
    fun `battery hotter than cpu and gpu — battery wins`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(
                zones = listOf(
                    zone("cpu0", 65f),
                    zone("gpu0", 70f),
                ),
                batteryTempDeciC = 850, // 85 °C
            ),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(85f)
        assertThat(result.sourceLabel).isEqualTo("battery")
    }

    // ─── GPU label variants ───────────────────────────────────────────────────

    @Test
    fun `kgsl label is treated as gpu zone`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(zones = listOf(zone("kgsl-3d0", 83f))),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(83f)
        assertThat(result.sourceLabel).isEqualTo("kgsl-3d0")
    }

    @Test
    fun `GPU label case-insensitive — GPU-BIG is recognised`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(zones = listOf(zone("GPU-BIG", 82f))),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
    }

    // ─── Non-cpu/gpu zones are ignored ────────────────────────────────────────

    @Test
    fun `ambient and charger zones do not trigger alerts`() {
        // Only zones labelled cpu* / gpu* / kgsl* / battery count.
        // An "ambient" or "charger" zone should not trip the alert.
        val result = TempAlertEvaluator.evaluate(
            telemetry(
                zones = listOf(
                    zone("ambient", 99f),  // should be ignored
                    zone("charger", 99f),  // should be ignored
                    zone("cpu0", 60f),     // only real source
                ),
                batteryTempDeciC = null,
            ),
            thresholdC = 80,
        )
        assertThat(result.tripped).isFalse()
        assertThat(result.hottestC).isWithin(0.05f).of(60f)
        assertThat(result.sourceLabel).isEqualTo("cpu0")
    }

    // ─── Multiple cpu zones — hottest wins ────────────────────────────────────

    @Test
    fun `multiple cpu zones — peak wins`() {
        val result = TempAlertEvaluator.evaluate(
            telemetry(
                zones = listOf(
                    zone("cpu-cluster0", 72f),
                    zone("cpu-cluster1", 84f),
                    zone("cpu-cluster2", 78f),
                ),
            ),
            thresholdC = 80,
        )
        assertThat(result.tripped).isTrue()
        assertThat(result.hottestC).isWithin(0.05f).of(84f)
        assertThat(result.sourceLabel).isEqualTo("cpu-cluster1")
    }
}
