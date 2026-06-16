package io.github.mayusi.calibratesoc.data.autotdp

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test

/**
 * WAVE 4a — wiring + state-persistence tests for the GoalProfile + ControllerState
 * contract through the controller / service-intent / engine.
 *
 * These are PURE JVM tests (no Robolectric). [Intent] is backed by a real in-memory
 * map (see [fakeIntent]) so the goal/profile/watts encode↔decode round-trip is
 * exercised for real — only the Android container is faked, exactly as a Bundle is
 * a key/value map under the hood.
 *
 * What is proven here (the Wave 4a contract):
 *   A. AutoTdpProfileConfig carries the Smart goal; forGoal() picks the right legacy
 *      mirror; the intent round-trip preserves the goal end-to-end.
 *   B. The daemon's persist-and-reassign loop keeps ControllerState ALIVE across
 *      ticks — EWMA accumulators advance, the cross-actuator cool-down counts up,
 *      and the classifier hysteresis commits — none reset. The BROKEN (re-INITIAL
 *      every tick) loop is shown to defeat all three, proving the fix is load-bearing.
 *   C. The per-app native goal path passes a real GoalProfile (AUTO survives — it is
 *      NOT collapsed to BALANCED by a lossy map).
 *   D. AutoTdpRunState exposes activeGoal + detectedContext (honest belief, never a
 *      measurement).
 */
class AutoTdpGoalWiringTest {

    // ─── Device fixture (SD8Gen2 / RP6 topology, mirrors AutoTdpEngineTest) ──────

