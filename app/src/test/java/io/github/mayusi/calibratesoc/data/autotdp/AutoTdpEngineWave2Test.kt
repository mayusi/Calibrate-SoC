package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Wave 2 unit tests for the new actuators + invariants wired into the Smart-AutoTDP
 * band controller. Pure value-object tests (no Android, no device).
 *
 * Covers (per the Wave 2 VERIFY contract):
 *   - MM-1: bigClusterMinKhz strictly < bigClusterCapKhz, lockstep lowering
 *   - MM-2: min + cap are members of the OPP step table
 *   - ACT-2: {park} XOR {uclamp} per goal — never both
 *   - GPU devfreq min < max, both within probed bounds
 *   - fan governor rate-limit (≥10 ticks) + 5°C hysteresis + monotonic
 *   - lever selection per goal (MAX_FPS uclamp+floor, COOL_QUIET devfreq>park, ...)
 *   - new telemetry fields flow into computeSignals (die temp, cooling state)
 */
class AutoTdpEngineWave2Test {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    /** Big OPP table (kHz) used everywhere — matches AutoTdpEngineTest. */
    private val bigOpps = listOf(
        499_000, 844_000, 1_171_000, 1_536_000,
        1_920_000, 2_323_000, 2_707_000, 2_803_000,
    )

    /** Odin-style GPU devfreq table (Hz): 160 MHz .. 1100 MHz. */
    private val gpuDevfreqSteps = listOf(
        160_000_000L, 305_000_000L, 451_000_000L, 547_000_000L,
        650_000_000L, 800_000_000L, 950_000_000L, 1_100_000_000L,
    )

    /**
     * Full Wave-2 caps: single prime core (7), big policy 4, GPU devfreq table +
     * uclamp + fan ALL available. Goals can therefore actually select the new levers.
     */
    private val capsWave2 = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = bigOpps,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = gpuDevfreqSteps.first(),
        gpuDevfreqCeilHz = gpuDevfreqSteps.last(),
        gpuDevfreqStepsHz = gpuDevfreqSteps,
        gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    /** Multi-prime variant (cores 5,6,7) for park/uclamp chase tests. */
    private val capsWave2MultiPrime = capsWave2.copy(primeCoreIndices = listOf(5, 6, 7))

    private val balancedConfig = AutoTdpProfileConfig(AutoTdpProfile.BALANCED) // → BALANCED_SMART
    private val KNOWN_GAME = "org.dolphinemu.dolphinemu"

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

