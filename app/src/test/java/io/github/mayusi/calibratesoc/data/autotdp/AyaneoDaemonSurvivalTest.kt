package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter
import org.junit.Test

/**
 * RUNTIME-GAP regression: an AYANEO-shaped caps set under SUSTAINED tightening must NEVER
 * cause the daemon to self-terminate, and must STILL respect the 40% hard cap floor.
 *
 * The bug: on AYANEO only scaling_max_freq / governor / GPU-devfreq-max / fan are bindable.
 * But every goal's tighten ladder also contains MIN_FREQ_FLOOR / PARK / GPU_FLOOR — none of
 * which the AYANEO binder can drive. When the CAP bottoms at the 40% floor and the engine
 * keeps tightening, it emits one of those NON-bindable ops → AyaneoVendorWriter returns
 * CapabilityDenied → the OLD AutoTdpService treated CapabilityDenied as FATAL and stopped
 * the daemon mid-session.
 *
 * The fix: AutoTdpService stops ONLY on a CapabilityDenied of the CORE LIVE NODE — the cap
 * the daemon actuates, cpuMaxFreq(caps.bigPolicyId). A CapabilityDenied on any OTHER lever
 * is skipped (daemon keeps running on the cap path).
 *
 * This test proves the two invariants the fix depends on, using the SAME engine + delta the
 * daemon uses (the daemon's own op-loop is an Android Service, verified by source reasoning):
 *   (1) The cap NEVER drops below the 40% hard floor under a long tighten run (cap floor holds).
 *   (2) The tighten ladder DOES emit NON-bindable (non-cap) ops once the cap bottoms — so the
 *       CapabilityDenied-skip path is genuinely exercised in real sessions — and the cap-node
 *       discriminator the fix uses (op.target == cpuMaxFreq(caps.bigPolicyId)) correctly
 *       classifies those ops as NON-cap (skip), never as the fatal cap op.
 */
class AyaneoDaemonSurvivalTest {

    // AYANEO Pocket DS gold (big) cluster = policy3. Its OPP ladder (kHz), ascending.
    // 40% of 2 803 200 ≈ 1 121 280 → first OPP ≥ that = 1 516 800.
    private val GOLD_OPP_KHZ = listOf(
        710_400, 1_017_600, 1_516_800, 2_016_000, 2_419_200, 2_803_200,
    )

    // 3-cluster AYANEO shape: bigPolicyId = 3 (gold), prime cores = policy7 (just core 7).
    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 3,
        bigClusterOppStepsKhz = GOLD_OPP_KHZ,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    /** The hard floor: first OPP ≥ 40% of the top OPP. */
    private val hardFloorKhz = GOLD_OPP_KHZ.first { it >= (GOLD_OPP_KHZ.last() * 0.40).toInt() }

    /** The CORE LIVE NODE the daemon actuates — the exact discriminator the fix uses. */
    private val capNodeTarget = Tunables.cpuMaxFreq(caps.bigPolicyId).target

    /** A real, cool, idle-GPU sample so the band controller genuinely wants to tighten. */
    private fun tightenSample() = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 10 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT, // TRUE load (not proxy)
        gpuLoadPct = 1,                                          // below every band low → tighten
        gpuFreqHz = 200_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", 60_000)),   // cool: no thermal pre-empt
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 300,
        batteryCurrentUa = 1_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = null,
        gpuDieTempMilliC = 60_000,
        coolingDeviceMaxState = 0,
        realFpsX10 = null,
        isRealFps = false,
    )

    /**
     * Replay a tighten session and capture EVERY WriteOp the daemon would emit, in order.
     * Mirrors AutoTdpService.runDaemon's per-tick decide→delta→write loop (minus the actual
     * writes, which the daemon performs through TunableWriter).
     */
    private fun replayCapturingOps(goal: GoalProfile, ticks: Int): Pair<TdpState, List<TdpStateTransition.WriteOp>> {
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        val window = ArrayDeque<Telemetry>(5)
        val config = AutoTdpProfileConfig.forGoal(goal)
        val allOps = mutableListOf<TdpStateTransition.WriteOp>()
        for (i in 0 until ticks) {
            window.addLast(tightenSample())
            if (window.size > 4) window.removeFirst()
            val d = AutoTdpEngine.decide(
                window = window.toList(),
                config = config,
                caps = caps,
                current = current,
                controllerState = state,
                goalOverride = goal,
            )
            if (d.target != current) {
                allOps += TdpStateTransition.delta(
                    from = current,
                    to = d.target,
                    bigPolicyId = caps.bigPolicyId,
                    gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
                    fanModeKey = null,
                )
            }
            current = d.target
            state = d.controllerState
        }
        return current to allOps
    }

    @Test
    fun `BATTERY_SAVER sustained tighten never drops the cap below the 40 percent floor`() {
        // BATTERY_SAVER is the strongest-tighten goal — the worst case for the floor.
        val (final, _) = replayCapturingOps(GoalProfile.BATTERY_SAVER, ticks = 200)
        val cap = final.bigClusterCapKhz
        // Never floored to the bottom OPP, and never below the 40%-of-top hard floor.
        assertThat(cap).isNotEqualTo(GOLD_OPP_KHZ.first())
        if (cap != null) assertThat(cap).isAtLeast(hardFloorKhz)
    }

    @Test
    fun `sustained tighten emits NON-cap levers that are NOT the fatal cap node (daemon would skip, not stop)`() {
        // BALANCED_SMART's tighten ladder is CAP → MIN_FREQ_FLOOR → GPU_DEVFREQ → GPU_FLOOR
        // → PARK. Once the cap bottoms at the 40% floor, the ladder escalates to the
        // non-bindable levers. We assert those ops appear AND that the daemon's cap-node
        // discriminator classifies them as NON-cap (skip-class), never as the fatal cap op.
        val (_, ops) = replayCapturingOps(GoalProfile.BATTERY_SAVER, ticks = 200)

        // Some cap writes happened (the live path is exercised).
        val capOps = ops.filter { it.id.target == capNodeTarget }
        assertThat(capOps).isNotEmpty()

        // The ladder escalated past the cap floor to at least one NON-bindable lever — the
        // exact op that returns CapabilityDenied on AYANEO. (min-freq floor, core-park, or
        // GPU pwrlevel — none of which AyaneoVendorWriter can drive.)
        val nonBindableOps = ops.filter { !AyaneoVendorWriter.isBindableNode(it.id.target) }
        assertThat(nonBindableOps).isNotEmpty()

        // CRITICAL: every non-bindable op is NOT the core live cap node, so the fixed daemon
        // classifies it as a SKIP (keep running), never as the fatal cap-node denial.
        for (op in nonBindableOps) {
            assertThat(op.id.target).isNotEqualTo(capNodeTarget)
        }

        // And conversely the cap node itself IS bindable (drivable live on AYANEO) — so a
        // CapabilityDenied could only ever come from a NON-cap lever in this session, which
        // the daemon skips. The daemon therefore never self-terminates here.
        assertThat(AyaneoVendorWriter.isBindableNode(capNodeTarget)).isTrue()
    }

    @Test
    fun `the cap-node discriminator picks exactly the gold policy3 cap, not the prime policy7`() {
        // The fix derives the fatal-class node from caps.bigPolicyId (the actuated gold
        // cluster), NOT from maxByOrNull (which would be the prime policy7). This keeps the
        // daemon's "is this the core live node?" check aligned with what delta() writes.
        assertThat(capNodeTarget).isEqualTo(Tunables.cpuMaxFreq(3).target)
        assertThat(capNodeTarget).isNotEqualTo(Tunables.cpuMaxFreq(7).target)
    }
}