    private val caps = TdpCaps(
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

    private val KNOWN_GAME = "org.dolphinemu.dolphinemu" // in KnownGames table

    private fun tel(
        gpuLoad: Int,
        coreLoads: List<Int> = List(8) { 30 },
        source: CpuLoadReading.Source = CpuLoadReading.Source.DIRECT_PROC_STAT,
        zones: List<ZoneTemp> = emptyList(),
        fgPkg: String? = null,
        dieTempMilliC: Int? = null,
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
        coolingDeviceMaxState = null,
        realFpsX10 = null,
        isRealFps = false,
    )

    private fun window(gpu: Int, n: Int = 4, fgPkg: String? = null) =
        List(n) { tel(gpuLoad = gpu, fgPkg = fgPkg) }

    /**
     * A MockK [Intent] backed by a real map so put/get genuinely round-trips. Only
     * the extras used by build/configFromStartIntent are wired (string / int / long).
     */
    private fun fakeIntent(): Intent {
        val extras = HashMap<String, Any?>()
        val intent = mockk<Intent>(relaxed = true)

        val keyS = slot<String>(); val valS = slot<String>()
        every { intent.putExtra(capture(keyS), capture(valS)) } answers {
            extras[keyS.captured] = valS.captured; intent
        }
        val keyI = slot<String>(); val valI = slot<Int>()
        every { intent.putExtra(capture(keyI), capture(valI)) } answers {
            extras[keyI.captured] = valI.captured; intent
        }
        val keyL = slot<String>(); val valL = slot<Long>()
        every { intent.putExtra(capture(keyL), capture(valL)) } answers {
            extras[keyL.captured] = valL.captured; intent
        }

        every { intent.getStringExtra(any()) } answers {
            extras[firstArg()] as? String
        }
        val giKey = slot<String>(); val giDef = slot<Int>()
        every { intent.getIntExtra(capture(giKey), capture(giDef)) } answers {
            (extras[giKey.captured] as? Int) ?: giDef.captured
        }
        val glKey = slot<String>(); val glDef = slot<Long>()
        every { intent.getLongExtra(capture(glKey), capture(glDef)) } answers {
            (extras[glKey.captured] as? Long) ?: glDef.captured
        }
        return intent
    }

    /**
     * Build a start intent for [config] WITHOUT a real Context (the Intent
     * constructor needs Android). We mimic AutoTdpService.buildStartIntent's extra
     * writes against [fakeIntent], then decode via the REAL configFromStartIntent.
     * This exercises the production decode path against production-shaped extras.
     */
    private fun roundTrip(config: AutoTdpProfileConfig): AutoTdpProfileConfig {
        val intent = fakeIntent()
        // Mirror buildStartIntent's writes (the Context-bound constructor is the only
        // un-fakeable part; the EXTRA encoding is identical and is what we verify).
        intent.putExtra(AutoTdpService.EXTRA_PROFILE_ORDINAL, config.profile.ordinal)
        config.targetMilliWatts?.let { intent.putExtra(AutoTdpService.EXTRA_TARGET_MW, it) }
        config.goal?.let { intent.putExtra(AutoTdpService.EXTRA_GOAL, it.name) }
        return AutoTdpService.configFromStartIntent(intent)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  A. CONFIG + CONTROLLER + INTENT CARRY THE GOAL
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `config carries the smart goal and keeps profile populated`() {
        val cfg = AutoTdpProfileConfig.forGoal(GoalProfile.COOL_QUIET)
        assertThat(cfg.goal).isEqualTo(GoalProfile.COOL_QUIET)
        // profile is always populated (back-compat); COOL_QUIET has no ceiling → EFFICIENCY mirror.
        assertThat(cfg.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
        assertThat(cfg.targetMilliWatts).isNull()
    }

    @Test
    fun `forGoal mirrors a hard-ceiling goal to BATTERY_TARGET and keeps the watts`() {
        val cfg = AutoTdpProfileConfig.forGoal(GoalProfile.BATTERY_SAVER, targetMilliWatts = 2_500L)
        assertThat(cfg.goal).isEqualTo(GoalProfile.BATTERY_SAVER)
        assertThat(cfg.profile).isEqualTo(AutoTdpProfile.BATTERY_TARGET) // hasHardPowerCeiling
        assertThat(cfg.targetMilliWatts).isEqualTo(2_500L)
    }

    @Test
    fun `forGoal mirrors BALANCED_SMART and AUTO to BALANCED`() {
        assertThat(AutoTdpProfileConfig.forGoal(GoalProfile.BALANCED_SMART).profile)
            .isEqualTo(AutoTdpProfile.BALANCED)
        assertThat(AutoTdpProfileConfig.forGoal(GoalProfile.AUTO).profile)
            .isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `legacy config has null goal so the daemon keeps the legacy path`() {
        val cfg = AutoTdpProfileConfig(AutoTdpProfile.BALANCED)
        assertThat(cfg.goal).isNull()
    }

    @Test
    fun `intent round-trip preserves every smart goal including AUTO`() {
        for (goal in GoalProfile.entries) {
            val original = AutoTdpProfileConfig.forGoal(goal, targetMilliWatts = 1_800L)
            val rebuilt = roundTrip(original)
            assertThat(rebuilt.goal).isEqualTo(goal)
            assertThat(rebuilt.profile).isEqualTo(original.profile)
            assertThat(rebuilt.targetMilliWatts).isEqualTo(1_800L)
        }
    }

    @Test
    fun `intent round-trip with no goal yields null goal (legacy path survives)`() {
        val original = AutoTdpProfileConfig(AutoTdpProfile.EFFICIENCY)
        val rebuilt = roundTrip(original)
        assertThat(rebuilt.goal).isNull()
        assertThat(rebuilt.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
        assertThat(rebuilt.targetMilliWatts).isNull()
    }

    @Test
    fun `unparseable goal name in the intent degrades to null goal, not a crash`() {
        val intent = fakeIntent()
        intent.putExtra(AutoTdpService.EXTRA_PROFILE_ORDINAL, AutoTdpProfile.BALANCED.ordinal)
        intent.putExtra(AutoTdpService.EXTRA_GOAL, "NOT_A_REAL_GOAL")
        val cfg = AutoTdpService.configFromStartIntent(intent)
        assertThat(cfg.goal).isNull()
        assertThat(cfg.profile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  B. CONTROLLER STATE PERSISTS ACROSS TICKS  (THE critical fix)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Faithfully replays the daemon's control loop: hold one `var controllerState`
     * OUTSIDE the loop, pass it into decide(), and reassign it from the returned
     * decision after each tick. [windowFor] supplies the per-tick window so a step
     * change can be modelled. Returns the per-tick controller states (index 0 = after
     * tick 1). [goal] is passed as goalOverride exactly as AutoTdpService now does.
     */
    private fun persistingLoop(
        ticks: Int,
        goal: GoalProfile? = null,
        windowFor: (Int) -> List<Telemetry>,
    ): List<ControllerState> {
        var controllerState = ControllerState.INITIAL // per-session reset (once)
        var current = TdpState.STOCK
        val states = mutableListOf<ControllerState>()
        repeat(ticks) { i ->
            val decision = AutoTdpEngine.decide(
                window = windowFor(i),
                config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED),
                caps = caps,
                current = current,
                controllerState = controllerState, // ← threaded IN
                goalOverride = goal,
            )
            current = decision.target
            controllerState = decision.controllerState // ← PERSIST for next tick
            states += controllerState
        }
        return states
    }

    /** The BROKEN loop (pre-fix): decide() called with a fresh INITIAL every tick. */
    private fun resettingLoop(
        ticks: Int,
        goal: GoalProfile? = null,
        windowFor: (Int) -> List<Telemetry>,
    ): List<ControllerState> {
        var current = TdpState.STOCK
        val states = mutableListOf<ControllerState>()
        repeat(ticks) { i ->
            val decision = AutoTdpEngine.decide(
                window = windowFor(i),
                config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED),
                caps = caps,
                current = current,
                controllerState = ControllerState.INITIAL, // ← BUG: reset every tick
                goalOverride = goal,
            )
            current = decision.target
            states += decision.controllerState
        }
        return states
    }

    // Convenience constant-GPU window factories for the step-free tests.
    private fun constGpu(gpu: Int, fgPkg: String? = null): (Int) -> List<Telemetry> =
        { window(gpu, fgPkg = fgPkg) }

    @Test
    fun `tick counter advances monotonically when persisted (and is stuck at 1 when reset)`() {
        val persisted = persistingLoop(ticks = 5, windowFor = constGpu(74))
        // tick is advanced once per decide(); persisted across ticks → 1,2,3,4,5.
        assertThat(persisted.map { it.tick }).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
        // The broken loop re-INITIALs each tick → every decision reports tick == 1.
        val broken = resettingLoop(ticks = 5, windowFor = constGpu(74))
        assertThat(broken.map { it.tick }).containsExactly(1L, 1L, 1L, 1L, 1L).inOrder()
    }

    @Test
    fun `gpu EWMA accumulator is carried across ticks (1-pole convergence) not reset each tick`() {
        // Model a STEP change: tick 0 sees an all-30 window (seed = mean = 30), ticks
        // 1+ see an all-90 window. With persistence the EWMA (alpha=0.4) does a true
        // 1-pole walk 30 → 54 → 68.4 → 78.96 → … converging toward 90 across ticks.
        // Without persistence each tick reseeds from INITIAL → the window MEAN (= 90
        // from tick 1 on), snapping straight to 90 with NO 1-pole filtering at all.
        val step: (Int) -> List<Telemetry> = { i -> window(if (i == 0) 30 else 90) }

        val persisted = persistingLoop(ticks = 5, windowFor = step).map { it.gpuEwma!! }
        // Strictly increasing 30 → … → toward 90 (the accumulator carried each tick).
        assertThat(persisted).isInOrder()
        assertThat(persisted.zipWithNext().all { (a, b) -> b > a }).isTrue()
        assertThat(persisted.first()).isWithin(0.01).of(30.0)        // seed = mean(30)
        assertThat(persisted[1]).isWithin(0.01).of(0.4 * 90 + 0.6 * 30) // = 54.0 (1-pole)
        assertThat(persisted.last()).isLessThan(90.0)                // not snapped yet

        val broken = resettingLoop(ticks = 5, windowFor = step).map { it.gpuEwma!! }
        // tick0 = 30 (mean of 30s); every later reset tick reseeds to the 90-window
        // mean = 90 — no convergence, just an instant snap. Distinct from persisted.
        assertThat(broken[1]).isWithin(0.01).of(90.0)
        assertThat(broken.drop(1).toSet()).containsExactly(90.0)
    }

    @Test
    fun `cross-actuator cool-down quietTicks counts up across ticks when persisted`() {
        // GPU at 50% sits BELOW the BALANCED_SMART band (63-85): the controller wants
        // to tighten. After it acts and then holds, quietTicks must ACCRUE across ticks
        // (the cross-actuator cool-down counter). With persistence we see it climb;
        // with the reset bug it can never exceed what a single tick produces.
        val persisted = persistingLoop(ticks = 12, windowFor = constGpu(50))
        val maxQuietPersisted = persisted.maxOf { it.quietTicks }
        val broken = resettingLoop(ticks = 12, windowFor = constGpu(50))
        val maxQuietBroken = broken.maxOf { it.quietTicks }
        // The persisted loop accrues a real multi-tick cool-down; the reset loop cannot.
        assertThat(maxQuietPersisted).isGreaterThan(maxQuietBroken)
        assertThat(maxQuietPersisted).isAtLeast(1)
    }

    @Test
    fun `direction-episode confirm counter advances across ticks when persisted`() {
        // Below-band GPU → the controller confirms a TIGHTEN episode. The confirm /
        // lever / lastActedDirection live in ControllerState; persisting them lets the
        // episode progress (it eventually ACTS, recording lastActedDirection=TIGHTEN).
        val persisted = persistingLoop(ticks = 8, windowFor = constGpu(50))
        assertThat(persisted.any { it.lastActedDirection == Direction.TIGHTEN }).isTrue()
        // The reset loop starts a fresh episode every tick — it can confirm but the
        // carried confirm counter never survives to the NEXT tick's view.
        val broken = resettingLoop(ticks = 8, windowFor = constGpu(50))
        // Each broken decision is computed from INITIAL (confirmTicks=0 in), so no
        // decision ever accrues a multi-tick confirm: confirmTicks never exceeds 1.
        assertThat(broken.maxOf { it.confirmTicks }).isAtMost(1)
        assertThat(persisted.maxOf { it.confirmTicks }).isAtLeast(2)
    }

    @Test
    fun `classifier hysteresis commits across ticks when persisted (and never when reset)`() {
        // A known game at high GPU should upgrade the classifier to HEAVY_GAME after
        // UPGRADE_TICKS (2) agreeing ticks — but ONLY if the classifier state is
        // threaded. Persisted: the stable context climbs to HEAVY_GAME. Reset: the
        // classifier restarts from UNKNOWN every tick and can NEVER commit.
        val persisted = persistingLoop(ticks = 6, windowFor = constGpu(90, fgPkg = KNOWN_GAME))
        assertThat(persisted.last().classifier.stable).isEqualTo(WorkloadContext.HEAVY_GAME)

        val broken = resettingLoop(ticks = 6, windowFor = constGpu(90, fgPkg = KNOWN_GAME))
        // Without carried agreeingTicks the commit can never happen → stays UNKNOWN.
        assertThat(broken.last().classifier.stable).isEqualTo(WorkloadContext.UNKNOWN)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  C. PER-APP NATIVE GOAL  — AUTO survives end-to-end (not collapsed to BALANCED)
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `native goal path carries AUTO through the intent (no lossy collapse)`() {
        // The per-app bundle calls controller.start(goal). start(goal) builds
        // forGoal(goal). Through the intent round-trip the goal must remain AUTO — the
        // old lossy goalToLegacyProfile would have turned AUTO into BALANCED (losing
        // the classifier entirely). Prove AUTO survives.
        val cfg = AutoTdpProfileConfig.forGoal(GoalProfile.AUTO)
        val rebuilt = roundTrip(cfg)
        assertThat(rebuilt.goal).isEqualTo(GoalProfile.AUTO)
    }

    @Test
    fun `AUTO goalOverride engages the real classifier instead of mapping to BALANCED`() {
        // With goalOverride=AUTO and a heavy-game window, the engine resolves AUTO via
        // the classifier and the decision's resolvedGoal reflects a REAL classifier
        // choice (BALANCED_SMART for a cool heavy game) — proving AUTO reached the
        // classifier rather than being pre-collapsed.
        val states = persistingLoop(
            ticks = 6, goal = GoalProfile.AUTO, windowFor = constGpu(90, fgPkg = KNOWN_GAME),
        )
        assertThat(states.last().classifier.stable).isEqualTo(WorkloadContext.HEAVY_GAME)
    }

    @Test
    fun `decision resolvedGoal echoes the requested goal for a concrete (non-AUTO) goal`() {
        val decision = AutoTdpEngine.decide(
            window = window(74),
            config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED),
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
            goalOverride = GoalProfile.COOL_QUIET,
        )
        assertThat(decision.resolvedGoal).isEqualTo(GoalProfile.COOL_QUIET)
    }

    @Test
    fun `decision resolvedGoal echoes the classifier-resolved goal for AUTO`() {
        // Drive AUTO to a committed HEAVY_GAME + cool trend → classifier maps to
        // BALANCED_SMART. The decision's resolvedGoal must be the CONCRETE goal, not AUTO.
        var controllerState = ControllerState.INITIAL
        var current = TdpState.STOCK
        var last = AutoTdpEngine.decide(
            window = window(90, fgPkg = KNOWN_GAME),
            config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED),
            caps = caps, current = current,
            controllerState = controllerState, goalOverride = GoalProfile.AUTO,
        )
        repeat(5) {
            current = last.target
            controllerState = last.controllerState
            last = AutoTdpEngine.decide(
                window = window(90, fgPkg = KNOWN_GAME),
                config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED),
                caps = caps, current = current,
                controllerState = controllerState, goalOverride = GoalProfile.AUTO,
            )
        }
        // HEAVY_GAME + cool → BALANCED_SMART (never AUTO; never the legacy collapse).
        assertThat(last.resolvedGoal).isNotEqualTo(GoalProfile.AUTO)
        assertThat(last.resolvedGoal).isEqualTo(GoalProfile.BALANCED_SMART)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  D. RUN STATE EXPOSES activeGoal + detectedContext  (read-only, honest belief)
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `AutoTdpRunState exposes activeGoal and detectedContext fields`() {
        val state = AutoTdpRunState(
            activeGoal = GoalProfile.BALANCED_SMART,
            detectedContext = WorkloadContext.HEAVY_GAME,
        )
        assertThat(state.activeGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(state.detectedContext).isEqualTo(WorkloadContext.HEAVY_GAME)
    }

    @Test
    fun `AutoTdpRunState defaults activeGoal and detectedContext to null (honest unknown)`() {
        val state = AutoTdpRunState()
        assertThat(state.activeGoal).isNull()
        assertThat(state.detectedContext).isNull()
    }

    @Test
    fun `the daemon would surface the concrete AUTO-resolved goal as activeGoal`() {
        // Mirror the service's per-tick computation: activeGoal = decision.resolvedGoal
        // when config.goal != null; detectedContext = classifier.stable. Drive AUTO to
        // HEAVY_GAME and assert what the run state WOULD carry.
        val cfg = AutoTdpProfileConfig.forGoal(GoalProfile.AUTO)
        var controllerState = ControllerState.INITIAL
        var current = TdpState.STOCK
        var last = AutoTdpEngine.decide(
            window(90, fgPkg = KNOWN_GAME), cfg, caps, current, controllerState, cfg.goal,
        )
        repeat(5) {
            current = last.target; controllerState = last.controllerState
            last = AutoTdpEngine.decide(
                window(90, fgPkg = KNOWN_GAME), cfg, caps, current, controllerState, cfg.goal,
            )
        }
        val activeGoal = if (cfg.goal != null) last.resolvedGoal else null
        val detectedContext = last.controllerState.classifier.stable
        assertThat(activeGoal).isEqualTo(GoalProfile.BALANCED_SMART) // AUTO resolved
        assertThat(detectedContext).isEqualTo(WorkloadContext.HEAVY_GAME)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  E. START USES THE SELECTED GOAL  (UI wiring bug 4 — MAX must not run BALANCED)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Mirrors the FIXED screen→VM wiring: tapping the MAX goal chip then START builds a
     * config via forGoal(MAX_FPS) and starts the daemon with config.goal = MAX_FPS,
     * which the service passes as goalOverride. The BUG was that START always built a
     * legacy BALANCED profile config (goal == null), so the daemon ran the BALANCED
     * band (GPU 63-85) instead of MAX_FPS's (70-95).
     */
    @Test
    fun `selecting MAX FPS and starting builds a config carrying the MAX_FPS goal`() {
        val cfg = AutoTdpProfileConfig.forGoal(GoalProfile.MAX_FPS)
        // The authority the engine follows is config.goal — and it is NOT collapsed.
        assertThat(cfg.goal).isEqualTo(GoalProfile.MAX_FPS)
        // Through the production intent round-trip the goal still arrives as MAX_FPS.
        assertThat(roundTrip(cfg).goal).isEqualTo(GoalProfile.MAX_FPS)
    }

    /**
     * The load-bearing behavioural proof: with goalOverride = MAX_FPS the engine hunts
     * the MAX_FPS band (70-95), NOT the BALANCED band (63-85). A GPU sample at 67% is
     * BELOW the MAX_FPS band (would tighten toward it) but INSIDE the BALANCED band
     * (would hold). The decision's reason carries the goal's band edges, so we can
     * assert the engine genuinely followed MAX_FPS and not BALANCED — exactly the
     * difference the user saw on-device (Max-FPS GPU 70-95 vs the wrongly-applied
     * Balanced 63-85).
     */
    @Test
    fun `MAX_FPS goalOverride drives the Max-FPS band not the Balanced band`() {
        val maxDecision = AutoTdpEngine.decide(
            window = window(67),
            config = AutoTdpProfileConfig.forGoal(GoalProfile.MAX_FPS),
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
            goalOverride = GoalProfile.MAX_FPS,
        )
        // The reason string carries the goal's CONFIG band edges (H-1). MAX_FPS = 70-95.
        assertThat(maxDecision.reason).contains("70-95")
        assertThat(maxDecision.reason).doesNotContain("63-85")
        assertThat(maxDecision.resolvedGoal).isEqualTo(GoalProfile.MAX_FPS)

        // Same sample under BALANCED would target the 63-85 band — proving the band
        // (and therefore the goal) genuinely differs, not just the label.
        val balDecision = AutoTdpEngine.decide(
            window = window(67),
            config = AutoTdpProfileConfig.forGoal(GoalProfile.BALANCED_SMART),
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
            goalOverride = GoalProfile.BALANCED_SMART,
        )
        assertThat(balDecision.reason).contains("63-85")
        assertThat(balDecision.resolvedGoal).isEqualTo(GoalProfile.BALANCED_SMART)
    }

    @Test
    fun `the daemon surfaces null activeGoal on the legacy (no-goal) path`() {
        val cfg = AutoTdpProfileConfig(AutoTdpProfile.BALANCED) // goal == null
        val decision = AutoTdpEngine.decide(
            window(74), cfg, caps, TdpState.STOCK, ControllerState.INITIAL, cfg.goal,
        )
        val activeGoal = if (cfg.goal != null) decision.resolvedGoal else null
        assertThat(activeGoal).isNull()
        // detectedContext is still surfaced (the classifier runs regardless of path).
        assertThat(decision.controllerState.classifier.stable).isNotNull()
    }
}
