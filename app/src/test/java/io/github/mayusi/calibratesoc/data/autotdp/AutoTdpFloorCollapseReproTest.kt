package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * FORENSIC REPRODUCTION — DEFECT A: the Bayonetta/GameNative cap→floor collapse.
 *
 * Live signature on the AYN Odin 3 little cluster (policy0):
 *   stock max = 3_532_800 kHz (3.53 GHz), floor OPP = 384_000 kHz.
 *   After the collapse: scaling_max_freq == scaling_min_freq == scaling_cur_freq
 *   == 384_000. The governor drove the CAP all the way to the BOTTOM OPP and
 *   dragged the min-floor down with it (MM-1 lockstep), mid-heavy-game.
 *
 * Root-cause chain these tests pin down:
 *   1. The Wine wrapper foreground package "app.gamenative.iic" is NOT in
 *      [io.github.mayusi.calibratesoc.data.gameaware.KnownGames] (only
 *      "com.gamenative" is, with no "app.gamenative" prefix entry), so the
 *      classifier never anchors it to a game.
 *   2. On the Odin, true /proc/stat per-core load is SELinux-denied → the load
 *      source is FREQ_PROXY (never ROOT/DIRECT proc_stat) → hasTrueLoadData=false.
 *   3. The GPU busy% node read can go null (SELinux/transient); the engine coerces
 *      `gpuLoadPct ?: 0`, so a null GPU read becomes 0% busy.
 *   4. classify(unknown-pkg, gpu≈0) → IDLE/UNKNOWN. With no game anchor the
 *      paused-game guard can't fire. Under any band, gpu≈0 < band.low → TIGHTEN.
 *   5. bandTighten rides the CAP lever down OPP-by-OPP. [stepCapDown]'s floor is
 *      index 0 (the bottom OPP) for every non-hard-ceiling goal — there is NO
 *      "never below X% of cpuinfo_max" clamp. The cap walks to 384_000.
 *   6. enforceInvariants MM-1 then drags scaling_min_freq down in lockstep to keep
 *      floor < cap, producing cur=min=max=384_000.
 *
 * These tests are RED against the current engine. They are the executable spec for
 * the missing guardrails (hard cap floor + classifier-uncertain hold). NO production
 * code is modified by this file.
 */
class AutoTdpFloorCollapseReproTest {

    // ── AYN Odin 3 little-cluster envelope (policy0), real OPP table ────────────
    // The little cluster is the one that collapsed; its bottom OPP is 384 MHz and
    // its top is 3.53 GHz. (The exact mids don't matter to the repro; a realistic
    // monotonic ladder is enough for the cap-walk.)
    private val ODIN_LITTLE_OPP_KHZ = listOf(
        384_000, 614_400, 864_000, 1_363_200, 1_708_800,
        2_016_000, 2_419_200, 2_995_200, 3_532_800,
    )

    private val odinCaps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 0,
        bigClusterOppStepsKhz = ODIN_LITTLE_OPP_KHZ,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    /** The IIC build's foreground package — present in ZERO KnownGames entries. */
    private val WINE_WRAPPER_PKG = "app.gamenative.iic"

    /**
     * A telemetry sample mirroring the live failure: a Wine wrapper foreground,
     * FREQ_PROXY load (true /proc/stat denied on the Odin), and a NULL GPU read.
     */
    private fun deniedSample(
        gpuLoad: Int? = null,
        fgPkg: String? = WINE_WRAPPER_PKG,
        dieTempMilliC: Int? = 70_000, // warm but well under any soft target
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        // Proxy load looks moderate, but the SOURCE is FREQ_PROXY so it must never
        // originate a state change (FP-1). This is the only load signal on the Odin.
        perCoreLoadPct = List(8) { 55 },
        cpuLoadSource = CpuLoadReading.Source.FREQ_PROXY,
        gpuLoadPct = gpuLoad, // null on a denied/transient GPU-busy read
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", dieTempMilliC ?: 70_000)),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 320,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = fgPkg,
        gpuDieTempMilliC = dieTempMilliC,
        coolingDeviceMaxState = null,
        realFpsX10 = null,
        isRealFps = false,
    )

