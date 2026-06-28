package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import org.junit.Test

/**
 * UNIT 4 — RICHER GOAL MODES (engine goal-resolution region).
 *
 * Deterministic replay of synthetic [Telemetry] against the PURE engine. Covers ONLY the
 * goal-resolution region's additions (the band controller itself is exercised by the
 * Unit 1/2/3 suites and is UNCHANGED here):
 *
 *  - the three new objective enum cases are valid CONCRETE goals (band ≥ 22 wide, reuse
 *    curated edges) the band controller runs directly;
 *  - charge-aware AUTO: charging → MAX_FPS; >30% unplugged → classifier path; 15–30% →
 *    BATTERY_SAVER; <15% → TARGET_RUNTIME; unknown level → classifier path (no new guess);
 *  - TARGET_FPS_FLOOR honestly DEGRADES to BALANCED_SMART (with the fpsFloorDegraded flag)
 *    when no real FPS source is present, and stays itself when a real source exists;
 *  - the existing 6 modes are byte-identical with the new params at their defaults (null).
 */
class AutoTdpUnit4Test {

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

    /** A real-load sample. [gpu] busy%, [dieC] die temp, optional real FPS. */
    private fun tel(
        gpu: Int,
        dieC: Int = 50,
        realFpsX10: Int? = null,
        isReal: Boolean = false,
        fgPkg: String? = null,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 30 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT,
        gpuLoadPct = gpu,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = emptyList(),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = fgPkg,
        gpuDieTempMilliC = dieC * 1000,
        coolingDeviceMaxState = 0,
        realFpsX10 = realFpsX10,
        isRealFps = isReal,
    )

    private fun decide(
        goal: GoalProfile,
        window: List<Telemetry>,
        batteryPct: Int? = null,
        charging: Boolean? = null,
    ) = AutoTdpEngine.decide(
        window = window,
        config = AutoTdpProfileConfig.forGoal(goal),
        caps = caps,
        current = TdpState.STOCK,
        controllerState = ControllerState.INITIAL,
        goalOverride = goal,
        batteryPct = batteryPct,
        charging = charging,
    )

    // ════════════════════════════════════════════════════════════════════════════
    //  Enum sanity — the three new modes are valid concrete goals
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `new objective modes reuse curated bands at least 22 points wide`() {
        assertThat(GoalProfile.TARGET_TEMP_CEILING.bandWidthPct).isAtLeast(22)
        assertThat(GoalProfile.TARGET_FPS_FLOOR.bandWidthPct).isAtLeast(22)
        assertThat(GoalProfile.TARGET_RUNTIME.bandWidthPct).isAtLeast(22)
        // TARGET_TEMP_CEILING reuses COOL_QUIET's band/soft-temp; the FPS/RUNTIME modes
        // reuse BATTERY_SAVER's band. None carries a hard watts ceiling.
        assertThat(GoalProfile.TARGET_TEMP_CEILING.gpuBandLowPct)
            .isEqualTo(GoalProfile.COOL_QUIET.gpuBandLowPct)
        assertThat(GoalProfile.TARGET_FPS_FLOOR.gpuBandLowPct)
            .isEqualTo(GoalProfile.BATTERY_SAVER.gpuBandLowPct)
        assertThat(GoalProfile.TARGET_TEMP_CEILING.hasHardPowerCeiling).isFalse()
        assertThat(GoalProfile.TARGET_FPS_FLOOR.hasHardPowerCeiling).isFalse()
        assertThat(GoalProfile.TARGET_RUNTIME.hasHardPowerCeiling).isFalse()
    }

    @Test
    fun `default objective fields are null for the original six modes (zero behaviour change)`() {
        listOf(
            GoalProfile.AUTO, GoalProfile.BALANCED_SMART, GoalProfile.MAX_FPS,
            GoalProfile.COOL_QUIET, GoalProfile.BATTERY_SAVER,
        ).forEach { g ->
            assertThat(g.defaultFpsFloor).isNull()
            assertThat(g.defaultTempCeilingC).isNull()
            assertThat(g.defaultTargetRuntimeHours).isNull()
        }
    }

