package io.github.mayusi.calibratesoc.ui.overlay

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Pure-JVM tests for [HudStateAssembler.assembleTelemetryFields] — the per-tick
 * telemetry → HUD-field mapping. No Android, no coroutines: the mapping was
 * extracted from the collect loop precisely so these honesty rules are testable.
 *
 * Load-bearing invariants covered:
 *  - battery TEMPERATURE is surfaced (deci-°C → °C).
 *  - CPU PEAK temp is the MAX cpu zone (never the average that hides a hot core).
 *  - GPU temp FALLS BACK to the die-temp probe when no gpu/kgsl thermal zone exists.
 *  - the freq-proxy load source is flagged (loadIsProxy) for the "~" honesty marker.
 *  - missing sensors stay null — never a fabricated value.
 */
class HudStateAssemblerTest {

    /** Build a Telemetry with only the fields a test cares about; rest are inert. */
    private fun telemetry(
        perCoreCpuFreqKhz: List<Int> = listOf(2_000_000),
        perCoreLoadPct: List<Int> = listOf(50),
        cpuLoadSource: CpuLoadReading.Source = CpuLoadReading.Source.DIRECT_PROC_STAT,
        gpuLoadPct: Int? = null,
        gpuFreqHz: Long? = null,
        zoneTempsMilliC: List<ZoneTemp> = emptyList(),
        ramTotalKb: Long = 0,
        ramAvailableKb: Long = 0,
        batteryTempDeciC: Int? = null,
        batteryCurrentUa: Long? = null,
        batteryVoltageUv: Long? = null,
        gpuDieTempMilliC: Int? = null,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = perCoreCpuFreqKhz,
        perCoreLoadPct = perCoreLoadPct,
        cpuLoadSource = cpuLoadSource,
        gpuLoadPct = gpuLoadPct,
        gpuFreqHz = gpuFreqHz,
        zoneTempsMilliC = zoneTempsMilliC,
        ramTotalKb = ramTotalKb,
        ramAvailableKb = ramAvailableKb,
        batteryTempDeciC = batteryTempDeciC,
        batteryCurrentUa = batteryCurrentUa,
        batteryVoltageUv = batteryVoltageUv,
        fanRpm = null,
        gpuDieTempMilliC = gpuDieTempMilliC,
    )

    private fun cpuZone(id: Int, milliC: Int) = ZoneTemp(id, "cpu$id-silver", milliC)

    // ── battery temperature ───────────────────────────────────────────────────

    @Test
    fun `battery temp deci-C is converted to celsius`() {
        val f = HudStateAssembler.assembleTelemetryFields(telemetry(batteryTempDeciC = 312))
        assertThat(f.batteryTempC).isWithin(0.001f).of(31.2f)
    }

    @Test
    fun `battery temp is null when sensor absent`() {
        val f = HudStateAssembler.assembleTelemetryFields(telemetry(batteryTempDeciC = null))
        assertThat(f.batteryTempC).isNull()
    }

    // ── CPU peak vs average ───────────────────────────────────────────────────

