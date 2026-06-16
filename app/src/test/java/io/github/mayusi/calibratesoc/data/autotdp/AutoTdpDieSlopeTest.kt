package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import org.junit.Test

/**
 * MEDIUM — dTemp-slope EWMA must fold ONLY the newest per-tick delta into the carried
 * slope (matching the GPU EWMA pattern), NOT re-fold the whole overlapping window every
 * tick. Re-folding over-inflated/over-attenuated the slope, driving spurious early
 * tightens / COOL_QUIET routing.
 *
 * We drive [AutoTdpEngine.decide] and read back the persisted slope from the returned
 * [ControllerState.dTempSlopeEwma].
 *
 * Math (α = 0.5, DTEMP_MIN_TICKS = 3, window = 4):
 *  - Tick 1 SEEDS from a rising window die-temps [40,42,44,46] °C → deltas [2,2,2]:
 *      s = 2.0; s = 0.5*2 + 0.5*2.0 = 2.0; s = 2.0  → carried slope = 2.0.
 *  - Tick 2 window is FLAT [46,46,46,46] (newest delta = 0):
 *      NEWEST-ONLY (correct): 0.5*0 + 0.5*2.0 = 1.0.
 *      RE-FOLD WHOLE WINDOW (bug): from 2.0 over deltas [0,0,0] → 1.0 → 0.5 → 0.25.
 *  So correct == 1.0, the old bug == 0.25 — a clean discriminator.
 */
class AutoTdpDieSlopeTest {

    private val bigOpps = listOf(
        499_000, 844_000, 1_171_000, 1_536_000,
        1_920_000, 2_323_000, 2_707_000, 2_803_000,
    )

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = bigOpps,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = 160_000_000L,
        gpuDevfreqCeilHz = 1_100_000_000L,
        gpuDevfreqStepsHz = listOf(160_000_000L, 1_100_000_000L),
        gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    private val config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED)

    private fun telDie(dieC: Int) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 30 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT,
        gpuLoadPct = 50,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = emptyList(),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = null,
        gpuDieTempMilliC = dieC * 1000, // sane milli-C so the engine uses the die
        coolingDeviceMaxState = null,
        realFpsX10 = null,
        isRealFps = false,
    )

    @Test
    fun `dTemp slope folds only the newest delta on the carried tick`() {
        // Tick 1: a rising window seeds the slope.
        val risingWindow = listOf(40, 42, 44, 46).map { telDie(it) }
        val d1 = AutoTdpEngine.decide(
            window = risingWindow,
            config = config,
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
        )
        val seeded = d1.controllerState.dTempSlopeEwma
        assertThat(seeded).isNotNull()
        assertThat(seeded!!).isWithin(1e-9).of(2.0)

        // Tick 2: a FLAT window (newest delta = 0). Newest-only EWMA → 1.0; the old
        // whole-window re-fold would have produced 0.25.
        val flatWindow = listOf(46, 46, 46, 46).map { telDie(it) }
        val d2 = AutoTdpEngine.decide(
            window = flatWindow,
            config = config,
            caps = caps,
            current = d1.target,
            controllerState = d1.controllerState,
        )
        val carried = d2.controllerState.dTempSlopeEwma
        assertThat(carried).isNotNull()
        assertThat(carried!!).isWithin(1e-9).of(1.0)
    }
}
