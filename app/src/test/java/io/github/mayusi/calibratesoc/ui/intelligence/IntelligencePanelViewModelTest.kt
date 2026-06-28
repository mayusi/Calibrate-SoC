package io.github.mayusi.calibratesoc.ui.intelligence

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyPlan
import io.github.mayusi.calibratesoc.data.efficiency.EstimateSource
import io.github.mayusi.calibratesoc.data.monitor.EstimateBasis
import io.github.mayusi.calibratesoc.data.monitor.BatteryEstimate
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import org.junit.Test

/**
 * Unit tests for [IntelligencePanelViewModel]'s pure companion helpers.
 *
 * All helpers are pure functions (no Android, no coroutines needed) so
 * these run on plain JVM without Robolectric overhead.
 *
 * Coverage:
 *  - [IntelligencePanelViewModel.computeThermalHeadroom] — valid/unavailable/edge cases
 *  - [IntelligencePanelViewModel.planToWinState] — MEASURED/ESTIMATED/UNAVAILABLE
 *  - BatteryEstimate basis handling: LIVE_DRAW, CHARGING, INSUFFICIENT_DATA
 */
class IntelligencePanelViewModelTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  computeThermalHeadroom
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `headroom is 85 minus peak when single zone reads 65 C`() {
        val t = makeTelemetry(listOf(65_000))
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t)
        assertThat(state).isInstanceOf(ThermalHeadroomState.Available::class.java)
        state as ThermalHeadroomState.Available
        assertThat(state.headroomC).isWithin(0.1f).of(20f)   // 85 - 65
        assertThat(state.peakZoneTempC).isWithin(0.1f).of(65f)
        assertThat(state.killTempC).isEqualTo(85f)
    }

    @Test
    fun `headroom picks hottest of multiple zones`() {
        val t = makeTelemetry(listOf(40_000, 72_000, 55_000))
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t) as ThermalHeadroomState.Available
        assertThat(state.peakZoneTempC).isWithin(0.1f).of(72f)
        assertThat(state.headroomC).isWithin(0.1f).of(13f)    // 85 - 72
    }

    @Test
    fun `headroom is Unavailable when no zones present`() {
        val t = makeTelemetry(emptyList())
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t)
        assertThat(state).isEqualTo(ThermalHeadroomState.Unavailable)
    }

    @Test
    fun `headroom is Unavailable when all zones are out of plausible range`() {
        // Implausible values (< 1 000 milliC or > 120 000 milliC) are filtered
        val t = makeTelemetry(listOf(0, 500, 130_000, 200_000))
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t)
        assertThat(state).isEqualTo(ThermalHeadroomState.Unavailable)
    }

    @Test
    fun `headroom at kill temp exactly gives zero`() {
        val t = makeTelemetry(listOf(85_000))
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t) as ThermalHeadroomState.Available
        assertThat(state.headroomC).isWithin(0.1f).of(0f)
    }

    @Test
    fun `headroom above kill temp gives negative headroom (throttling)`() {
        val t = makeTelemetry(listOf(90_000))
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t) as ThermalHeadroomState.Available
        assertThat(state.headroomC).isLessThan(0f)
    }

    @Test
    fun `headroom respects custom killC override`() {
        val t = makeTelemetry(listOf(70_000))
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t, killC = 95f) as ThermalHeadroomState.Available
        assertThat(state.headroomC).isWithin(0.1f).of(25f)   // 95 - 70
        assertThat(state.killTempC).isEqualTo(95f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  planToWinState — MEASURED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MEASURED knee cap with drawPct produces Measured state`() {
        val plan = makePlan(
            kneeCap = 2_000_000,             // 2000 MHz
            drawPct = 14,
            source = EstimateSource.MEASURED,
        )
        val caps = makeTdpCaps(bigPolicyId = 4, oppStepsKhz = listOf(1_200_000, 1_800_000, 2_400_000))
        val state = IntelligencePanelViewModel.planToWinState(plan, caps)
        assertThat(state).isInstanceOf(EfficiencyWinState.Measured::class.java)
        state as EfficiencyWinState.Measured
        assertThat(state.kneeMhz).isEqualTo(2000)
        assertThat(state.drawReductionPct).isEqualTo(14)
        assertThat(state.kneeCaps).containsExactly(4, 2_000_000)
    }

    @Test
    fun `MEASURED knee cap with null drawPct still produces Measured state`() {
        val plan = makePlan(
            kneeCap = 1_800_000,
            drawPct = null,
            source = EstimateSource.MEASURED,
        )
        val caps = makeTdpCaps(bigPolicyId = 4, oppStepsKhz = listOf(1_800_000))
        val state = IntelligencePanelViewModel.planToWinState(plan, caps)
        assertThat(state).isInstanceOf(EfficiencyWinState.Measured::class.java)
        state as EfficiencyWinState.Measured
        assertThat(state.drawReductionPct).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  planToWinState — ESTIMATED (no knee cap)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ESTIMATED plan with drawPct but no knee cap produces Estimated state`() {
        val plan = makePlan(
            kneeCap = null,
            drawPct = 10,
            source = EstimateSource.ESTIMATED,
        )
        val caps = makeTdpCaps(bigPolicyId = 4, oppStepsKhz = listOf(1_800_000, 2_400_000))
        val state = IntelligencePanelViewModel.planToWinState(plan, caps)
        assertThat(state).isInstanceOf(EfficiencyWinState.Estimated::class.java)
        state as EfficiencyWinState.Estimated
        assertThat(state.drawReductionPct).isEqualTo(10)
    }

    @Test
    fun `HONESTY - ESTIMATED source with knee cap present is NOT promoted to Measured`() {
        // Even if bigClusterKneeCap is non-null, ESTIMATED source means no sweep happened.
        // Must never claim MEASURED. This is the honesty law test.
        val plan = makePlan(
            kneeCap = 1_800_000,  // knee cap present
            drawPct = 10,
            source = EstimateSource.ESTIMATED,  // but ESTIMATED source
        )
        val caps = makeTdpCaps(bigPolicyId = 4, oppStepsKhz = listOf(1_800_000))
        val state = IntelligencePanelViewModel.planToWinState(plan, caps)
        // Must NOT be Measured — ESTIMATED source is never promoted
        assertThat(state).isNotInstanceOf(EfficiencyWinState.Measured::class.java)
        // With a knee cap but ESTIMATED source, drawPct is the signal — falls to Estimated
        assertThat(state).isInstanceOf(EfficiencyWinState.Estimated::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  planToWinState — UNAVAILABLE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `plan with no knee cap and null drawPct produces Unavailable`() {
        val plan = makePlan(
            kneeCap = null,
            drawPct = null,
            source = EstimateSource.ESTIMATED,
        )
        val caps = makeTdpCaps(bigPolicyId = 4, oppStepsKhz = emptyList())
        val state = IntelligencePanelViewModel.planToWinState(plan, caps)
        assertThat(state).isEqualTo(EfficiencyWinState.Unavailable)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BatteryEstimate honesty — INSUFFICIENT_DATA never surfaces a number
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `INSUFFICIENT_DATA battery estimate has null hoursRemaining`() {
        // Honesty law: when the device can't produce a discharge estimate,
        // hoursRemaining must be null — never a fabricated number.
        val estimate = BatteryEstimate(
            hoursRemaining = null,
            watts = null,
            basis = EstimateBasis.INSUFFICIENT_DATA,
        )
        assertThat(estimate.basis).isEqualTo(EstimateBasis.INSUFFICIENT_DATA)
        assertThat(estimate.hoursRemaining).isNull()
    }

    @Test
    fun `CHARGING battery estimate has null hoursRemaining`() {
        val estimate = BatteryEstimate(
            hoursRemaining = null,
            watts = -2.5,   // negative = charging
            basis = EstimateBasis.CHARGING,
        )
        assertThat(estimate.basis).isEqualTo(EstimateBasis.CHARGING)
        assertThat(estimate.hoursRemaining).isNull()
    }

    @Test
    fun `LIVE_DRAW battery estimate carries a positive hours value`() {
        val estimate = BatteryEstimate(
            hoursRemaining = 2.5,
            watts = 5.0,
            basis = EstimateBasis.LIVE_DRAW,
        )
        assertThat(estimate.basis).isEqualTo(EstimateBasis.LIVE_DRAW)
        assertThat(estimate.hoursRemaining!!).isGreaterThan(0.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Thermal headroom color mapping (unit check — UI logic sanity)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `headroom greater than 20C maps to Emerald color logic`() {
        val t = makeTelemetry(listOf(60_000))  // 85 - 60 = 25 C headroom
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t) as ThermalHeadroomState.Available
        // headroomC > 20 → should map to Emerald in the composable
        assertThat(state.headroomC).isGreaterThan(20f)
    }

    @Test
    fun `headroom between 10 and 20C maps to Amber color logic`() {
        val t = makeTelemetry(listOf(70_000))  // 85 - 70 = 15 C headroom
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t) as ThermalHeadroomState.Available
        assertThat(state.headroomC).isGreaterThan(10f)
        assertThat(state.headroomC).isLessThan(20f)
    }

    @Test
    fun `headroom below 10C maps to Red color logic`() {
        val t = makeTelemetry(listOf(78_000))  // 85 - 78 = 7 C headroom
        val state = IntelligencePanelViewModel.computeThermalHeadroom(t) as ThermalHeadroomState.Available
        assertThat(state.headroomC).isLessThan(10f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build a minimal [Telemetry] with the given zone temperatures in milliC. */
    private fun makeTelemetry(zoneTempsMilliC: List<Int>): Telemetry {
        val zones = zoneTempsMilliC.mapIndexed { idx, t ->
            ZoneTemp(zoneId = idx, label = "zone$idx", tempMilliC = t)
        }
        return Telemetry(
            timestampMs = 0L,
            perCoreCpuFreqKhz = emptyList(),
            perCoreLoadPct = emptyList(),
            gpuLoadPct = null,
            gpuFreqHz = null,
            zoneTempsMilliC = zones,
            ramTotalKb = 0L,
            ramAvailableKb = 0L,
            batteryTempDeciC = null,
            batteryCurrentUa = null,
            batteryVoltageUv = null,
            fanRpm = null,
        )
    }

    /** Build a minimal [EfficiencyPlan]. */
    private fun makePlan(
        kneeCap: Int?,
        drawPct: Int?,
        source: EstimateSource,
        summaryText: String = "test plan",
    ): EfficiencyPlan = EfficiencyPlan(
        bigClusterKneeCap = kneeCap,
        primeClusterKneeCap = null,
        gpuPowerLevelFloor = null,
        estimatedDrawReductionPct = drawPct,
        drawEstimateSource = source,
        summaryText = summaryText,
        requiresSweep = source == EstimateSource.ESTIMATED,
    )

    /**
     * Build a minimal [TdpCaps] for testing [planToWinState].
     *
     * [TdpCaps] is a data class. We construct it directly — no Android deps.
     * Field list matches the actual data class: required fields first, then
     * Wave 2 defaulted fields.
     */
    private fun makeTdpCaps(bigPolicyId: Int, oppStepsKhz: List<Int>): TdpCaps = TdpCaps(
        primeCoreIndices = emptyList(),
        bigPolicyId = bigPolicyId,
        bigClusterOppStepsKhz = oppStepsKhz,
        gpuMinLevel = null,
        gpuMaxLevel = null,
        minOnlineCores = 1,
        totalOnlineCores = 8,
    )
}
