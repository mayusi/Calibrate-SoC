package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * The 22 must-have unit tests for the Smart-AutoTDP band controller
 * ([AutoTdpEngine.decide]), per the TIGHTENED CONTROL SPEC in
 * SMART-AUTOTDP-DESIGN.md. All tests are pure value-object tests — no Android, no
 * mocks, no device.
 *
 * Device model (mirrors SD8Gen2 / RP6 / Thor topology):
 *   policy0 : cores 0-3 (little),  top 2016 MHz
 *   policy4 : cores 4-6 (gold/big), top 2803 MHz  ← big-cluster cap target
 *   policy7 : core 7   (prime),     top 3187 MHz  ← parking target
 *
 * Coverage map (22):
 *   No-oscillation              : 4  (edge-camp, worst-OPP-swing, two-actuator-
 *                                     chase-single-lever, cool-down-enforced)
 *   Freq-proxy-never-originates : 3  (proxy-saturation-no-tighten, unpark-gated-to-
 *                                     true-load, proxy-only-drives-gpu+LOAD_BLIND)
 *   Invariants                  : 3  (cpu0-never-parked, cap∈OPP-steps[MM-2],
 *                                     park-XOR-uclamp[ACT-2])
 *   Thermal pre-empt            : 4  (preempt-before-kill, cur_state-immediate,
 *                                     dTemp-noise-rejection, kill-regression)
 *   Classifier                  : 3  (paused-game-guard, asymmetric-hysteresis,
 *                                     anchor-change-fast-declass)
 *   FPS-null degradation        : 3  (null-runs-on-gpu, null-no-crash,
 *                                     not-real-fps-ignored)
 *   Honesty                     : 2  (no-measured-units-in-reason, LOAD_BLIND-on-proxy)
 */
class AutoTdpEngineTest {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    /** SD8Gen2-style 3-cluster caps. Core 7 = only prime; big policy = 4. */
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

    /** Multi-prime caps (cores 5,6,7) for park/unpark chase tests. */
    private val capsMultiPrime = TdpCaps(
        primeCoreIndices = listOf(5, 6, 7),
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

    private val balancedConfig = AutoTdpProfileConfig(AutoTdpProfile.BALANCED) // → BALANCED_SMART (63-85)

    private val KNOWN_GAME = "org.dolphinemu.dolphinemu" // in KnownGames table

    /** Telemetry with explicit fields; all Wave-2 seams default absent. */
    private fun tel(
        gpuLoad: Int,
        coreLoads: List<Int> = List(8) { 30 },
        source: CpuLoadReading.Source = CpuLoadReading.Source.DIRECT_PROC_STAT,
        zones: List<ZoneTemp> = emptyList(),
        fgPkg: String? = null,
        dieTempMilliC: Int? = null,
        coolingState: Int? = null,
        realFpsX10: Int? = null,
        isRealFps: Boolean = false,
    ) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(coreLoads.size.coerceAtLeast(8)) { 1_000_000 },
        perCoreLoadPct = coreLoads,
        cpuLoadSource = source,
        gpuLoadPct = gpuLoad,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = zones,
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = fgPkg,
        gpuDieTempMilliC = dieTempMilliC,
        coolingDeviceMaxState = coolingState,
        realFpsX10 = realFpsX10,
        isRealFps = isRealFps,
    )

    private fun window(gpu: Int, n: Int = 4, build: () -> Telemetry = { tel(gpu) }) =
        List(n) { build() }

    /**
     * Seed a committed HEAVY_GAME [ClassifierState] by running real upgrade ticks
     * (known game + high GPU). Pure test helper — no production-side test hook.
     */
    private fun heavyGameState(pkg: String = KNOWN_GAME): ClassifierState {
        var state = ClassifierState.INITIAL
        repeat(5) {
            val r = ContextClassifier.classify(
                window = listOf(tel(gpuLoad = 90, fgPkg = pkg)),
                smoothedGpuPct = 90,
                prior = state,
            )
            state = r.state
        }
        check(state.stable == WorkloadContext.HEAVY_GAME) { "fixture failed to reach HEAVY_GAME" }
        return state
    }

