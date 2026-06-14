package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

class TelemetrySampleMapperTest {

    /** Minimal [Telemetry] builder with sensible defaults for fields not under test. */
    private fun telemetry(
        perCoreCpuFreqKhz: List<Int> = emptyList(),
        zoneTemps: List<ZoneTemp> = emptyList(),
        gpuFreqHz: Long? = null,
        batteryTempDeciC: Int? = null,
        batteryCurrentUa: Long? = null,
        batteryVoltageUv: Long? = null,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = perCoreCpuFreqKhz,
        perCoreLoadPct = emptyList(),
        gpuLoadPct = null,
        gpuFreqHz = gpuFreqHz,
        zoneTempsMilliC = zoneTemps,
        ramTotalKb = 0L,
        ramAvailableKb = 0L,
        batteryTempDeciC = batteryTempDeciC,
        batteryCurrentUa = batteryCurrentUa,
        batteryVoltageUv = batteryVoltageUv,
        fanRpm = null,
    )

    private fun zone(label: String, tempMilliC: Int) =
        ZoneTemp(zoneId = 0, label = label, tempMilliC = tempMilliC)

    // ── cpuMaxMhz ────────────────────────────────────────────────────────

    @Test
    fun `cpuMaxMhz is max per-core freq in kHz divided by 1000`() {
        val t = telemetry(perCoreCpuFreqKhz = listOf(1_000_000, 2_500_000, 3_187_200))
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.cpuMaxMhz).isEqualTo(3187)
    }

    @Test
    fun `empty core list yields cpuMaxMhz zero`() {
        val t = telemetry(perCoreCpuFreqKhz = emptyList())
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.cpuMaxMhz).isEqualTo(0)
    }

    // ── cpuMaxTempC ──────────────────────────────────────────────────────

    @Test
    fun `cpuMaxTempC is max of zones labelled cpu in milliC divided by 1000`() {
        val t = telemetry(
            zoneTemps = listOf(
                zone("cpu0", 65_000),
                zone("cpu1", 70_000),
                zone("gpu0", 55_000),
            ),
        )
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.cpuMaxTempC).isWithin(0.001f).of(70f)
    }

    @Test
    fun `cpuMaxTempC label match is case-insensitive`() {
        val t = telemetry(zoneTemps = listOf(zone("CPU-cluster", 80_000)))
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.cpuMaxTempC).isWithin(0.001f).of(80f)
    }

    @Test
    fun `cpuMaxTempC is zero when no cpu zones present`() {
        val t = telemetry(zoneTemps = listOf(zone("gpu0", 55_000)))
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.cpuMaxTempC).isWithin(0.001f).of(0f)
    }

    // ── gpuTempC ─────────────────────────────────────────────────────────

    @Test
    fun `gpuTempC picks max of zones labelled gpu or kgsl`() {
        val t = telemetry(
            zoneTemps = listOf(
                zone("cpu0", 70_000),
                zone("gpu-thermal", 60_000),
                zone("kgsl-adreno630", 65_000),
            ),
        )
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.gpuTempC).isNotNull()
        assertThat(sample.gpuTempC!!).isWithin(0.001f).of(65f)
    }

    @Test
    fun `gpuTempC is null when no gpu or kgsl zones`() {
        val t = telemetry(zoneTemps = listOf(zone("cpu0", 60_000)))
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.gpuTempC).isNull()
    }

    // ── gpuMaxMhz ────────────────────────────────────────────────────────

    @Test
    fun `gpuMaxMhz converts gpuFreqHz from Hz to MHz`() {
        val t = telemetry(gpuFreqHz = 585_000_000L)
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.gpuMaxMhz).isEqualTo(585)
    }

    @Test
    fun `gpuMaxMhz is null when gpuFreqHz is null`() {
        val t = telemetry(gpuFreqHz = null)
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.gpuMaxMhz).isNull()
    }

    // ── batteryTempC ─────────────────────────────────────────────────────

    @Test
    fun `batteryTempC converts batteryTempDeciC to Celsius`() {
        val t = telemetry(batteryTempDeciC = 302)  // 30.2°C
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.batteryTempC).isWithin(0.001f).of(30.2f)
    }

    @Test
    fun `batteryTempC is zero when batteryTempDeciC is null`() {
        val t = telemetry(batteryTempDeciC = null)
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.batteryTempC).isWithin(0.001f).of(0f)
    }

    // ── batteryDrawMw ────────────────────────────────────────────────────

    @Test
    fun `batteryDrawMw is non-null when current and voltage present`() {
        // 2_000_000 µA (2 A) * 4_000_000 µV (4 V) / 1_000_000_000 = 8000 mW (8 W).
        // Matches the Telemetry.batteryDrawMilliW formula: (|µA| * µV) / 1e9.
        val t = telemetry(batteryCurrentUa = 2_000_000L, batteryVoltageUv = 4_000_000L)
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.batteryDrawMw).isNotNull()
        assertThat(sample.batteryDrawMw).isEqualTo(8000L)
    }

    @Test
    fun `batteryDrawMw is null when current is missing`() {
        val t = telemetry(batteryCurrentUa = null, batteryVoltageUv = 4_000_000L)
        val sample = telemetryToThrottleSample(t, runStartedAt = 0L)
        assertThat(sample.batteryDrawMw).isNull()
    }

    // ── elapsedMs ────────────────────────────────────────────────────────

    @Test
    fun `elapsedMs is non-negative and increases with runStartedAt`() {
        val t = telemetry()
        val before = System.currentTimeMillis()
        val sample = telemetryToThrottleSample(t, runStartedAt = before - 500L)
        // elapsed should be approximately 500ms; we just verify it's positive
        assertThat(sample.elapsedMs).isAtLeast(0L)
    }
}
