package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Verifies [AutoTdpEngine.decide] sets the correct [HoldReason] in EVERY return
 * branch. The mapping is part of the data contract two UI agents build against,
 * so each branch is asserted explicitly.
 *
 * Topology mirrors AutoTdpEngineTest (SD8Gen2-style 3-cluster):
 *   prime core = 7, big policy = 4.
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

    private val efficiencyConfig = AutoTdpProfileConfig(AutoTdpProfile.EFFICIENCY)

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

    @Test
    fun `empty window maps to NO_TELEMETRY`() {
        val decision = AutoTdpEngine.decide(
            window = emptyList(),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.NO_TELEMETRY)
    }

    @Test
    fun `saturated core maps to CPU_BOUND_RELAXING`() {
        val window = List(4) {
            telemetry(gpuLoad = 30, coreLoads = List(8) { i -> if (i == 7) 92 else 50 })
        }
        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = 1_171_000),
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.CPU_BOUND_RELAXING)
    }

    @Test
    fun `confirmed gpu-bound window maps to GPU_BOUND_CAPPING`() {
        val window = List(4) {
            telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
        }
        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.GPU_BOUND_CAPPING)
    }

    @Test
    fun `battery-target proportional cap maps to BATTERY_TARGET_HOLDING`() {
        // Mixed/idle workload (neither saturated nor GPU-bound) under BATTERY_TARGET
        // with a budget → proportional cap branch.
        val window = List(4) {
            telemetry(gpuLoad = 40, coreLoads = List(8) { 30 })
        }
        val decision = AutoTdpEngine.decide(
            window = window,
            config = AutoTdpProfileConfig(AutoTdpProfile.BATTERY_TARGET, targetMilliWatts = 1_500),
            caps = caps3Cluster,
            current = TdpState.STOCK, // no cap yet → budget cap differs → branch fires
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.BATTERY_TARGET_HOLDING)
    }

    @Test
    fun `idle window with known load maps to IDLE_HOLDING`() {
        val window = List(4) {
            telemetry(
                gpuLoad = 40,
                coreLoads = List(8) { 30 },
                source = CpuLoadReading.Source.DIRECT_PROC_STAT,
            )
        }
        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.IDLE_HOLDING)
    }

    @Test
    fun `load-blind window maps to LOAD_BLIND_HOLDING not IDLE`() {
        // UNAVAILABLE source + empty perCoreLoadPct = blind. The engine must NOT
        // claim idle — that would be a lie. It must report LOAD_BLIND_HOLDING.
        val window = List(4) {
            telemetry(
                gpuLoad = 40,
                coreLoads = emptyList(),
                source = CpuLoadReading.Source.UNAVAILABLE,
            )
        }
        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.holdReason).isEqualTo(HoldReason.LOAD_BLIND_HOLDING)
        // Honesty cross-check: never IDLE when blind.
        assertThat(decision.holdReason).isNotEqualTo(HoldReason.IDLE_HOLDING)
    }
}