    @Test
    fun `cpu peak temp is the hottest zone not the average`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(
                zoneTempsMilliC = listOf(
                    cpuZone(0, 60_000),  // 60°C
                    cpuZone(1, 90_000),  // 90°C — the hot core
                ),
            ),
        )
        // Average would be 75; the HUD must surface the PEAK = 90.
        assertThat(f.cpuPeakTempC).isWithin(0.001f).of(90f)
        assertThat(f.cpuTempC).isWithin(0.001f).of(75f)
        assertThat(f.cpuPeakTempC).isGreaterThan(f.cpuTempC)
    }

    @Test
    fun `cpu temps are null when no cpu zones`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(zoneTempsMilliC = listOf(ZoneTemp(9, "battery", 30_000))),
        )
        assertThat(f.cpuTempC).isNull()
        assertThat(f.cpuPeakTempC).isNull()
    }

    // ── GPU temp + die fallback ───────────────────────────────────────────────

    @Test
    fun `gpu temp prefers a gpu thermal zone over the die probe`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(
                zoneTempsMilliC = listOf(ZoneTemp(3, "gpu-usr", 72_000)),
                gpuDieTempMilliC = 80_000, // present but must NOT win — zone is preferred
            ),
        )
        assertThat(f.gpuTempC).isWithin(0.001f).of(72f)
    }

    @Test
    fun `gpu temp falls back to die probe when no gpu zone exists`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(
                zoneTempsMilliC = listOf(cpuZone(0, 65_000)), // only a cpu zone
                gpuDieTempMilliC = 78_000,                    // 78°C die-only device
            ),
        )
        assertThat(f.gpuTempC).isWithin(0.001f).of(78f)
    }

    @Test
    fun `gpu temp picks up kgsl-labelled zones`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(zoneTempsMilliC = listOf(ZoneTemp(4, "kgsl-3d0", 70_000))),
        )
        assertThat(f.gpuTempC).isWithin(0.001f).of(70f)
    }

    @Test
    fun `gpu temp is null when neither zone nor die probe present`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(zoneTempsMilliC = listOf(cpuZone(0, 60_000)), gpuDieTempMilliC = null),
        )
        assertThat(f.gpuTempC).isNull()
    }

    // ── load-source proxy flag ────────────────────────────────────────────────

    @Test
    fun `loadIsProxy true only for freq-proxy source`() {
        assertThat(
            HudStateAssembler.assembleTelemetryFields(
                telemetry(cpuLoadSource = CpuLoadReading.Source.FREQ_PROXY),
            ).loadIsProxy,
        ).isTrue()
        for (src in listOf(
            CpuLoadReading.Source.ROOT_PROC_STAT,
            CpuLoadReading.Source.DIRECT_PROC_STAT,
            CpuLoadReading.Source.UNAVAILABLE,
        )) {
            assertThat(
                HudStateAssembler.assembleTelemetryFields(telemetry(cpuLoadSource = src)).loadIsProxy,
            ).isFalse()
        }
    }

    // ── derived freq / power / ram ────────────────────────────────────────────

    @Test
    fun `cpu max mhz is derived from the highest core khz`() {
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(perCoreCpuFreqKhz = listOf(1_800_000, 2_918_000, 1_200_000)),
        )
        assertThat(f.cpuMaxMhz).isEqualTo(2918)
        assertThat(f.perCoreMhz).containsExactly(1800, 2918, 1200).inOrder()
    }

    @Test
    fun `gpu mhz is null when freq absent and derived when present`() {
        assertThat(HudStateAssembler.assembleTelemetryFields(telemetry(gpuFreqHz = null)).gpuMhz).isNull()
        // 540 MHz = 540_000_000 Hz
        assertThat(
            HudStateAssembler.assembleTelemetryFields(telemetry(gpuFreqHz = 540_000_000L)).gpuMhz,
        ).isEqualTo(540)
    }

    @Test
    fun `battery watts derived from current and voltage, null when either missing`() {
        // |−1_000_000 µA| × 3_800_000 µV / 1e9 = 3800 mW → 3.8 W
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(batteryCurrentUa = -1_000_000L, batteryVoltageUv = 3_800_000L),
        )
        assertThat(f.batteryW).isWithin(0.001).of(3.8)
        assertThat(
            HudStateAssembler.assembleTelemetryFields(
                telemetry(batteryCurrentUa = null, batteryVoltageUv = 3_800_000L),
            ).batteryW,
        ).isNull()
    }

    @Test
    fun `battery watts is unavailable (null) when current reads literal zero`() {
        // RP6 honesty fix: /sys/.../current_now returns a literal 0 while the
        // device is actively running. A true 0 W draw is impossible there, so 0
        // means "unreadable" → batteryW must be null (HUD shows "--"), NOT 0.0W.
        assertThat(
            HudStateAssembler.assembleTelemetryFields(
                telemetry(batteryCurrentUa = 0L, batteryVoltageUv = 3_800_000L),
            ).batteryW,
        ).isNull()
    }

    @Test
    fun `battery watts is a real value for a small but non-zero current`() {
        // A genuine non-zero measurement is honest even when tiny — 50 mA at 3.8 V
        // = 0.19 W. Only literal-0/null are treated as unavailable.
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(batteryCurrentUa = 50_000L, batteryVoltageUv = 3_800_000L),
        )
        assertThat(f.batteryW).isNotNull()
        assertThat(f.batteryW!!).isWithin(0.001).of(0.19)
    }

    @Test
    fun `ram used pct null when total is zero`() {
        assertThat(
            HudStateAssembler.assembleTelemetryFields(telemetry(ramTotalKb = 0)).ramUsedPct,
        ).isNull()
        val f = HudStateAssembler.assembleTelemetryFields(
            telemetry(ramTotalKb = 8_000_000, ramAvailableKb = 2_000_000),
        )
        assertThat(f.ramUsedPct).isEqualTo(75)
    }
}
