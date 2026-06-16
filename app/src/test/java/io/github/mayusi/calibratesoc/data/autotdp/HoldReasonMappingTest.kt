package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Verifies the Smart-AutoTDP band controller sets the correct [HoldReason] in the
 * branches the UI builds against. The mapping is part of the data contract two UI
 * agents render, so each surfaced branch is asserted explicitly.
 *
 * Topology mirrors AutoTdpEngineTest (SD8Gen2-style 3-cluster):
 *   prime core = 7, big policy = 4.
 *
 * NOTE: under the band controller the BATTERY_TARGET_HOLDING branch is no longer
 * EMITTED (BATTERY_SAVER follows the band with the watts step as the cap floor and
 * surfaces GPU_BOUND_CAPPING / IDLE_HOLDING). The enum value is retained for UI
 * back-compat; we do not assert it is produced.
 */
class HoldReasonMappingTest {

    private val caps3Cluster = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(
            499_000, 844_000, 1_171_000, 1_536_000,
            1_920_000, 2_323_000, 2_707_000, 2_803_000,
        ),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    private val balancedConfig = AutoTdpProfileConfig(AutoTdpProfile.BALANCED)

    private fun telemetry(
        gpuLoad: Int,
        coreLoads: List<Int>,
        source: CpuLoadReading.Source = CpuLoadReading.Source.DIRECT_PROC_STAT,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(coreLoads.size.coerceAtLeast(8)) { 1_000_000 },
        perCoreLoadPct = coreLoads,
        cpuLoadSource = source,
        gpuLoadPct = gpuLoad,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = emptyList<ZoneTemp>(),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
    )

    /** Drive the engine [n] ticks on the same window so timing/cool-down gates clear. */
    private fun run(
        window: List<Telemetry>,
        current: TdpState,
        config: AutoTdpProfileConfig = balancedConfig,
        ticks: Int = 6,
    ): TdpDecision {
        var state = ControllerState.INITIAL
        var decision = AutoTdpEngine.decide(window, config, caps3Cluster, current, state)
        repeat(ticks - 1) {
            state = decision.controllerState
            decision = AutoTdpEngine.decide(window, config, caps3Cluster, current, state)
        }
        return decision
    }

    @Test
    fun `empty window maps to NO_TELEMETRY`() {
        val decision = AutoTdpEngine.decide(
            window = emptyList(),
            config = balancedConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.NO_TELEMETRY)
    }

    @Test
    fun `saturated true-load core maps to CPU_BOUND_RELAXING`() {
        // GPU well below band (would tighten) but a TRUE-load saturated prime core
        // must take precedence and RELAX.
        val window = List(4) {
            telemetry(
                gpuLoad = 30,
                coreLoads = List(8) { i -> if (i == 7) 92 else 50 },
                source = CpuLoadReading.Source.ROOT_PROC_STAT,
            )
        }
        val decision = run(
            window = window,
            current = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = 1_171_000),
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.CPU_BOUND_RELAXING)
    }

    @Test
    fun `gpu below band maps to GPU_BOUND_CAPPING after confirm`() {
        // GPU at 30% is below the BALANCED_SMART band (63-85) → tighten → cap.
        val window = List(4) {
            telemetry(gpuLoad = 30, coreLoads = List(8) { i -> if (i == 7) 20 else 15 })
        }
        val decision = run(window = window, current = TdpState.STOCK)
        assertThat(decision.holdReason).isEqualTo(HoldReason.GPU_BOUND_CAPPING)
    }

    @Test
    fun `gpu inside band with known load maps to IDLE_HOLDING`() {
        // GPU at 74% is inside BALANCED_SMART (63-85) → hold; load is real → IDLE.
        val window = List(4) {
            telemetry(
                gpuLoad = 74,
                coreLoads = List(8) { 30 },
                source = CpuLoadReading.Source.DIRECT_PROC_STAT,
            )
        }
        val decision = run(window = window, current = TdpState.STOCK)
        assertThat(decision.holdReason).isEqualTo(HoldReason.IDLE_HOLDING)
    }

    @Test
    fun `load-blind window inside band maps to LOAD_BLIND_HOLDING not IDLE`() {
        // GPU inside band → hold; but CPU dimension is blind → must say LOAD_BLIND.
        val window = List(4) {
            telemetry(
                gpuLoad = 74,
                coreLoads = emptyList(),
                source = CpuLoadReading.Source.UNAVAILABLE,
            )
        }
        val decision = run(window = window, current = TdpState.STOCK)
        assertThat(decision.holdReason).isEqualTo(HoldReason.LOAD_BLIND_HOLDING)
        assertThat(decision.holdReason).isNotEqualTo(HoldReason.IDLE_HOLDING)
    }
}