    /** Replay the engine like the daemon does: thread controllerState + apply target. */
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
                caps = odinCaps,
                current = current,
                controllerState = state,
                goalOverride = goal,
            )
            current = d.target
            state = d.controllerState
        }
        return current
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  DEFECT A — cap (and min-floor) collapse to the bottom OPP
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * THE BUG. AUTO goal + Wine wrapper foreground + NULL GPU read + FREQ_PROXY load.
     * Over a sustained session the cap must NOT walk to the bottom OPP (384 MHz).
     *
     * EXPECTED (after fix): the cap holds at or above a safe floor (e.g. ≥ ~40% of
     * cpuinfo_max ≈ 1.4 GHz), OR the engine refuses to tighten because the classifier
     * is uncertain and the only load signal is proxy.
     *
     * ACTUAL (current engine): the cap collapses to 384_000 — RED.
     */
    @Test
    fun `AUTO wine-wrapper null-gpu proxy-load must NOT collapse cap to bottom OPP`() {
        val final = replay(
            goal = GoalProfile.AUTO,
            sampleFor = { deniedSample(gpuLoad = null) },
            ticks = 60,
        )

        val bottom = ODIN_LITTLE_OPP_KHZ.first() // 384_000
        val cpuinfoMax = ODIN_LITTLE_OPP_KHZ.last() // 3_532_800
        val cap = final.bigClusterCapKhz

        // The catastrophic state: cap pinned to the absolute floor OPP.
        assertThat(cap).isNotEqualTo(bottom)

        // Stronger guardrail: a heavy/uncertain game session must never cap below a
        // safe fraction of cpuinfo_max. 40% ≈ 1_413_120 kHz. (Proposed HARD floor.)
        val safeFloor = (cpuinfoMax * 0.40).toInt()
        if (cap != null) {
            assertThat(cap).isAtLeast(safeFloor)
        }
    }

    /**
     * The cur=min=max=384 signature: when the cap collapses to the bottom OPP,
     * enforceInvariants MM-1 drags scaling_min_freq down in lockstep, so the cluster
     * is pinned at a single OPP and can't even boost. This asserts the min-floor is
     * NOT dragged to the bottom — RED today because the cap reaches it.
     */
    @Test
    fun `min-floor must not be dragged to the bottom OPP in lockstep with a collapsed cap`() {
        val final = replay(
            goal = GoalProfile.BALANCED_SMART,
            sampleFor = { deniedSample(gpuLoad = null) },
            ticks = 60,
        )
        val bottom = ODIN_LITTLE_OPP_KHZ.first()
        // If the cap collapsed to bottom, MM-1 forces min to null/bottom (can't be
        // strictly below index 0). Either way the cluster is pinned. Assert the cap
        // never reached bottom so min was never dragged.
        assertThat(final.bigClusterCapKhz).isNotEqualTo(bottom)
    }

    /**
     * Even with a KNOWN heavy game (com.gamenative is in the table) but a NULL GPU
     * read + proxy-only load, a HEAVY_GAME classification must never floor the CPU.
     * This isolates the cap-floor defect from the classifier-miss defect: the missing
     * hard floor sinks the cap regardless of classification once gpu reads 0.
     *
     * RED today: gpu≈0 < every band low, so HEAVY_GAME still tightens to the bottom.
     */
    @Test
    fun `known heavy game with null gpu must not floor the cap`() {
        val final = replay(
            goal = GoalProfile.MAX_FPS, // loosen-biased, the LEAST likely to floor
            sampleFor = { deniedSample(gpuLoad = null, fgPkg = "com.gamenative") },
            ticks = 60,
        )
        val bottom = ODIN_LITTLE_OPP_KHZ.first()
        assertThat(final.bigClusterCapKhz).isNotEqualTo(bottom)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CLASSIFIER MISREAD — the Wine wrapper is not seen as a game
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * GUARDRAIL 4 (was defect-A trigger #1). The IIC Wine wrapper "app.gamenative.iic"
     * is now recognised via [KnownGames.PREFIX_TABLE]'s "app.gamenative" entry, so even
     * with a null/low GPU read the classifier anchors the foreground to at least
     * LIGHT_GAME — and AUTO maps that to BALANCED_SMART, NOT BATTERY_SAVER's aggressive
     * power-cap. This inverts the original (RED) record of the misclassification.
     */
    @Test
    fun `wine wrapper with null gpu now anchors to at least LIGHT_GAME and AUTO avoids BATTERY_SAVER`() {
        var classified = ContextClassifier.classify(
            window = listOf(deniedSample(gpuLoad = 0)),
            smoothedGpuPct = 0,
            prior = ClassifierState.INITIAL,
        )
        var state = classified.state
        // gpu≈0 (null read coerced to 0 upstream) + the recognised wrapper → LIGHT_GAME.
        repeat(9) {
            classified = ContextClassifier.classify(
                window = listOf(deniedSample(gpuLoad = 0)),
                smoothedGpuPct = 0,
                prior = state,
            )
            state = classified.state
        }
        // The committed belief is at least LIGHT_GAME (the GUARDRAIL-4 foreground floor).
        assertThat(state.stable).isAnyOf(WorkloadContext.LIGHT_GAME, WorkloadContext.HEAVY_GAME)
        // The context AUTO acts on this tick is floored to at least LIGHT_GAME too.
        assertThat(classified.context).isAnyOf(WorkloadContext.LIGHT_GAME, WorkloadContext.HEAVY_GAME)
        // AUTO → LIGHT_GAME maps to BALANCED_SMART (a smooth, non-flooring goal), and
        // NEVER to BATTERY_SAVER (the strongest-tighten + hard-ceiling goal).
        val goal = ContextClassifier.goalFor(classified.context, ThermalTrend.COOL)
        assertThat(goal).isNotEqualTo(GoalProfile.BATTERY_SAVER)
        assertThat(goal).isEqualTo(GoalProfile.BALANCED_SMART)
    }
}