    @Test
    fun `TARGET_TEMP_CEILING tightens near its soft temp via the committed fast path`() {
        // die at 79 (≥ COOL_QUIET soft 80 - 1) with GPU below the band → the committed
        // fast-tighten urgency lands a cap step in one tick (no 2-tick confirm).
        val d = decide(GoalProfile.TARGET_TEMP_CEILING, listOf(tel(gpu = 5, dieC = 79)))
        assertThat(d.resolvedGoal).isEqualTo(GoalProfile.TARGET_TEMP_CEILING)
        assertThat(d.target.bigClusterCapKhz).isNotNull()
        assertThat(d.target.bigClusterCapKhz!!).isLessThan(bigOpps.last())
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_FPS_FLOOR — honest degrade
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `TARGET_FPS_FLOOR degrades to BALANCED_SMART when no real fps source`() {
        val d = decide(GoalProfile.TARGET_FPS_FLOOR, listOf(tel(gpu = 70, isReal = false)))
        assertThat(d.resolvedGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(d.fpsFloorDegraded).isTrue()
    }

    @Test
    fun `TARGET_FPS_FLOOR stays itself when a real fps source is present`() {
        val d = decide(
            GoalProfile.TARGET_FPS_FLOOR,
            listOf(tel(gpu = 70, realFpsX10 = 595, isReal = true)),
        )
        assertThat(d.resolvedGoal).isEqualTo(GoalProfile.TARGET_FPS_FLOOR)
        assertThat(d.fpsFloorDegraded).isFalse()
    }

    @Test
    fun `non-fps modes never set the fps-degrade flag`() {
        listOf(GoalProfile.BALANCED_SMART, GoalProfile.COOL_QUIET, GoalProfile.TARGET_RUNTIME)
            .forEach { g ->
                val d = decide(g, listOf(tel(gpu = 60, isReal = false)))
                assertThat(d.fpsFloorDegraded).isFalse()
            }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Charge-aware AUTO
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `AUTO resolves to MAX_FPS while charging at any level`() {
        val d = decide(GoalProfile.AUTO, listOf(tel(gpu = 70)), batteryPct = 12, charging = true)
        assertThat(d.resolvedGoal).isEqualTo(GoalProfile.MAX_FPS)
    }

    @Test
    fun `AUTO resolves to BATTERY_SAVER between 15 and 30 percent unplugged`() {
        val d = decide(GoalProfile.AUTO, listOf(tel(gpu = 70)), batteryPct = 22, charging = false)
        assertThat(d.resolvedGoal).isEqualTo(GoalProfile.BATTERY_SAVER)
    }

    @Test
    fun `AUTO resolves to TARGET_RUNTIME below 15 percent unplugged`() {
        val d = decide(GoalProfile.AUTO, listOf(tel(gpu = 70)), batteryPct = 9, charging = false)
        assertThat(d.resolvedGoal).isEqualTo(GoalProfile.TARGET_RUNTIME)
    }

    @Test
    fun `AUTO above 30 percent unplugged uses the classifier path unchanged`() {
        // No game anchor + high GPU → classifier returns BALANCED_SMART (the safe default
        // for UNKNOWN). The point is it goes through goalFor, not a charge override.
        val d = decide(GoalProfile.AUTO, listOf(tel(gpu = 70)), batteryPct = 80, charging = false)
        assertThat(d.resolvedGoal).isAnyOf(
            GoalProfile.BALANCED_SMART, GoalProfile.COOL_QUIET, GoalProfile.BATTERY_SAVER,
        )
        // It must NOT be one of the charge-gate-only resolutions when level is healthy and
        // a real classifier result exists — specifically never MAX_FPS (that's charging-only).
        assertThat(d.resolvedGoal).isNotEqualTo(GoalProfile.MAX_FPS)
    }

    @Test
    fun `AUTO with unknown battery falls through to the classifier path`() {
        val charging = decide(GoalProfile.AUTO, listOf(tel(gpu = 70)), batteryPct = null, charging = null)
        // Unknown level + unknown charging → classifier path (same as before the gate).
        assertThat(charging.resolvedGoal).isNotEqualTo(GoalProfile.TARGET_RUNTIME)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Back-compat — the existing 6 modes are byte-identical with default params
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `default-null UNIT 4 params produce identical decisions for an existing mode`() {
        val window = listOf(tel(gpu = 40, dieC = 55))
        val withoutParams = AutoTdpEngine.decide(
            window = window,
            config = AutoTdpProfileConfig.forGoal(GoalProfile.BALANCED_SMART),
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
            goalOverride = GoalProfile.BALANCED_SMART,
        )
        val withDefaultParams = AutoTdpEngine.decide(
            window = window,
            config = AutoTdpProfileConfig.forGoal(GoalProfile.BALANCED_SMART),
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
            goalOverride = GoalProfile.BALANCED_SMART,
            goalParams = null,
            batteryPct = null,
            charging = null,
        )
        assertThat(withDefaultParams.target).isEqualTo(withoutParams.target)
        assertThat(withDefaultParams.resolvedGoal).isEqualTo(withoutParams.resolvedGoal)
        assertThat(withDefaultParams.reason).isEqualTo(withoutParams.reason)
        assertThat(withDefaultParams.fpsFloorDegraded).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  GoalParams value object — snap/clamp + defaults
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `GoalParams snaps fps floor to an allowed step and clamps temp and hours`() {
        assertThat(GoalParams.snapFpsFloor(58)).isEqualTo(60)
        assertThat(GoalParams.snapFpsFloor(31)).isEqualTo(30)
        assertThat(GoalParams.clampTempCeiling(120)).isEqualTo(GoalParams.TEMP_CEILING_MAX_C)
        assertThat(GoalParams.clampTempCeiling(40)).isEqualTo(GoalParams.TEMP_CEILING_MIN_C)
        assertThat(GoalParams.clampRuntimeHours(99f)).isEqualTo(GoalParams.RUNTIME_HOURS_MAX)
        assertThat(GoalParams.clampRuntimeHours(0.1f)).isEqualTo(GoalParams.RUNTIME_HOURS_MIN)
    }

    @Test
    fun `GoalParams defaults match the documented setpoints`() {
        val p = GoalParams.DEFAULT
        assertThat(p.fpsFloor).isEqualTo(60)
        assertThat(p.tempCeilingC).isEqualTo(80)
        assertThat(p.targetRuntimeHours).isEqualTo(3f)
    }
}