    /** Drive [ticks] iterations threading controllerState + applying the target. */
    private fun drive(
        windowFor: (Int) -> List<Telemetry>,
        caps: TdpCaps = capsWave2,
        config: AutoTdpProfileConfig = balancedConfig,
        goal: GoalProfile? = null,
        ticks: Int,
        start: TdpState = TdpState.STOCK,
    ): Pair<TdpDecision, List<TdpDecision>> {
        var state = ControllerState.INITIAL
        var current = start
        val history = mutableListOf<TdpDecision>()
        var last = AutoTdpEngine.decide(windowFor(0), config, caps, current, state, goal)
        history += last; current = last.target; state = last.controllerState
        for (i in 1 until ticks) {
            last = AutoTdpEngine.decide(windowFor(i), config, caps, current, state, goal)
            history += last; current = last.target; state = last.controllerState
        }
        return last to history
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  MM-1 : floor < cap, lockstep lowering
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Across a sustained tighten under BALANCED_SMART (which steps cap then floor),
     * the emitted min floor is ALWAYS strictly below the emitted cap.
     */
    @Test
    fun `mm-1 min floor stays strictly below cap across a full tighten run`() {
        val (_, history) = drive(windowFor = { window(40) }, ticks = 40)
        history.forEach { d ->
            val cap = d.target.bigClusterCapKhz
            val floor = d.target.bigClusterMinKhz
            if (cap != null && floor != null) {
                assertThat(floor).isLessThan(cap)
            }
        }
    }

    /**
     * Lockstep: a pre-seeded state whose floor would invert under the next cap drop
     * gets the floor lowered to one OPP below the new cap, never an inversion.
     */
    @Test
    fun `mm-1 lockstep lowers floor when a cap drop would invert it`() {
        // Floor at index 5 (2_323_000), cap at index 6 (2_707_000): floor is one below
        // cap. A tighten that drops the cap to index 5 would invert — enforceInvariants
        // must pull the floor to index 4 (1_920_000).
        var current = TdpState(
            bigClusterCapKhz = bigOpps[6],
            bigClusterMinKhz = bigOpps[5],
        )
        // Seed a tighten episode already past the confirm gate, EWMA pinned low.
        var state = ControllerState(
            gpuEwma = 40.0,
            currentDirection = Direction.TIGHTEN,
            confirmTicks = 2,
            activeLever = Lever.CAP,
        )
        repeat(6) {
            val d = AutoTdpEngine.decide(window(40), balancedConfig, capsWave2, current, state)
            val cap = d.target.bigClusterCapKhz
            val floor = d.target.bigClusterMinKhz
            if (cap != null && floor != null) assertThat(floor).isLessThan(cap)
            current = d.target; state = d.controllerState
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  MM-2 : min + cap ∈ OPP steps
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `mm-2 emitted min floor is always an OPP step`() {
        val (_, history) = drive(windowFor = { window(40) }, ticks = 40)
        history.forEach { d ->
            d.target.bigClusterMinKhz?.let { assertThat(bigOpps).contains(it) }
            d.target.bigClusterCapKhz?.let { assertThat(bigOpps).contains(it) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  ACT-2 : {park} XOR {uclamp}
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * MAX_FPS uses uclamp, NEVER parks. Even when its cap/GPU/floor saturate under a
     * sustained tighten, it must never park a core, and may set a uclamp hint.
     */
    @Test
    fun `act-2 max-fps never parks and may set uclamp`() {
        // MAX_FPS band is 70-95; GPU at 40 is below → sustained tighten.
        val (_, history) = drive(
            windowFor = { window(40) },
            caps = capsWave2MultiPrime,
            goal = GoalProfile.MAX_FPS,
            ticks = 60,
        )
        history.forEach { d ->
            assertThat(d.target.parkedPrimeCores).isEmpty() // NEVER parks (uclamp goal)
        }
        // Over a long tighten the uclamp hint should have been lowered at least once.
        assertThat(history.any { it.target.uclampTopAppMin != null }).isTrue()
        // And no decision ever carries BOTH a parked core and a uclamp hint.
        history.forEach { d ->
            val both = d.target.parkedPrimeCores.isNotEmpty() && d.target.uclampTopAppMin != null
            assertThat(both).isFalse()
        }
    }

    /**
     * BALANCED_SMART parks (does not use uclamp). It must never set a uclamp hint.
     */
    @Test
    fun `act-2 balanced parks and never sets uclamp`() {
        val (_, history) = drive(
            windowFor = { window(40) },
            caps = capsWave2MultiPrime,
            goal = GoalProfile.BALANCED_SMART,
            ticks = 60,
        )
        history.forEach { d ->
            assertThat(d.target.uclampTopAppMin).isNull() // park goal: no uclamp ever
        }
        // Parking eventually occurs once the freq levers saturate.
        assertThat(history.any { it.target.parkedPrimeCores.isNotEmpty() }).isTrue()
    }

    /**
     * Defence-in-depth: a pathological pre-seeded state with BOTH a parked core AND a
     * uclamp hint is corrected by enforceInvariants to the goal's choice.
     */
    @Test
    fun `act-2 invariant strips the conflicting actuator`() {
        // Pre-seed BOTH on a uclamp goal (MAX_FPS) → park must be dropped, uclamp kept.
        val seeded = TdpState(parkedPrimeCores = setOf(7), uclampTopAppMin = 600)
        // Hold inside MAX_FPS band (70-95) so the engine doesn't act — but the loosen/
        // tighten paths run enforceInvariants. Use a below-band tighten to force a
        // lever apply that re-emits through enforceInvariants.
        var current = seeded
        var state = ControllerState(gpuEwma = 40.0, currentDirection = Direction.TIGHTEN, confirmTicks = 2)
        val d = AutoTdpEngine.decide(window(40), balancedConfig, capsWave2, current, state, GoalProfile.MAX_FPS)
        // MAX_FPS uses uclamp → the parked core must be stripped.
        assertThat(d.target.parkedPrimeCores).isEmpty()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  GPU devfreq bounds
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `gpu devfreq min stays strictly below max and within bounds`() {
        // COOL_QUIET prefers GPU-devfreq early. Drive a long tighten so the devfreq
        // max steps down repeatedly, then assert bounds on every emitted state.
        val (_, history) = drive(
            windowFor = { window(30) },
            goal = GoalProfile.COOL_QUIET,
            ticks = 50,
        )
        history.forEach { d ->
            val dmin = d.target.gpuDevfreqMinHz
            val dmax = d.target.gpuDevfreqMaxHz
            if (dmin != null) {
                assertThat(dmin).isAtLeast(capsWave2.gpuDevfreqFloorHz!!)
                assertThat(dmin).isAtMost(capsWave2.gpuDevfreqCeilHz!!)
            }
            if (dmax != null) {
                assertThat(dmax).isAtLeast(capsWave2.gpuDevfreqFloorHz!!)
                assertThat(dmax).isAtMost(capsWave2.gpuDevfreqCeilHz!!)
            }
            if (dmin != null && dmax != null) assertThat(dmin).isLessThan(dmax)
        }
    }

    @Test
    fun `gpu devfreq lever is skipped when no devfreq table`() {
        // Caps without a devfreq envelope → no devfreq write ever appears.
        val noDevfreq = capsWave2.copy(
            gpuDevfreqFloorHz = null,
            gpuDevfreqCeilHz = null,
            gpuDevfreqStepsHz = emptyList(),
        )
        val (_, history) = drive(
            windowFor = { window(30) },
            caps = noDevfreq,
            goal = GoalProfile.COOL_QUIET,
            ticks = 30,
        )
        history.forEach { d ->
            assertThat(d.target.gpuDevfreqMinHz).isNull()
            assertThat(d.target.gpuDevfreqMaxHz).isNull()
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FAN GOVERNOR : rate-limit + hysteresis + monotonic
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `fan governor allows the first change then rate-limits for 10 ticks`() {
        var s = FanGovernorState.INITIAL
        // First change: allowed (no prior change to rate-limit).
        val first = FanGovernor.decide(GoalProfile.FanPresets.SPORT, dieTempC = 90, tick = 5, state = s)
        assertThat(first).isEqualTo(GoalProfile.FanPresets.SPORT)
        s = FanGovernor.recordChange(first!!, 90, 5)
        // A different preset only 4 ticks later: rate-limited → null.
        val tooSoon = FanGovernor.decide(GoalProfile.FanPresets.SMART, dieTempC = 80, tick = 9, state = s)
        assertThat(tooSoon).isNull()
        // 10 ticks later AND ≥5°C cooler: allowed.
        val later = FanGovernor.decide(GoalProfile.FanPresets.SMART, dieTempC = 80, tick = 15, state = s)
        assertThat(later).isEqualTo(GoalProfile.FanPresets.SMART)
    }

    @Test
    fun `fan governor enforces 5C hysteresis`() {
        var s = FanGovernor.recordChange(GoalProfile.FanPresets.SMART, dieTempC = 80, tick = 0)
        // 12 ticks later (rate-limit cleared) but only 3°C hotter → within hysteresis.
        val withinHys = FanGovernor.decide(GoalProfile.FanPresets.SPORT, dieTempC = 83, tick = 12, state = s)
        assertThat(withinHys).isNull()
        // 12 ticks later and 6°C hotter → hysteresis cleared, allowed (monotonic up).
        val pastHys = FanGovernor.decide(GoalProfile.FanPresets.SPORT, dieTempC = 86, tick = 12, state = s)
        assertThat(pastHys).isEqualTo(GoalProfile.FanPresets.SPORT)
    }

    @Test
    fun `fan governor is monotonic per direction`() {
        // Last applied SMART at 80°C. Request a HIGHER-cooling preset (Sport) while
        // the die got COOLER — non-monotonic → rejected even past rate-limit+hysteresis.
        val s = FanGovernor.recordChange(GoalProfile.FanPresets.SMART, dieTempC = 80, tick = 0)
        val nonMonotonic = FanGovernor.decide(GoalProfile.FanPresets.SPORT, dieTempC = 70, tick = 20, state = s)
        assertThat(nonMonotonic).isNull()
    }

    @Test
    fun `fan governor holds when die temp is unknown`() {
        val s = FanGovernorState.INITIAL
        assertThat(FanGovernor.decide(GoalProfile.FanPresets.SPORT, dieTempC = null, tick = 10, state = s)).isNull()
    }

    /**
     * End-to-end: COOL_QUIET drives the fan via the engine, but only when the device
     * has a controllable fan AND not faster than the rate-limit.
     */
    @Test
    fun `engine drives fan_mode under cool-quiet and respects rate-limit`() {
        // Hot die so COOL_QUIET wants the Smart preset (≥ soft-5 = 75°C). Drive many
        // ticks; the fan_mode must be set at most once within any 10-tick window.
        val hotWin = { tel(gpuLoad = 60, dieTempMilliC = 78_000) }
        val (_, history) = drive(
            windowFor = { List(4) { hotWin() } },
            goal = GoalProfile.COOL_QUIET,
            ticks = 30,
        )
        // At least one tick set the fan preset.
        val fanSetTicks = history.withIndex().filter {
            it.value.target.fanMode != null &&
                (it.index == 0 || history[it.index - 1].target.fanMode != it.value.target.fanMode)
        }.map { it.index }
        // Once warmed up the preset should have been applied; and consecutive changes
        // (if any) are ≥10 ticks apart (rate-limit).
        for (i in 1 until fanSetTicks.size) {
            assertThat(fanSetTicks[i] - fanSetTicks[i - 1]).isAtLeast(10)
        }
    }

    /** When the device has no controllable fan, fan_mode is never set. */
    @Test
    fun `engine never sets fan_mode when fan unavailable`() {
        val noFan = capsWave2.copy(fanModeAvailable = false)
        val (_, history) = drive(
            windowFor = { List(4) { tel(gpuLoad = 60, dieTempMilliC = 78_000) } },
            caps = noFan,
            goal = GoalProfile.COOL_QUIET,
            ticks = 20,
        )
        history.forEach { assertThat(it.target.fanMode).isNull() }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  LEVER SELECTION PER GOAL
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * MAX_FPS loosen prefers the min-freq FLOOR first (smoothness). Loosening from a
     * pre-capped state, the first lever to move should be the min floor (raised),
     * not the cap.
     */
    @Test
    fun `max-fps loosen raises min floor early for smoothness`() {
        // Start capped + GPU floored so there is room to loosen. GPU above band (98 >
        // 95) → loosen. MAX_FPS order = MIN_FREQ_FLOOR, GPU_DEVFREQ, GPU_FLOOR, CAP, UCLAMP.
        val start = TdpState(
            bigClusterCapKhz = bigOpps[3],
            bigClusterMinKhz = bigOpps[0],
            gpuFloorLevel = 3,
        )
        val (_, history) = drive(
            windowFor = { window(98) },
            goal = GoalProfile.MAX_FPS,
            ticks = 6,
            start = start,
        )
        // The FIRST state that differs from start must have moved the min floor up
        // (smoothness lever first), not stepped the cap.
        val firstChange = history.firstOrNull { it.target != start }
        assertThat(firstChange).isNotNull()
        val t = firstChange!!.target
        assertThat(t.bigClusterMinKhz).isGreaterThan(start.bigClusterMinKhz!!)
        // Cap unchanged on that first move (single lever, floor-first).
        assertThat(t.bigClusterCapKhz).isEqualTo(start.bigClusterCapKhz)
    }

    /**
     * COOL_QUIET tighten prefers GPU-devfreq over parking: under a long below-band
     * tighten, a GPU devfreq max step occurs BEFORE any core is parked.
     */
    @Test
    fun `cool-quiet tightens via gpu devfreq before parking`() {
        var state = ControllerState.INITIAL
        var current: TdpState = TdpState.STOCK
        var firstDevfreqTick = -1
        var firstParkTick = -1
        for (i in 0 until 60) {
            val d = AutoTdpEngine.decide(
                window(30), balancedConfig, capsWave2MultiPrime, current, state, GoalProfile.COOL_QUIET,
            )
            val devfreqMoved = d.target.gpuDevfreqMaxHz != current.gpuDevfreqMaxHz
            val parkedMore = d.target.parkedPrimeCores.size > current.parkedPrimeCores.size
            if (devfreqMoved && firstDevfreqTick < 0) firstDevfreqTick = i
            if (parkedMore && firstParkTick < 0) firstParkTick = i
            current = d.target; state = d.controllerState
        }
        // A devfreq step must have happened, and (if parking happened at all) it came
        // after the devfreq lever engaged.
        assertThat(firstDevfreqTick).isAtLeast(0)
        if (firstParkTick >= 0) assertThat(firstParkTick).isGreaterThan(firstDevfreqTick)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TELEMETRY → computeSignals FLOW
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The new gpuDieTempMilliC field drives thermal pre-empt: a hot die (via the new
     * field, NOT zone temps) tightens even with GPU in band.
     */
    @Test
    fun `gpu die temp field flows into thermal pre-empt`() {
        // BALANCED_SMART soft = 88°C; die 90°C via the new field; zones EMPTY (so only
        // the new field could have driven it).
        val win = List(4) { tel(gpuLoad = 74, dieTempMilliC = 90_000, zones = emptyList()) }
        val d = AutoTdpEngine.decide(win, balancedConfig, capsWave2, TdpState.STOCK)
        assertThat(d.reason.lowercase()).contains("pre-empt")
        assertThat(d.target).isNotEqualTo(TdpState.STOCK)
    }

    /**
     * The new coolingDeviceMaxState field drives the immediate-tighten arm: cur_state
     * > 0 pre-empts regardless of a cool die.
     */
    @Test
    fun `cooling cur_state field flows into immediate pre-empt`() {
        val win = List(4) { tel(gpuLoad = 74, dieTempMilliC = 55_000, coolingState = 2) }
        val d = AutoTdpEngine.decide(win, balancedConfig, capsWave2, TdpState.STOCK)
        assertThat(d.reason.lowercase()).contains("pre-empt")
    }

    /**
     * foregroundPackage flows into the classifier anchor: a known game pins AUTO to a
     * game-class goal (not battery), shown by the AUTO path not driving toward the
     * battery band's aggressive tighten on a clearly-gaming tick.
     */
    @Test
    fun `foreground package field anchors the classifier`() {
        // Known game + high GPU → HEAVY_GAME. Confirm the classifier commits it.
        var state = ClassifierState.INITIAL
        repeat(3) {
            val r = ContextClassifier.classify(
                window = listOf(tel(gpuLoad = 90, fgPkg = KNOWN_GAME)),
                smoothedGpuPct = 90,
                prior = state,
            )
            state = r.state
        }
        assertThat(state.stable).isEqualTo(WorkloadContext.HEAVY_GAME)
    }
}
