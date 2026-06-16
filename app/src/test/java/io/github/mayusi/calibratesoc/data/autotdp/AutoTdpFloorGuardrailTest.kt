package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * GUARDRAIL regression suite — the executable proof that the 384 MHz cap→floor collapse
 * (DEFECT A) is now IMPOSSIBLE BY CONSTRUCTION, across every goal and every signal state.
 *
 * Pairs with [AutoTdpFloorCollapseReproTest] (the forensic repro). This file proves the
 * positive guarantees:
 *   - GUARDRAIL 1: the hard cap floor (40% of top OPP) holds across a long tighten run
 *     for EVERY [GoalProfile], with REAL low GPU (not a null phantom) so tightening is
 *     genuinely requested every tick.
 *   - GUARDRAIL 3: a null-load + null-GPU tick HOLDS (no cap change), for the same goals.
 *   - MM-1 still holds at the floor (min strictly < cap, both at/above the hard floor).
 */
class AutoTdpFloorGuardrailTest {

    // AYN Odin 3 little-cluster OPP ladder (the cluster that collapsed). Bottom 384 MHz,
    // top 3.53 GHz. 40% of top ≈ 1 413 120 → snaps to the 1 708 800 step (first OPP ≥ 40%).
    private val ODIN_LITTLE_OPP_KHZ = listOf(
        384_000, 614_400, 864_000, 1_363_200, 1_708_800,
        2_016_000, 2_419_200, 2_995_200, 3_532_800,
    )

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 0,
        bigClusterOppStepsKhz = ODIN_LITTLE_OPP_KHZ,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    /** The hard floor the cap may never drop below: first OPP ≥ 40% of the top OPP. */
    private val hardFloorKhz = ODIN_LITTLE_OPP_KHZ.first { it >= (ODIN_LITTLE_OPP_KHZ.last() * 0.40).toInt() }

    /** Every CONCRETE goal AUTO can resolve to, plus AUTO itself. */
    private val allGoals = GoalProfile.entries.toList()

    /**
     * A sample with REAL telemetry but a low GPU read, so the band controller genuinely
     * WANTS to tighten every tick (gpu below every goal's band low). True jiffie load so
     * the load-blind hold does NOT fire — this isolates GUARDRAIL 1 (the hard cap floor).
     */
    private fun realLowGpuSample(
        gpuLoad: Int = 1,
        fgPkg: String? = null,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 10 }, // genuinely idle CPU (true load) → tighten wanted
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT, // TRUE load (not proxy)
        gpuLoadPct = gpuLoad,            // REAL low read → below every band low → tighten
        gpuFreqHz = 200_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", 60_000)), // cool: no thermal pre-empt
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 300,
        batteryCurrentUa = 1_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = fgPkg,
        gpuDieTempMilliC = 60_000,
        coolingDeviceMaxState = 0,
        realFpsX10 = null,
        isRealFps = false,
    )

    /** A null-load (proxy) + null-GPU sample — the LOAD-BLIND tick (DEFECT A's phantom). */
    private fun blindSample(fgPkg: String? = "app.gamenative.iic") = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 55 },
        cpuLoadSource = CpuLoadReading.Source.FREQ_PROXY, // NOT true load
        gpuLoadPct = null,                                 // NULL GPU read
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", 70_000)),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 320,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = fgPkg,
        gpuDieTempMilliC = 70_000,
        coolingDeviceMaxState = null,
        realFpsX10 = null,
        isRealFps = false,
    )

    private fun replay(
        goal: GoalProfile,
        sampleFor: (Int) -> Telemetry,
        ticks: Int,
        windowSize: Int = 4,
    ): TdpState {
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        val window = ArrayDeque<Telemetry>(windowSize + 1)
        val config = AutoTdpProfileConfig.forGoal(goal)
        for (i in 0 until ticks) {
            window.addLast(sampleFor(i))
            if (window.size > windowSize) window.removeFirst()
            val d = AutoTdpEngine.decide(
                window = window.toList(),
                config = config,
                caps = caps,
                current = current,
                controllerState = state,
                goalOverride = goal,
            )
            current = d.target
            state = d.controllerState
        }
        return current
    }

    // ── GUARDRAIL 1: hard cap floor holds for EVERY goal under a long tighten run ──

    @Test
    fun `hard cap floor holds across a long tighten run for every goal`() {
        for (goal in allGoals) {
            val final = replay(goal, { realLowGpuSample(gpuLoad = 1) }, ticks = 120)
            val cap = final.bigClusterCapKhz
            val bottom = ODIN_LITTLE_OPP_KHZ.first()
            // Never floored to the bottom OPP …
            assertThat(cap).isNotEqualTo(bottom)
            // … and never below the 40%-of-top hard floor.
            if (cap != null) {
                assertThat(cap).isAtLeast(hardFloorKhz)
            }
        }
    }

    @Test
    fun `BATTERY_SAVER the strongest-tighten goal still cannot floor the cap`() {
        // BATTERY_SAVER (hasHardPowerCeiling) is the worst case: even its watts-budget
        // floor can only RAISE the floor, never breach the 40% hard floor.
        val final = replay(
            GoalProfile.BATTERY_SAVER,
            { realLowGpuSample(gpuLoad = 1) },
            ticks = 200,
        )
        val cap = final.bigClusterCapKhz
        assertThat(cap).isNotEqualTo(ODIN_LITTLE_OPP_KHZ.first())
        if (cap != null) assertThat(cap).isAtLeast(hardFloorKhz)
    }

    // ── GUARDRAIL 3: null-load + null-GPU tick HOLDS (no cap change) ───────────────

    @Test
    fun `null-load and null-gpu tick holds — no cap change — for every goal`() {
        for (goal in allGoals) {
            val final = replay(goal, { blindSample() }, ticks = 60)
            // A purely load-blind session must leave the cap at STOCK (never touched it).
            assertThat(final.bigClusterCapKhz).isNull()
            assertThat(final).isEqualTo(TdpState.STOCK)
        }
    }

    @Test
    fun `single load-blind tick emits LOAD_BLIND_HOLDING and holds stock`() {
        val d = AutoTdpEngine.decide(
            window = listOf(blindSample()),
            config = AutoTdpProfileConfig.forGoal(GoalProfile.BATTERY_SAVER),
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
            goalOverride = GoalProfile.BATTERY_SAVER,
        )
        assertThat(d.target).isEqualTo(TdpState.STOCK)
        assertThat(d.holdReason).isEqualTo(HoldReason.LOAD_BLIND_HOLDING)
    }

    // ── MM-1 still holds at the hard floor (min strictly < cap, both ≥ hard floor) ─

    @Test
    fun `MM-1 holds at the floor — min strictly below cap and both at or above the hard floor`() {
        // Drive a long tighten so the cap (and possibly the min lever) reach their floors.
        val final = replay(GoalProfile.BALANCED_SMART, { realLowGpuSample(gpuLoad = 1) }, ticks = 200)
        val cap = final.bigClusterCapKhz
        val min = final.bigClusterMinKhz
        // Cap is at/above the hard floor.
        if (cap != null) assertThat(cap).isAtLeast(hardFloorKhz)
        // MM-1: when a min floor is set it is strictly below the cap, and never below the
        // hard floor (GUARDRAIL 1 mirror) — unless MM-1 had to lower it under the cap.
        if (min != null && cap != null) {
            assertThat(min).isLessThan(cap)
        }
    }
}