    /**
     * Run the engine across [ticks] iterations on the same window, threading
     * controllerState. The caller may evolve `current` per tick via [advance].
     */
    private fun drive(
        windowFor: (Int) -> List<Telemetry>,
        caps: TdpCaps = caps3Cluster,
        config: AutoTdpProfileConfig = balancedConfig,
        goal: GoalProfile? = null,
        ticks: Int,
        applyTarget: Boolean = true,
        start: TdpState = TdpState.STOCK,
    ): Pair<TdpDecision, List<TdpDecision>> {
        var state = ControllerState.INITIAL
        var current = start
        val history = mutableListOf<TdpDecision>()
        var last: TdpDecision = AutoTdpEngine.decide(windowFor(0), config, caps, current, state, goal)
        history += last
        if (applyTarget) current = last.target
        state = last.controllerState
        for (i in 1 until ticks) {
            last = AutoTdpEngine.decide(windowFor(i), config, caps, current, state, goal)
            history += last
            if (applyTarget) current = last.target
            state = last.controllerState
        }
        return last to history
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  NO-OSCILLATION (4)
    // ════════════════════════════════════════════════════════════════════════════

    /** 1. Edge-camp: GPU parked exactly inside the band never actuates. */
    @Test
    fun `no-oscillation edge-camp inside band holds forever`() {
        // GPU at 74% sits dead-center in BALANCED_SMART (63-85). Across many ticks the
        // controller must NEVER change state — pure hold.
        val (_, history) = drive(
            windowFor = { window(74) },
            ticks = 20,
        )
        history.forEach { d ->
            assertThat(d.target).isEqualTo(TdpState.STOCK)
        }
    }

    /**
     * 2. Worst-OPP-swing: after a single tighten step the resulting GPU does NOT
     * land outside the band such that the very next tick reverses it. We model the
     * worst 15pt swing by checking the dead-band math: a below-band reading (low GPU)
     * tightens, and a subsequent in-band reading holds (does not immediately loosen).
     */
    @Test
    fun `no-oscillation worst-opp-swing settles without reversal`() {
        var state = ControllerState.INITIAL
        var current: TdpState = TdpState.STOCK
        val cfg = balancedConfig
        // Phase 1: GPU at 50% (below band) for several ticks → confirm + tighten.
        repeat(6) {
            val d = AutoTdpEngine.decide(window(50), cfg, caps3Cluster, current, state)
            current = d.target; state = d.controllerState
        }
        assertThat(current.bigClusterCapKhz).isNotNull() // tightened at least once
        val capAfterTighten = current.bigClusterCapKhz
        // Phase 2: the cap raised GPU back to 74% (dead-center in 63-85). The worst
        // +15pt one-notch swing lands strictly INSIDE the band — the controller must
        // HOLD and must NOT loosen the cap back (no reversal). Run several in-band
        // ticks to be sure no delayed reversal sneaks through.
        repeat(5) {
            val d = AutoTdpEngine.decide(window(74), cfg, caps3Cluster, current, state)
            // Cap must never step UP (loosen) on an in-band reading.
            val cap = d.target.bigClusterCapKhz
            assertThat(cap).isNotNull()
            assertThat(cap!!).isAtMost(capAfterTighten!!)
            current = d.target; state = d.controllerState
        }
    }

    /**
     * 3. Two-actuator-chase, single lever: while tightening, the controller rides ONE
     * lever (cap) repeatedly before touching another — it does NOT alternate cap/park
     * by tick. We assert the cap moves multiple steps before any core is parked.
     */
    @Test
    fun `no-oscillation two-actuator-chase rides single lever`() {
        var state = ControllerState.INITIAL
        var current: TdpState = TdpState.STOCK
        var capStepTicks = 0
        var firstParkTick = -1
        var firstCapTick = -1
        // GPU stays at 40% (below band) for many ticks → sustained tighten. The cap is
        // the FIRST tighten lever; cores park only AFTER cap (and GPU-floor) saturate.
        for (i in 0 until 50) {
            val d = AutoTdpEngine.decide(window(40), balancedConfig, capsMultiPrime, current, state)
            val capChanged = d.target.bigClusterCapKhz != current.bigClusterCapKhz
            val parkedNow = d.target.parkedPrimeCores.size > current.parkedPrimeCores.size
            if (capChanged) { capStepTicks++; if (firstCapTick < 0) firstCapTick = i }
            if (parkedNow && firstParkTick < 0) firstParkTick = i
            current = d.target; state = d.controllerState
        }
        // The cap must have stepped multiple times (single-lever ride, not round-robin).
        assertThat(capStepTicks).isAtLeast(3)
        // The cap lever must engage BEFORE any core is parked (one lever per episode,
        // fixed priority cap→GPU-floor→park — never park on the first tighten tick).
        assertThat(firstCapTick).isAtLeast(0)
        if (firstParkTick >= 0) {
            assertThat(firstParkTick).isGreaterThan(firstCapTick)
        }
    }

    /**
     * 4. Cool-down enforced: after a tighten action, an immediate desire to loosen is
     * blocked for K_TIGHTEN_TO_LOOSEN (3) quiet ticks before it may act.
     */
    @Test
    fun `no-oscillation cool-down blocks tighten-to-loosen for 3 ticks`() {
        // Pre-seed a controller that JUST tightened (lastActedDirection=TIGHTEN,
        // quietTicks=0) with the GPU EWMA already pinned high (95% — above the band)
        // so the ONLY thing between it and a loosen is the K_TIGHTEN_TO_LOOSEN=3
        // cross-actuator cool-down (EWMA lag is removed by pre-seeding gpuEwma=95).
        var current: TdpState = TdpState(bigClusterCapKhz = 1_171_000)
        var state = ControllerState(
            gpuEwma = 95.0,
            lastActedDirection = Direction.TIGHTEN,
            currentDirection = Direction.TIGHTEN,
            quietTicks = 0,
        )
        var loosenedTick = -1
        for (i in 0 until 6) {
            val d = AutoTdpEngine.decide(window(95), balancedConfig, caps3Cluster, current, state)
            if (d.target != current && loosenedTick < 0) loosenedTick = i
            current = d.target; state = d.controllerState
        }
        // The loosen must be blocked for the first 3 quiet ticks: the earliest it may
        // act is tick index 3 (ticks 0,1,2 are the cool-down quiet ticks).
        assertThat(loosenedTick).isAtLeast(3)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FREQ-PROXY-NEVER-ORIGINATES (3) — FP-1
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * 5. A freq-proxy "saturated" core must NOT originate an unpark/loosen. With GPU
     * below band, a proxy-saturated core must NOT flip the decision to relax.
     */
    @Test
    fun `freq-proxy saturated core does not originate relax`() {
        val proxyWindow = List(4) {
            tel(
                gpuLoad = 40, // below band → tighten direction
                coreLoads = List(8) { i -> if (i == 7) 95 else 50 }, // looks saturated
                source = CpuLoadReading.Source.FREQ_PROXY, // ...but proxy only
            )
        }
        val (decision, _) = drive(windowFor = { proxyWindow }, ticks = 4)
        // Must NOT take the CPU-relax path; holdReason must not be CPU_BOUND_RELAXING.
        assertThat(decision.holdReason).isNotEqualTo(HoldReason.CPU_BOUND_RELAXING)
    }

    /**
     * 6. Unpark is gated to TRUE load: the SAME saturated reading unparks under
     * ROOT_PROC_STAT but NOT under FREQ_PROXY.
     */
    @Test
    fun `unpark gated to true-load only`() {
        // Cap already at stock (null) and no GPU-floor → UNPARK is the first available
        // loosen lever, so a CPU-saturation relax must directly unpark cpu7 IF the load
        // is real, and must do NOTHING if the saturation is only a freq-proxy reading.
        val parked = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = null, gpuFloorLevel = null)
        val sat = List(8) { i -> if (i == 7) 95 else 50 }

        val trueLoadWin = List(4) { tel(gpuLoad = 40, coreLoads = sat, source = CpuLoadReading.Source.ROOT_PROC_STAT) }
        val proxyWin = List(4) { tel(gpuLoad = 40, coreLoads = sat, source = CpuLoadReading.Source.FREQ_PROXY) }

        val trueDecision = AutoTdpEngine.decide(trueLoadWin, balancedConfig, caps3Cluster, parked)
        val proxyDecision = AutoTdpEngine.decide(proxyWin, balancedConfig, caps3Cluster, parked)

        // True load → CPU-relax path → unpark cpu7.
        assertThat(trueDecision.holdReason).isEqualTo(HoldReason.CPU_BOUND_RELAXING)
        assertThat(trueDecision.target.parkedPrimeCores).doesNotContain(7)
        // Proxy → never originates a relax; cpu7 stays parked.
        assertThat(proxyDecision.target.parkedPrimeCores).contains(7)
        assertThat(proxyDecision.holdReason).isNotEqualTo(HoldReason.CPU_BOUND_RELAXING)
    }

    /**
     * 7. Proxy-only window drives on GPU%+pkg+thermal and surfaces LOAD_BLIND for the
     * CPU dimension when holding in band.
     */
    @Test
    fun `proxy-only window drives on gpu and reports LOAD_BLIND when holding`() {
        // GPU inside band → hold; CPU is proxy-only → LOAD_BLIND, not IDLE.
        val proxyWin = List(4) {
            tel(gpuLoad = 74, coreLoads = List(8) { 60 }, source = CpuLoadReading.Source.FREQ_PROXY)
        }
        val (decision, _) = drive(windowFor = { proxyWin }, ticks = 6)
        assertThat(decision.holdReason).isEqualTo(HoldReason.LOAD_BLIND_HOLDING)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  INVARIANTS (3)
    // ════════════════════════════════════════════════════════════════════════════

    /** 8. cpu0 is NEVER parked, even when caps pathologically include it. */
    @Test
    fun `cpu0 never parked even when caps include it`() {
        val badCaps = capsMultiPrime.copy(primeCoreIndices = listOf(0, 5, 6, 7))
        val (_, history) = drive(
            windowFor = { window(40) }, // below band → keeps trying to park
            caps = badCaps,
            ticks = 15,
        )
        history.forEach { assertThat(it.target.parkedPrimeCores).doesNotContain(0) }
    }

    /** 9. MM-2: any emitted cap is a real OPP step (or null). */
    @Test
    fun `mm-2 emitted cap is always an OPP step`() {
        val (_, history) = drive(windowFor = { window(40) }, ticks = 15)
        history.forEach { d ->
            val cap = d.target.bigClusterCapKhz
            if (cap != null) {
                assertThat(caps3Cluster.bigClusterOppStepsKhz).contains(cap)
            }
        }
    }

    /**
     * 10. ACT-2: {park} XOR {uclamp} — Wave 1 has no uclamp actuator, so a tightening
     * episode parks cores and NEVER sets any uclamp field. We assert parking occurs
     * and (proxy for "no uclamp") the only state fields touched are cap/park/gpuFloor.
     */
    @Test
    fun `act-2 tighten parks without any uclamp side-channel`() {
        // Pre-seed a state whose cap and GPU-floor are already at their tighten floors
        // (cap at bottom OPP, GPU-floor at gpuMaxLevel) so PARK is the only remaining
        // tighten lever. Sustained below-band GPU must then park a prime core — and
        // never touch a uclamp/governor side-channel (Wave 1 has no uclamp lever).
        val bottomCap = capsMultiPrime.bigClusterOppStepsKhz.first()
        var current = TdpState(bigClusterCapKhz = bottomCap, gpuFloorLevel = capsMultiPrime.gpuMaxLevel)
        // confirmTicks=2 satisfies the tighten confirm gate immediately.
        var state = ControllerState(gpuEwma = 40.0, currentDirection = Direction.TIGHTEN, confirmTicks = 2)
        repeat(8) {
            val d = AutoTdpEngine.decide(window(40), balancedConfig, capsMultiPrime, current, state)
            current = d.target; state = d.controllerState
        }
        // Parking must have happened (cap + GPU-floor were already saturated).
        assertThat(current.parkedPrimeCores).isNotEmpty()
        // No uclamp lever exists in Wave 1: governorOverrides (the only other TdpState
        // map field) stays empty — nothing wrote a uclamp/governor side-channel.
        assertThat(current.governorOverrides).isEmpty()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  THERMAL PRE-EMPT (4)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * 11. Pre-empt before kill: smoothed die ≥ soft_target tightens immediately, well
     * below the 105°C kill, bypassing cool-down.
     */
    @Test
    fun `thermal pre-empt fires at soft die-temp before kill`() {
        // BALANCED_SMART soft = 88°C. Die at 90°C → pre-empt tighten on the FIRST tick.
        val hotWin = List(4) { tel(gpuLoad = 74, dieTempMilliC = 90_000) }
        val decision = AutoTdpEngine.decide(hotWin, balancedConfig, caps3Cluster, TdpState.STOCK)
        // Must tighten (cap applied) even though GPU is inside the band.
        assertThat(decision.target.bigClusterCapKhz).isNotNull()
        assertThat(decision.holdReason).isEqualTo(HoldReason.GPU_BOUND_CAPPING)
        assertThat(decision.reason.lowercase()).contains("pre-empt")
    }

    /** 12. cur_state > 0 (kernel throttling NOW) tightens immediately, any temp. */
    @Test
    fun `thermal pre-empt fires immediately on cooling cur_state`() {
        // Cool die, GPU in band — but the kernel reports cooling_device active.
        val win = List(4) { tel(gpuLoad = 74, dieTempMilliC = 60_000, coolingState = 1) }
        val decision = AutoTdpEngine.decide(win, balancedConfig, caps3Cluster, TdpState.STOCK)
        assertThat(decision.target).isNotEqualTo(TdpState.STOCK)
        assertThat(decision.reason.lowercase()).contains("pre-empt")
    }

    /**
     * 13. dTemp noise rejection: a single-tick temperature spike (with the smoothed
     * slope still low and die well below soft) must NOT trigger pre-empt.
     */
    @Test
    fun `thermal pre-empt rejects single-tick dTemp noise`() {
        // Die hovers at 70°C (well below 88 soft, and >8°C away so arm2 cannot fire),
        // with one noisy spike. Slope must not, on its own, pre-empt.
        val win = listOf(
            tel(gpuLoad = 74, dieTempMilliC = 70_000),
            tel(gpuLoad = 74, dieTempMilliC = 70_000),
            tel(gpuLoad = 74, dieTempMilliC = 71_000),
            tel(gpuLoad = 74, dieTempMilliC = 70_000), // spike gone
        )
        val decision = AutoTdpEngine.decide(win, balancedConfig, caps3Cluster, TdpState.STOCK)
        // GPU in band + no real thermal threat → HOLD, no pre-empt.
        assertThat(decision.target).isEqualTo(TdpState.STOCK)
        assertThat(decision.reason.lowercase()).doesNotContain("pre-empt")
    }

    /**
     * 14. Kill-regression: the thermal KILL (105°C / 2-sample) is unchanged by this
     * wave. ThermalKillEvaluator still fires; the engine's pre-empt does not suppress
     * it. We assert the evaluator (untouched safety) kills at 105°C.
     */
    @Test
    fun `thermal kill regression evaluator still fires at 105C`() {
        val evaluator = ThermalKillEvaluator(graceSamples = 0)
        val hot = tel(gpuLoad = 74, zones = listOf(ZoneTemp(0, "cpu", 105_000)))
        // First over-threshold sample: not yet (needs 2 consecutive).
        assertThat(evaluator.evaluate(hot)).isNull()
        // Second consecutive: kill fires.
        assertThat(evaluator.evaluate(hot)).isNotNull()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CLASSIFIER (3)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * 15. Paused-game guard: a HEAVY_GAME whose GPU drops to idle (paused) while the
     * foreground package is UNCHANGED must NOT declass below LIGHT_GAME.
     */
    @Test
    fun `classifier paused-game guard never declasses below LIGHT_GAME`() {
        // Establish HEAVY_GAME: known game + high GPU for several upgrade ticks.
        var state = ClassifierState.INITIAL
        repeat(5) {
            val r = ContextClassifier.classify(
                window = listOf(tel(gpuLoad = 90, fgPkg = KNOWN_GAME)),
                smoothedGpuPct = 90,
                prior = state,
            )
            state = r.state
        }
        assertThat(state.stable).isEqualTo(WorkloadContext.HEAVY_GAME)

        // Game paused: GPU collapses to 5% but SAME package. Even after the long
        // downgrade window, it must hold at LIGHT_GAME (never IDLE/VIDEO).
        var ctx = state.stable
        repeat(20) {
            val r = ContextClassifier.classify(
                window = listOf(tel(gpuLoad = 5, fgPkg = KNOWN_GAME)),
                smoothedGpuPct = 5,
                prior = state,
            )
            state = r.state; ctx = r.context
        }
        assertThat(ctx).isAnyOf(WorkloadContext.HEAVY_GAME, WorkloadContext.LIGHT_GAME)
        assertThat(ctx.isGameClass).isTrue()
    }

    /**
     * 16. Asymmetric hysteresis: an UPGRADE commits in 2 ticks; a DOWNGRADE takes 8.
     */
    @Test
    fun `classifier asymmetric hysteresis upgrade fast downgrade slow`() {
        // Upgrade: from IDLE-ish (no anchor) to HEAVY_GAME needs only 2 agreeing ticks.
        var up = ClassifierState.INITIAL
        val u1 = ContextClassifier.classify(listOf(tel(90, fgPkg = KNOWN_GAME)), 90, up); up = u1.state
        val u2 = ContextClassifier.classify(listOf(tel(90, fgPkg = KNOWN_GAME)), 90, up); up = u2.state
        assertThat(u2.context).isEqualTo(WorkloadContext.HEAVY_GAME) // committed by tick 2

        // Downgrade: HEAVY_GAME → (anchor leaves) light, but with anchor present a
        // genuine workload drop to LIGHT_GAME must NOT commit before 8 ticks.
        var dn = heavyGameState()
        var committedTick = -1
        for (i in 0 until 10) {
            val r = ContextClassifier.classify(listOf(tel(50, fgPkg = KNOWN_GAME)), 50, dn)
            if (r.context == WorkloadContext.LIGHT_GAME && committedTick < 0) committedTick = i
            dn = r.state
        }
        // Must take at least the 8-tick downgrade window (i.e. not commit at tick 0..6).
        assertThat(committedTick).isAtLeast(ContextClassifier.DOWNGRADE_TICKS - 1)
    }

    /**
     * 17. Anchor-change fast-declass: when the foreground package CHANGES, the
     * paused-game guard is void and the classifier may move to a lighter context.
     */
    @Test
    fun `classifier anchor change voids paused guard`() {
        // Heavy game established.
        var state = heavyGameState(pkg = KNOWN_GAME)
        // Foreground switches to a non-game (launcher), GPU low. Anchor changed →
        // guard void. After the confirm window the context leaves the game class.
        var ctx: WorkloadContext = WorkloadContext.HEAVY_GAME
        for (i in 0 until 12) {
            val r = ContextClassifier.classify(
                window = listOf(tel(gpuLoad = 5, fgPkg = "com.android.launcher")),
                smoothedGpuPct = 5,
                prior = state,
            )
            state = r.state; ctx = r.context
        }
        // It must have left the game class (guard did not pin it).
        assertThat(ctx.isGameClass).isFalse()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FPS-NULL DEGRADATION (3)
    // ════════════════════════════════════════════════════════════════════════════

    /** 18. FPS null (the common case): the loop runs cleanly on GPU%+CPU+thermal. */
    @Test
    fun `fps-null window decides normally on gpu signal`() {
        // No FPS at all; GPU below band → must still tighten.
        val win = List(4) { tel(gpuLoad = 40, realFpsX10 = null, isRealFps = false) }
        val (decision, _) = drive(windowFor = { win }, ticks = 4)
        assertThat(decision.target.bigClusterCapKhz).isNotNull()
    }

    /** 19. FPS null does not crash and produces a valid decision in band. */
    @Test
    fun `fps-null window in band holds without crash`() {
        val win = List(4) { tel(gpuLoad = 74, realFpsX10 = null, isRealFps = false) }
        val decision = AutoTdpEngine.decide(win, balancedConfig, caps3Cluster, TdpState.STOCK)
        assertThat(decision.target).isEqualTo(TdpState.STOCK)
        assertThat(decision.holdReason).isAnyOf(HoldReason.IDLE_HOLDING, HoldReason.LOAD_BLIND_HOLDING)
    }

    /**
     * 20. isRealFps == false: a present-but-not-real FPS value is ignored entirely —
     * the decision is identical to the FPS-absent case.
     */
    @Test
    fun `not-real fps value is ignored`() {
        val withFakeFps = List(4) { tel(gpuLoad = 40, realFpsX10 = 100, isRealFps = false) }
        val withoutFps = List(4) { tel(gpuLoad = 40, realFpsX10 = null, isRealFps = false) }
        val a = AutoTdpEngine.decide(withFakeFps, balancedConfig, caps3Cluster, TdpState.STOCK)
        val b = AutoTdpEngine.decide(withoutFps, balancedConfig, caps3Cluster, TdpState.STOCK)
        assertThat(a.target).isEqualTo(b.target)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  HONESTY (2) — H-1
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * 21. H-1: a band-controller reason string carries config/intent ONLY — never a
     * measured quantity (no mW / °C / fps in the reason).
     */
    @Test
    fun `honesty reason carries no measured units`() {
        // Drive a variety of paths and check every reason string.
        val reasons = mutableListOf<String>()
        // tighten path
        reasons += drive(windowFor = { window(40) }, ticks = 8).second.map { it.reason }
        // loosen path
        reasons += drive(windowFor = { window(98) }, ticks = 8, start = TdpState(bigClusterCapKhz = 1_171_000)).second.map { it.reason }
        // hold path
        reasons += AutoTdpEngine.decide(window(74), balancedConfig, caps3Cluster, TdpState.STOCK).reason
        // thermal pre-empt path
        reasons += AutoTdpEngine.decide(List(4) { tel(74, dieTempMilliC = 95_000) }, balancedConfig, caps3Cluster, TdpState.STOCK).reason

        reasons.forEach { r ->
            val lower = r.lowercase()
            // No measured-unit tokens. (MHz is a CONFIG target, allowed; mW/°C/fps are
            // MEASURED quantities, forbidden in the band-controller reason.)
            assertThat(lower).doesNotContain("mw")
            assertThat(lower).doesNotContain("°c")
            assertThat(lower).doesNotContain(" c,")
            assertThat(lower).doesNotContain("fps")
        }
    }

    /** 22. LOAD_BLIND on proxy: proxy-only CPU surfaces LOAD_BLIND_HOLDING when held. */
    @Test
    fun `honesty proxy-only cpu surfaces LOAD_BLIND`() {
        val win = List(4) {
            tel(gpuLoad = 74, coreLoads = List(8) { 55 }, source = CpuLoadReading.Source.FREQ_PROXY)
        }
        val (decision, _) = drive(windowFor = { win }, ticks = 6)
        assertThat(decision.holdReason).isEqualTo(HoldReason.LOAD_BLIND_HOLDING)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  BONUS — preserved BATTERY watts-ceiling + deriveBudgetCap contract
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `deriveBudgetCap returns a step within the OPP table`() {
        val step = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 3_000L)
        if (step != null) assertThat(caps3Cluster.bigClusterOppStepsKhz).contains(step)
    }

    @Test
    fun `deriveBudgetCap null for empty table and zero budget`() {
        assertThat(AutoTdpEngine.deriveBudgetCap(caps3Cluster.copy(bigClusterOppStepsKhz = emptyList()), 3_000L)).isNull()
        assertThat(AutoTdpEngine.deriveBudgetCap(caps3Cluster, 0L)).isNull()
    }

    @Test
    fun `battery-saver honours watts ceiling as cap floor`() {
        val cfg = AutoTdpProfileConfig(AutoTdpProfile.BATTERY_TARGET, targetMilliWatts = 1_500L)
        // BATTERY_TARGET → BATTERY_SAVER (35-60 band). GPU low → tighten toward the
        // watts-budget floor; the cap must never go below the budget step.
        val budgetStep = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 1_500L)!!
        val (_, history) = drive(windowFor = { window(20) }, config = cfg, ticks = 15)
        history.forEach { d ->
            val cap = d.target.bigClusterCapKhz
            if (cap != null) assertThat(cap).isAtLeast(budgetStep)
        }
    }
}
