package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.thermal.CapFloor
import org.junit.Test

/**
 * UNIT 1 — per-game learning ENGINE wire-in tests (seed + proactive pre-empt).
 *
 * Pure value-object tests (no Android, no device). Mirrors the fixtures of
 * [AutoTdpEngineTest]. Asserts the two SAFETY-CRITICAL contracts:
 *   1. COLD START (seed == null) → decisions are IDENTICAL to the no-seed call.
 *   2. The seed only sets the STARTING cap (snapped + 40%-floor-clamped, once); the
 *      proactive pre-empt arms ONCE near 0.85 × onset, only TIGHTENS, never below floor,
 *      and stands down whenever a real thermal pre-empt is active.
 */
class AutoTdpEngineUnit1Test {

    private val opp = listOf(
        499_000, 844_000, 1_171_000, 1_536_000,
        1_920_000, 2_323_000, 2_707_000, 2_803_000,
    )
    private val topOpp = opp.last()
    private val hardFloor = CapFloor.hardFloorKhz(opp)!!

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = opp,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    private val balancedConfig = AutoTdpProfileConfig(AutoTdpProfile.BALANCED) // → BALANCED_SMART 63-85

    /** Cool, in-band telemetry: GPU 74% (dead-center 63-85), die ~50°C (well below 88 soft). */
    private fun coolTel(gpuLoad: Int = 74, dieMilliC: Int = 50_000) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 40 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT,
        gpuLoadPct = gpuLoad,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", dieMilliC)),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = null,
        gpuDieTempMilliC = dieMilliC,
        coolingDeviceMaxState = null,
        realFpsX10 = null,
        isRealFps = false,
    )

    private fun window(gpu: Int = 74, dieMilliC: Int = 50_000, n: Int = 4) =
        List(n) { coolTel(gpu, dieMilliC) }

    private fun seed(cap: Int? = 1_920_000, onset: Int? = null, sessions: Int = 5) =
        LearnedSeed(
            safeSustainedCapKhz = cap,
            throttleOnsetSec = onset,
            observedBandCenterPct = 74,
            sessionCount = sessions,
        )

    // ── COLD START IDENTITY ─────────────────────────────────────────────────────────

    @Test
    fun `cold start - seed null decisions identical to the no-seed call`() {
        var sBaseline = ControllerState.INITIAL
        var sSeeded = ControllerState.INITIAL
        var cBaseline: TdpState = TdpState.STOCK
        var cSeeded: TdpState = TdpState.STOCK
        repeat(15) { i ->
            val gpu = if (i % 3 == 0) 50 else 74 // mix of tighten + hold ticks
            val w = window(gpu)
            val baseline = AutoTdpEngine.decide(w, balancedConfig, caps, cBaseline, sBaseline)
            val seeded = AutoTdpEngine.decide(
                w, balancedConfig, caps, cSeeded, sSeeded,
                seed = null, sessionElapsedSec = i,
            )
            // Byte-identical: same target, reason, holdReason, AND carried controller state.
            assertThat(seeded.target).isEqualTo(baseline.target)
            assertThat(seeded.reason).isEqualTo(baseline.reason)
            assertThat(seeded.holdReason).isEqualTo(baseline.holdReason)
            assertThat(seeded.controllerState).isEqualTo(baseline.controllerState)
            cBaseline = baseline.target; sBaseline = baseline.controllerState
            cSeeded = seeded.target; sSeeded = seeded.controllerState
        }
    }

    // ── SEEDING THE STARTING CAP ────────────────────────────────────────────────────

    @Test
    fun `warm seed sets the starting cap snapped to a real OPP`() {
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, TdpState.STOCK, ControllerState.INITIAL,
            seed = seed(cap = 2_000_000), // off-table → snaps DOWN to 1_920_000
            sessionElapsedSec = 0,
        )
        assertThat(d.target.bigClusterCapKhz).isEqualTo(1_920_000)
        assertThat(d.target.bigClusterCapKhz).isIn(opp)
        assertThat(d.controllerState.learnedSeedApplied).isTrue()
        assertThat(d.reason).ignoringCase().contains("seeded")
    }

    @Test
    fun `seed clamps a below-floor learned cap up to the 40 percent floor`() {
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, TdpState.STOCK, ControllerState.INITIAL,
            seed = seed(cap = 499_000), // below the 40% floor
            sessionElapsedSec = 0,
        )
        assertThat(d.target.bigClusterCapKhz!!).isAtLeast(hardFloor)
        assertThat(d.target.bigClusterCapKhz).isEqualTo(hardFloor)
    }

    @Test
    fun `seed at or above the top OPP clears the cap to stock`() {
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, TdpState.STOCK, ControllerState.INITIAL,
            seed = seed(cap = topOpp),
            sessionElapsedSec = 0,
        )
        assertThat(d.target.bigClusterCapKhz).isNull() // top OPP → no cap needed
        assertThat(d.controllerState.learnedSeedApplied).isTrue()
    }

    @Test
    fun `seed applies exactly once - latched across ticks`() {
        // Tick 0 seeds the cap. The latch must prevent re-seeding on later ticks even
        // though the same seed is passed every tick.
        var state = ControllerState.INITIAL
        var current: TdpState = TdpState.STOCK
        val s = seed(cap = 1_920_000)
        val d0 = AutoTdpEngine.decide(window(74), balancedConfig, caps, current, state, seed = s, sessionElapsedSec = 0)
        assertThat(d0.controllerState.learnedSeedApplied).isTrue()
        current = d0.target; state = d0.controllerState
        // Now move the cap up via loosen pressure (GPU high) and ensure the seed never
        // re-clamps it back to the seeded value.
        repeat(8) { i ->
            val d = AutoTdpEngine.decide(window(95), balancedConfig, caps, current, state, seed = s, sessionElapsedSec = i + 1)
            current = d.target; state = d.controllerState
            assertThat(d.controllerState.learnedSeedApplied).isTrue()
        }
        // The seed did not pin the cap — the controller loosened away from the seeded OPP.
        assertThat(current.bigClusterCapKhz == null || current.bigClusterCapKhz!! >= 1_920_000).isTrue()
    }

    // ── PROACTIVE PRE-EMPT ──────────────────────────────────────────────────────────

    @Test
    fun `proactive preempt does not fire before the arm window`() {
        // onset=200 → arm window starts at 170s. At t=10s nothing proactive should happen.
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, TdpState.STOCK, ControllerState.INITIAL,
            seed = seed(cap = topOpp, onset = 200), // no starting cap, just an onset
            sessionElapsedSec = 10,
        )
        assertThat(d.controllerState.proactivePreemptArmed).isFalse()
        assertThat(d.reason).ignoringCase().doesNotContain("proactive")
    }

    @Test
    fun `proactive preempt arms once near 0_85x onset and tightens the cap`() {
        // onset=200 → 0.85×200 = 170s. Fire at t=175s. Start uncapped, cool + in-band so
        // the ONLY thing that can move the cap is the proactive arm.
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, TdpState.STOCK, ControllerState.INITIAL,
            seed = seed(cap = topOpp, onset = 200),
            sessionElapsedSec = 175,
        )
        assertThat(d.controllerState.proactivePreemptArmed).isTrue()
        // It TIGHTENED one notch below the top OPP (a real, below-top OPP) — never floored.
        assertThat(d.target.bigClusterCapKhz).isNotNull()
        assertThat(d.target.bigClusterCapKhz!!).isLessThan(topOpp)
        assertThat(d.target.bigClusterCapKhz!!).isAtLeast(hardFloor)
        assertThat(d.reason).ignoringCase().contains("proactive")
    }

    @Test
    fun `proactive preempt fires at most once per session`() {
        var state = ControllerState.INITIAL
        var current: TdpState = TdpState.STOCK
        val s = seed(cap = topOpp, onset = 200)
        // First arm at t=175.
        val d0 = AutoTdpEngine.decide(window(74), balancedConfig, caps, current, state, seed = s, sessionElapsedSec = 175)
        assertThat(d0.controllerState.proactivePreemptArmed).isTrue()
        current = d0.target; state = d0.controllerState
        val capAfterArm = current.bigClusterCapKhz
        // Subsequent ticks still inside the window must NOT fire a second proactive tighten.
        repeat(3) { i ->
            val d = AutoTdpEngine.decide(window(74), balancedConfig, caps, current, state, seed = s, sessionElapsedSec = 176 + i)
            assertThat(d.reason).ignoringCase().doesNotContain("proactive")
            current = d.target; state = d.controllerState
        }
        // Cap did not keep dropping from repeated proactive fires.
        assertThat(current.bigClusterCapKhz).isEqualTo(capAfterArm)
    }

    @Test
    fun `proactive preempt never fires below the 40 percent floor`() {
        // Start already AT the hard floor; a proactive arm must not push below it.
        val atFloor = TdpState(bigClusterCapKhz = hardFloor)
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, atFloor,
            ControllerState.INITIAL.copy(learnedSeedApplied = true), // skip re-seeding
            seed = seed(cap = hardFloor, onset = 100),
            sessionElapsedSec = 90, // within 0.85×100=85 .. 110
        )
        assertThat(d.target.bigClusterCapKhz!!).isAtLeast(hardFloor)
        assertThat(d.controllerState.proactivePreemptArmed).isTrue() // latched even at floor
    }

    @Test
    fun `real thermal preempt takes priority over the proactive arm`() {
        // Hot die (≥ soft 88°C) AND inside the proactive window. The REAL thermal pre-empt
        // must win — the reason is the thermal pre-empt, not the proactive one.
        val hot = window(gpu = 74, dieMilliC = 92_000) // ≥ 88°C soft → real pre-empt
        val d = AutoTdpEngine.decide(
            hot, balancedConfig, caps, TdpState.STOCK,
            ControllerState.INITIAL.copy(learnedSeedApplied = true),
            seed = seed(cap = topOpp, onset = 100),
            sessionElapsedSec = 90, // would otherwise arm the proactive
        )
        assertThat(d.reason).ignoringCase().contains("thermal pre-empt")
        assertThat(d.reason).ignoringCase().doesNotContain("proactive")
    }

    @Test
    fun `no onset means no proactive arm even deep into the session`() {
        val d = AutoTdpEngine.decide(
            window(74), balancedConfig, caps, TdpState.STOCK,
            ControllerState.INITIAL.copy(learnedSeedApplied = true),
            seed = seed(cap = 1_920_000, onset = null),
            sessionElapsedSec = 9_999,
        )
        assertThat(d.controllerState.proactivePreemptArmed).isFalse()
        assertThat(d.reason).ignoringCase().doesNotContain("proactive")
    }
}
