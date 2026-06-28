package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import io.github.mayusi.calibratesoc.data.monitor.hasTrueLoadData
import io.github.mayusi.calibratesoc.data.thermal.CapFloor

/**
 * The Smart-AutoTDP decision brain — a GOAL-GATED UTILIZATION-BAND CONTROLLER with
 * THERMAL PRE-EMPTION.
 *
 * PURE: no Android, no I/O, no time calls. All inputs are value objects; the output
 * is a [TdpDecision] the daemon applies via TunableWriter. Every path is unit-
 * testable without a device or Android runtime.
 *
 * ## The model (per 1 Hz tick)
 *
 *  1. **Resolve the goal.** A [GoalProfile] (or AUTO → classifier) gives a GPU
 *     busy% band + a soft die-temp + a bias. The band IS the goal.
 *  2. **Smooth the GPU signal** (EWMA α=0.4) and compare to the band:
 *       - above the band  → LOOSEN one notch (give the workload more headroom)
 *       - below the band  → TIGHTEN one notch (reclaim power)
 *       - inside the band → HOLD (the ≥22pt dead-band kills oscillation)
 *  3. **One lever per direction-episode** (ACT-1): a direction rides ONE actuator
 *     until it saturates, then hands off in fixed priority — never round-robin.
 *  4. **Cross-actuator cool-down** prevents tighten→stutter→loosen cycles.
 *  5. **Thermal pre-empt (OR)** tightens early when the die is hot / heating /
 *     the kernel is already throttling — exempt from the one-lever rule.
 *
 * ## The SEVEN INVARIANTS (all enforced before emitting any state)
 *  - **FP-1:** freq-proxy CPU load may set a holdReason but NEVER originates a
 *    state change. The CPU-saturation/unpark fast-path is gated to
 *    [Telemetry.hasTrueLoadData] ONLY. (This REVERSES the prior engine's
 *    unpark-on-proxy behaviour.) Proxy-only CPU → drive on GPU%+pkg+thermal and
 *    emit [HoldReason.LOAD_BLIND_HOLDING] for the CPU dimension.
 *  - **ACT-1:** one lever per direction-episode (above).
 *  - **ACT-2:** {park} XOR {uclamp} on a cluster per goal — never both. (Wave 1
 *    has no uclamp actuator yet, so this is trivially satisfied; the lever priority
 *    is shaped so Wave 2's uclamp slots in as park's mutually-exclusive sibling.)
 *  - **MM-1:** scaling_min_freq floor < bigClusterCap ALWAYS. (Wave 1 has no
 *    min-freq actuator; the seam is reserved in the loosen lever order.)
 *  - **MM-2:** both min and cap ∈ caps.bigClusterOppStepsKhz.
 *  - **H-1:** band-controller reason strings carry CONFIG/INTENT only — never a
 *    measured quantity (mW/°C/fps). A new DETECTED tier (classifier belief) is
 *    distinct from DERIVED (config) and MEASURED (probe).
 *  - cpu0 never parked; online ≥ minOnlineCores.
 *
 * The output shape ([TdpDecision] = target + reason + holdReason) is unchanged so
 * the daemon needs no edit; controller + classifier state ride along as a defaulted
 * field the daemon threads back each tick.
 */
object AutoTdpEngine {

    // ── Smoothing / control constants (LAW) ────────────────────────────────────

    /** GPU busy% EWMA smoothing factor. */
    private const val GPU_EWMA_ALPHA = 0.4

    /** dTemp slope EWMA smoothing factor (≥3 ticks; never a 1-tick raw delta). */
    private const val DTEMP_EWMA_ALPHA = 0.5

    /** Minimum slope history before the dTemp pre-empt arm may fire. */
    private const val DTEMP_MIN_TICKS = 3

    /** dTemp slope (°C/s) that arms the rising-fast thermal pre-empt. */
    private const val DTEMP_PREEMPT_C_PER_S = 3.0

    /** "Within N°C of soft" window that gates the dTemp pre-empt arm. */
    private const val DTEMP_NEAR_SOFT_C = 8

    /**
     * HIGH-3: plausible GPU die-temp band in milli-°C. The engine only PREFERS the GPU die
     * read over the skin zones when it falls in this band; otherwise the die is treated as
     * absent (fall back to zones). Mirrors SysfsProber's normalization band — defense in
     * depth so a bad-unit / zero / off-scale die can never blind the thermal pre-empt.
     */
    private const val DIE_SANE_MILLI_MIN = 20_000
    private const val DIE_SANE_MILLI_MAX = 130_000

    /** A big/prime core at/above this TRUE-load% is the CPU bottleneck (unpark). */
    private const val CPU_SATURATED_THRESHOLD = 85

    /** Cross-actuator cool-down counts (LAW). */
    private const val K_TIGHTEN_TO_LOOSEN = 3   // tighten→loosen needs 3 quiet ticks
    private const val K_TIGHTEN_TO_TIGHTEN = 1  // consecutive tighten cadence
    private const val K_LOOSEN_TO_TIGHTEN = 2   // loosen→tighten cadence

    /** Confirming-tick law: loosen acts on 1, tighten on 2. */
    private const val LOOSEN_CONFIRM_TICKS = 1
    private const val TIGHTEN_CONFIRM_TICKS = 2

    // ── UNIT 2: control-loop responsiveness & stability (Axis 2) ─────────────────
    // Asymmetric reaction: fast DOWN on a genuine thermal/throttle signal, slow on
    // band-only noise. The dead-band / hysteresis below the kill stays as-is; this only
    // SPLITS the tighten confirm gate by urgency. SAFETY: every new clamp is a
    // strictly-safer ADDITIONAL upper bound; none bypasses [enforceInvariants] or the
    // 105°C thermal kill (which still force-reverts, untouched, BELOW this layer).

    /** FAST tighten regime: a genuine thermal/throttle signal acts THIS tick (no confirm). */
    private const val TIGHTEN_CONFIRM_TICKS_FAST = 0

    /** dTemp slope (°C/s) at/above which a band tighten is URGENT (die heating fast). */
    private const val FAST_TIGHTEN_DTEMP_C_PER_S = 2.0

    /** "Die within N°C of soft" window that also makes a band tighten urgent. */
    private const val FAST_TIGHTEN_NEAR_SOFT_C = 3

    /**
     * RATE-LIMITED multi-OPP swing (384 MHz-collapse guard). A single tick may step the
     * big cap DOWN by at most this many OPPs. ADDITIONAL upper bound applied BEFORE
     * [enforceInvariants] (which only ever RAISES the cap toward the 40% hard floor) — it
     * never bypasses any invariant, and the 105°C KILL is a separate force-revert path.
     */
    private const val MAX_CAP_STEP_PER_TICK = 2

    // ── Adaptive tick cadence (cheap, allocation-light — one Int on the decision) ──
    /** Default (calm) re-eval cadence: the existing 1 Hz. */
    private const val CALM_TICK_MS = 1000
    /** Faster (warming) re-eval cadence requested via [TdpDecision.nextTickHintMs]. */
    private const val FAST_TICK_MS = 500
    /**
     * Service-facing mirrors (same module). [AutoTdpService] seeds its decimation cadence
     * from [CALM_TICK_MS_DEFAULT] and clamps every honoured hint to [ADAPTIVE_TICK_FLOOR_MS]
     * — the hard 500 ms floor that keeps the loop from ever re-evaluating faster than 2 Hz.
     */
    internal const val CALM_TICK_MS_DEFAULT = CALM_TICK_MS
    internal const val ADAPTIVE_TICK_FLOOR_MS = FAST_TICK_MS
    /** dTemp slope (°C/s) at/above which the engine REQUESTS the faster re-eval tick. */
    private const val WARM_DTEMP_C_PER_S = 1.0
    /** "Within N°C of soft" window that also requests the [FAST_TICK_MS] cadence. */
    private const val NEAR_SOFT_FAST_TICK_C = 5

    // ── uclamp (top-app perf hint) constants — 0..1024 LAW ──────────────────────
    /** uclamp.min lower bound (no boost). */
    private const val UCLAMP_MIN = 0
    /** uclamp.min upper bound (full boost) per the kernel's 0–1024 scale. */
    private const val UCLAMP_MAX = 1024
    /** Per-step uclamp.min change — one OPP-equivalent nudge. */
    private const val UCLAMP_STEP = 128
    /** Neutral starting hint when no uclamp value has been set yet. The first
     *  tighten/loosen moves off this baseline. */
    private const val UCLAMP_NEUTRAL = 512

    /**
     * Synthetic max-draw reference (mW) for [deriveBudgetCap]. Peak big/prime draw
     * at the top OPP; 3 000 mW is a conservative SoC default. The EfficiencyCurve
     * finder replaces it with measured data once calibrated. Units: mW (so the
     * fraction in deriveBudgetCap is dimensionless mW/mW).
     */
    private const val MAX_DRAW_PROXY_MW = 3_000.0

    /**
     * CAP-FLOOR (8th invariant) — the HARD cap floor as a fraction of the cluster's
     * top OPP. The big-cluster cap may NEVER be tightened below this fraction of
     * cpuinfo_max, regardless of goal, classification, or signal state.
     *
     * This is the construction-level guarantee that the 384 MHz cap→floor collapse
     * (DEFECT A) can never recur: a null-GPU phantom-idle tick under the strongest
     * tighten goal (BATTERY_SAVER) can no longer walk the cap to the bottom OPP. The
     * cap stops at the first real OPP step at/above 40% of the top OPP.
     *
     * 40% is a CONSERVATIVE FAIL-SAFE: enough power reduction to matter for battery,
     * but well clear of the unusable bottom that pins the cluster and stutters the
     * game. Sanity checks on real OPP tables:
     *   - AYN Odin 3 little (policy0), top 3 532 800 kHz → floor ≈ 1 413 120 kHz;
     *     the snap lands on the 1 708 800 kHz step (first OPP ≥ 40%, ≈ 1.71 GHz).
     *   - AYN Odin 3 big/prime cluster scales the same way off its own top OPP.
     * The floor is always snapped to a REAL OPP via [hardCapFloorIndex], so MM-2 holds.
     *
     * SHARED (HIGH-2): the fraction is the single source of truth in
     * [io.github.mayusi.calibratesoc.data.thermal.CapFloor.HARD_FLOOR_FRACTION] so the
     * predictive throttle guard applies the IDENTICAL 40% floor on the same node. Value
     * and behaviour are unchanged from before — this only de-duplicates the constant.
     */
    private const val CAP_HARD_FLOOR_FRACTION =
        io.github.mayusi.calibratesoc.data.thermal.CapFloor.HARD_FLOOR_FRACTION

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Core decision function.
     *
     * Back-compat: the legacy 4-arg call `decide(window, config, caps, current)`
     * still compiles — [controllerState] and [goalOverride] default. The daemon
     * (this wave) calls it unchanged; later waves thread [controllerState] back.
     *
     * @param window          rolling window of recent [Telemetry] (~4–5 samples).
     * @param config          legacy profile + optional target-watts budget.
     * @param caps            immutable device envelope from [TdpCaps.from].
     * @param current         last-applied [TdpState] (the daemon's write target).
     * @param controllerState carried band/lever/cool-down/classifier state. The
     *                        daemon persists [TdpDecision.controllerState] and
     *                        passes it back next tick. Defaults to INITIAL.
     * @param goalOverride    explicit [GoalProfile] to use instead of mapping
     *                        [config]. Null → map from the legacy profile. (The
     *                        Wave-3 UI picker will pass a goal here.)
     */
    fun decide(
        window: List<Telemetry>,
        config: AutoTdpProfileConfig,
        caps: TdpCaps,
        current: TdpState,
        controllerState: ControllerState = ControllerState.INITIAL,
        goalOverride: GoalProfile? = null,
        // ── UNIT 1 (per-game learning) — both default null => cold-start identical ──
        seed: LearnedSeed? = null,
        sessionElapsedSec: Int? = null,
        // ── UNIT 4 (richer goal modes) — all default null/false => behaviour identical ──
        // The objective setpoints (fps floor / temp ceiling / runtime hours) live in
        // [goalParams]; [batteryPct]/[charging] feed the charge-aware AUTO gate. All
        // defaulted, so legacy callers and the existing 6 modes are byte-identical.
        goalParams: GoalParams? = null,
        batteryPct: Int? = null,
        charging: Boolean? = null,
    ): TdpDecision {
        if (window.isEmpty()) {
            // No window ⇒ no classification possible. Echo the requested goal as-is
            // (AUTO stays AUTO here — there is no signal to resolve it against; the
            // daemon surfaces "AUTO" honestly until the first sample arrives).
            return TdpDecision(
                current,
                "no telemetry — holding",
                HoldReason.NO_TELEMETRY,
                controllerState,
                resolvedGoal = goalOverride ?: GoalProfile.fromLegacyProfile(config.profile),
            )
        }

        // ── Signals ────────────────────────────────────────────────────────────
        val signals = computeSignals(window, caps, controllerState)

        // ── Resolve the active goal (AUTO → classifier; UNIT 4 objective modes) ───
        val requestedGoal = goalOverride ?: GoalProfile.fromLegacyProfile(config.profile)
        val classification = ContextClassifier.classify(
            window = window,
            smoothedGpuPct = signals.smoothedGpuPct,
            prior = controllerState.classifier,
        )
        // UNIT 4 — TARGET_FPS_FLOOR honest degrade: the fps-floor block only has meaning
        // with a REAL frame source. When the latest sample carries no measured FPS we
        // DEGRADE the goal to BALANCED_SMART here (in the goal-resolution region) so the
        // band controller never tightens against a fabricated FPS number, and we flag the
        // decision so the UI can show the "FPS floor needs a real frame-rate source;
        // using Balanced" banner. (Charging AUTO can also resolve TO an objective mode.)
        val hasRealFps = window.lastOrNull()?.isRealFps == true
        val fpsFloorDegraded = requestedGoal == GoalProfile.TARGET_FPS_FLOOR && !hasRealFps
        val activeGoal: GoalProfile = when {
            requestedGoal == GoalProfile.AUTO ->
                resolveAutoGoal(classification.context, signals.thermalTrend, batteryPct, charging)
            fpsFloorDegraded -> GoalProfile.BALANCED_SMART
            else -> requestedGoal
        }

        // Carry the freshly-advanced classifier state AND the updated EWMA
        // accumulators into every returned decision so the 1-pole filters persist
        // across ticks. (The direction handlers each re-apply classifier = state too;
        // they inherit the EWMA values from baseState.) [tick] is advanced once per
        // decision so the fan governor's rate-limit window counts real ticks.
        val baseState0 = controllerState.copy(
            classifier = classification.state,
            gpuEwma = signals.gpuEwmaRaw,
            dTempSlopeEwma = signals.dTempSlopeRaw ?: controllerState.dTempSlopeEwma,
            tick = controllerState.tick + 1,
        )

        // ── UNIT 1 (PER-GAME LEARNING): seed start cap + proactive pre-empt ────────
        // Additive + SAFETY-SUBORDINATE. Both inert when seed == null (cold start) so
        // behaviour is byte-identical to today. The seed only sets the STARTING cap; the
        // proactive arm can only TIGHTEN and is skipped whenever the real thermal pre-empt
        // is active (live heat always wins). 40% floor + reactive controller + thermal
        // kill all still run from tick 1. We SHADOW current/baseState so the unchanged
        // band/pre-empt when-block below transparently sees the seeded start state.
        val (current, baseState) = seedFromLearned(seed, current, caps, baseState0)
        // H-4 (honesty): on the tick the seed is FIRST applied, surface a LEARNED note in
        // the decision reason (modeled-not-measured — the UI shows a "LEARNED (n sessions)"
        // tier distinct from MEASURED). Null on every other tick → reason unchanged.
        val seedNote: String? = if (seed != null &&
            !baseState0.learnedSeedApplied && baseState.learnedSeedApplied) {
            "seeded from learned cap (${seed.sessionCount} sessions)"
        } else null
        if (!signals.thermalPreempt(activeGoal)) {
            val proactive = maybeProactivePreempt(
                seed, sessionElapsedSec, current, caps, config, activeGoal,
                signals, baseState, classification,
            )
            if (proactive != null) {
                // UNIT 2: stamp the adaptive cadence hint on the proactive-pre-empt path too.
                // UNIT 4: carry the fps-degrade flag so the UI banner is consistent here too.
                return applyFanGovernor(proactive, caps, activeGoal, signals)
                    .copy(
                        resolvedGoal = activeGoal,
                        nextTickHintMs = nextTickHint(signals, activeGoal),
                        fpsFloorDegraded = fpsFloorDegraded,
                    )
            }
        }

        // ── 1. THERMAL PRE-EMPT (highest priority, exempt from one-lever) ────────
        val raw = when {
            signals.thermalPreempt(activeGoal) ->
                preemptTighten(current, caps, config, activeGoal, signals, baseState, classification)

            // ── 2. CPU-SATURATION fast unpark — FP-1: TRUE-load gated ONLY ───────
            // A saturated big/prime core means "give the CPU headroom NOW". We honour
            // it as an immediate loosen, but ONLY when the load is real jiffie data.
            // Freq-proxy may flag the dimension (LOAD_BLIND) but must NEVER originate
            // this state change.
            signals.cpuSaturatedTrueLoad ->
                cpuRelax(current, caps, activeGoal, signals, baseState, classification)

            // ── 2.5 LOAD-BLIND HOLD (GUARDRAIL 3 — the FP-1 rule for the GPU dim) ─
            // If this tick has NEITHER true CPU jiffie load NOR a real GPU read, the
            // band controller is reasoning over a phantom (the null→0 GPU coercion that
            // floored the cap in DEFECT A). A blind tick must NEVER originate a tighten.
            // Hold safe. (Thermal pre-empt above already handled any genuine heat/throttle
            // signal; a real CPU-saturation loosen is gated on true load by arm 2.)
            signals.isLoadBlind ->
                bandHoldLoadBlind(current, activeGoal, signals, baseState, classification)

            // ── 3. BAND CONTROL: GPU busy% vs the active goal band ───────────────
            else -> {
                val band = bandFor(activeGoal)
                val gpu = signals.smoothedGpuPct
                when {
                    gpu > band.high -> bandLoosen(current, caps, activeGoal, signals, baseState, classification)
                    gpu < band.low -> bandTighten(current, caps, config, activeGoal, signals, baseState, classification)
                    else -> bandHold(current, activeGoal, signals, baseState, classification)
                }
            }
        }

        // ── 4. FAN GOVERNOR (orthogonal to the clock levers) ─────────────────────
        // The fan is driven by THERMAL state per goal, independent of which clock
        // lever moved this tick, and is rate-limited + hysteresis-gated + monotonic
        // so it never flaps. Applied last so it rides on whatever target the band
        // controller produced.
        //
        // resolvedGoal echo (Wave 4a): stamp the concrete goal this tick actually
        // followed (AUTO → the classifier's choice) onto the returned decision so the
        // daemon can surface activeGoal to the HUD without re-deriving it. Purely
        // informational — does not affect target/reason/controllerState.
        // UNIT 2 — ADAPTIVE CADENCE: stamp the re-eval hint for the NEXT tick (warming →
        // 500 ms, calm → 1000 ms). The daemon honours it with a 500 ms floor; the field
        // defaults to null on the no-telemetry early-return path so default behaviour holds.
        val finalDecision = applyFanGovernor(raw, caps, activeGoal, signals)
            .copy(
                resolvedGoal = activeGoal,
                nextTickHintMs = nextTickHint(signals, activeGoal),
                fpsFloorDegraded = fpsFloorDegraded,
            )
        return if (seedNote != null) {
            finalDecision.copy(reason = "${finalDecision.reason} [$seedNote]")
        } else {
            finalDecision
        }
    }

    /**
     * Post-process a [TdpDecision] with the fan governor: ask the active goal what
     * fan preset it wants for the smoothed die temp, run it through [FanGovernor]'s
     * rate-limit + hysteresis + monotonic gate, and fold the result into BOTH the
     * target [TdpState.fanMode] and the carried [FanGovernorState].
     *
     * When the device has no controllable fan ([TdpCaps.fanModeAvailable] == false)
     * or the goal doesn't drive the fan, the decision passes through untouched — we
     * never set a fan_mode we can't honestly write.
     */
    private fun applyFanGovernor(
        decision: TdpDecision,
        caps: TdpCaps,
        goal: GoalProfile,
        signals: WindowSignals,
    ): TdpDecision {
        if (!caps.fanModeAvailable) return decision
        val cs = decision.controllerState
        val desired = goal.fanModeFor(signals.smoothedDieTempC)
        val applied = FanGovernor.decide(
            desiredMode = desired,
            dieTempC = signals.smoothedDieTempC,
            tick = cs.tick,
            state = cs.fan,
        ) ?: return decision // governor held the fan this tick — no change

        // Emit the new preset on the target and record the change in carried state.
        return decision.copy(
            target = decision.target.copy(fanMode = applied),
            controllerState = cs.copy(
                fan = FanGovernor.recordChange(applied, signals.smoothedDieTempC, cs.tick),
            ),
        )
    }

    // ── Goal band ───────────────────────────────────────────────────────────────

    private data class Band(val low: Int, val high: Int)

    private fun bandFor(goal: GoalProfile) = Band(goal.gpuBandLowPct, goal.gpuBandHighPct)

    // ── UNIT 4: charge-aware AUTO resolution ─────────────────────────────────────

    /** Battery-% thresholds for the charge-aware AUTO gate (LAW). */
    private const val AUTO_BATTERY_SAVER_PCT = 30 // ≤30% (and >15) → BATTERY_SAVER
    private const val AUTO_RUNTIME_PCT = 15       // <15% → TARGET_RUNTIME (finish the session)

    /**
     * UNIT 4 — the charge-aware AUTO resolution. AUTO gains an OUTER charge/level gate
     * around the existing context classifier; the classifier's inner branches are
     * UNCHANGED (this still calls the committed [ContextClassifier.goalFor]):
     *
     *   - charging (any %)            → MAX_FPS   (plugged in: spend freely for smoothness)
     *   - unplugged, battery > 30%    → the classifier path (LIGHT→BALANCED,
     *                                    HEAVY+rising→COOL_QUIET, etc.) — UNCHANGED
     *   - unplugged, 15%..30%         → BATTERY_SAVER (start conserving)
     *   - unplugged, < 15%            → TARGET_RUNTIME (stretch what's left to finish)
     *
     * When the battery level / charging state is unknown (null) we fall through to the
     * classifier path — the same behaviour as before this gate existed (no new guess).
     * PURE: scalar comparisons + the committed classifier call; no I/O, no allocations.
     */
    private fun resolveAutoGoal(
        context: WorkloadContext,
        thermalTrend: ThermalTrend,
        batteryPct: Int?,
        charging: Boolean?,
    ): GoalProfile {
        // Charging (any level): plugged in → favour smoothness, the classifier path is moot.
        if (charging == true) return GoalProfile.MAX_FPS
        // Unknown battery level: behave exactly as the pre-gate classifier path.
        val pct = batteryPct ?: return ContextClassifier.goalFor(context, thermalTrend)
        return when {
            pct < AUTO_RUNTIME_PCT -> GoalProfile.TARGET_RUNTIME
            pct <= AUTO_BATTERY_SAVER_PCT -> GoalProfile.BATTERY_SAVER
            else -> ContextClassifier.goalFor(context, thermalTrend)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  UNIT 1 — PER-GAME LEARNING (seed start cap + proactive pre-empt)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The fraction of the learned throttle-onset time at which the ONE proactive
     * tighten arms — fire BEFORE the die reaches soft temp so the device never has to
     * actually throttle for a game whose onset we've learned. 0.85 leaves a small margin
     * of error below the historical onset.
     */
    private const val PROACTIVE_ONSET_FRACTION = 0.85

    /**
     * Upper bound (× learned onset) past which the proactive arm is no longer eligible.
     * If we somehow blew past the onset window without arming (e.g. the session clock
     * jumped), do NOT fire a stale proactive tighten — the live reactive controller +
     * thermal pre-empt own that regime. Keeps the proactive arm a tight one-shot.
     */
    private const val PROACTIVE_ONSET_UPPER_FRACTION = 1.10

    /**
     * UNIT 1: seed the STARTING big-cluster cap from the learned [seed], ONCE per session.
     *
     * Returns the (possibly re-capped) [current] state and the (possibly latched) [base]
     * controller state. When seeding does not apply — no seed, no learned cap, or the
     * seed was already applied this session ([ControllerState.learnedSeedApplied]) — both
     * are returned UNCHANGED, so a cold start (seed == null) is byte-identical to today.
     *
     * SAFETY: the seed cap is snapped to a REAL OPP and clamped to the 40% hard floor via
     * [CapFloor.snapCapToOpp]; if it lands at/above the top OPP the cap is cleared to null
     * (stock — "no cap needed"). The result is passed through [enforceInvariants] so MM-1
     * / MM-2 / the CAP-FLOOR invariant all hold on the seeded state. The seed ONLY sets
     * the starting operating point — it skips the slow reactive walk-down but can never
     * push below the floor or disable any live safety path (pre-empt / kill run from tick 1).
     */
    private fun seedFromLearned(
        seed: LearnedSeed?,
        current: TdpState,
        caps: TdpCaps,
        base: ControllerState,
    ): Pair<TdpState, ControllerState> {
        if (seed == null || base.learnedSeedApplied) return current to base
        val learnedCap = seed.safeSustainedCapKhz ?: return current to base
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty()) {
            // No OPP table to snap to — refuse to seed an off-table cap; latch so we
            // don't retry every tick. The reactive controller proceeds from stock.
            return current to base.copy(learnedSeedApplied = true)
        }
        // Snap to a real OPP AND raise to the 40% hard floor (SAFETY). A value at/above
        // the top OPP means "no cap" -> clear to null (never hold a redundant top-OPP cap).
        val snapped = CapFloor.snapCapToOpp(learnedCap, steps)
        val topOpp = steps.last()
        val seededCap: Int? = if (snapped >= topOpp) null else snapped
        // Only re-seed the cap lever; leave every other actuator at its current value so
        // the seed sets the operating POINT, not a whole tuned state.
        val seededState = enforceInvariants(
            current.copy(bigClusterCapKhz = seededCap),
            caps,
            // Goal is irrelevant to the cap-floor/MM invariants we rely on here; pass the
            // DEFAULT so ACT-2 (park/uclamp) leaves the untouched actuators alone.
            GoalProfile.DEFAULT,
        )
        return seededState to base.copy(learnedSeedApplied = true)
    }

    /**
     * UNIT 1: the ONE proactive thermal tighten, armed near 0.85 × the learned onset.
     *
     * Returns a [TdpDecision] that steps the big-cluster cap DOWN one notch (never below
     * the 40% hard floor) BEFORE the die reaches soft temp, or null when not eligible.
     *
     * Eligibility (ALL required):
     *   - a learned [LearnedSeed.throttleOnsetSec] exists (we know WHEN this game throttles),
     *   - a session clock ([sessionElapsedSec]) is available,
     *   - the proactive arm has not already fired this session ([proactivePreemptArmed]),
     *   - the session clock is within the arm window:
     *     0.85 × onset <= elapsed <= 1.10 × onset (a tight one-shot, never stale).
     *
     * The caller already guarantees the live thermal pre-empt is NOT active this tick
     * (genuine heat takes the real pre-empt path), so this only ever fires PROACTIVELY.
     * It can ONLY TIGHTEN (one cap notch); the normal band controller loosens it back
     * over subsequent ticks if the workload turns out not to need it. The latch makes it
     * fire at most once per onset window.
     */
    private fun maybeProactivePreempt(
        seed: LearnedSeed?,
        sessionElapsedSec: Int?,
        current: TdpState,
        caps: TdpCaps,
        config: AutoTdpProfileConfig,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision? {
        val onset = seed?.throttleOnsetSec ?: return null
        val elapsed = sessionElapsedSec ?: return null
        if (base.proactivePreemptArmed) return null
        if (onset <= 0) return null
        val armAt = onset * PROACTIVE_ONSET_FRACTION
        val armUntil = onset * PROACTIVE_ONSET_UPPER_FRACTION
        if (elapsed < armAt || elapsed > armUntil) return null

        // ONE cap notch down, respecting the budget floor (hard-ceiling goals) AND the
        // 40% hard floor inside stepCapDown. If the cap can't move (already at/below the
        // effective floor) we still latch the arm so we don't re-evaluate every tick.
        val capFloorKhz: Int? = if (goal.hasHardPowerCeiling) {
            deriveBudgetCap(caps, config.targetMilliWatts ?: 0)
        } else null
        val stepped = stepCapDown(current.bigClusterCapKhz, caps, capFloorKhz)
        val next = enforceInvariants(
            if (stepped.changed) current.copy(bigClusterCapKhz = stepped.cap) else current,
            caps,
            goal,
        )
        val acted = next != current
        // Latch the arm (one-shot) and record a tighten so the next loosen still honours
        // the cross-actuator cool-down — mirrors preemptTighten's bookkeeping.
        val newState = base.copy(
            classifier = classification.state,
            proactivePreemptArmed = true,
            currentDirection = Direction.TIGHTEN,
            confirmTicks = TIGHTEN_CONFIRM_TICKS,
            quietTicks = 0,
            lastActedDirection = Direction.TIGHTEN,
            activeLever = null,
        )
        val note = if (acted) {
            "proactive: learned onset ~${onset}s — tighten early (cap -> ${next.bigClusterCapKhz?.div(1000)} MHz)"
        } else {
            "proactive: learned onset ~${onset}s — already at floor"
        }
        return TdpDecision(
            next,
            reason("proactive pre-empt -> tighten", goal, note),
            HoldReason.GPU_BOUND_CAPPING,
            newState,
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  DIRECTION HANDLERS
    // ════════════════════════════════════════════════════════════════════════════

    // ── LOOSEN ────────────────────────────────────────────────────────────────────
    // Loosen acts on 1 confirming tick. Cross-actuator cool-down: a loosen right
    // after a tighten needs 3 quiet ticks (K_TIGHTEN_TO_LOOSEN). One lever per
    // episode: ride GPU-floor → cap → unpark in fixed priority (Wave 2 inserts
    // min-freq before cap and uclamp after unpark).

    private fun bandLoosen(
        current: TdpState,
        caps: TdpCaps,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision {
        // Confirm timing.
        val confirm = base.advanceConfirm(Direction.LOOSEN)
        if (confirm.confirmTicks < LOOSEN_CONFIRM_TICKS) {
            return hold(
                current, goal, signals, confirm, classification,
                HoldReason.IDLE_HOLDING,
                "confirming loosen toward GPU ${goal.gpuBandLowPct}-${goal.gpuBandHighPct}%",
            )
        }
        // Cross-actuator cool-down: tighten→loosen needs K quiet ticks.
        val needed = coolDownNeeded(base.lastActedDirection, Direction.LOOSEN)
        if (base.quietTicks < needed) {
            val cooled = confirm.copy(quietTicks = base.quietTicks + 1)
            return hold(
                current, goal, signals, cooled, classification,
                HoldReason.IDLE_HOLDING,
                "cool-down before loosen (${base.quietTicks + 1}/$needed)",
            )
        }

        // Apply ONE lever in this goal's loosen priority order (ACT-1).
        val (next0, leverNote, lever) = applyLoosenLever(current, caps, goal, base.activeLever)
        val next = enforceInvariants(next0, caps, goal)
        val acted = next != current
        val newState = base
            .copy(classifier = classification.state)
            .recordAction(Direction.LOOSEN, lever, didAct = acted)
        return TdpDecision(
            next,
            reason("loosen", goal, leverNote),
            HoldReason.CPU_BOUND_RELAXING,
            newState,
        )
    }

    /**
     * Apply ONE lever in this goal's loosen priority ([GoalProfile.loosenLeverOrder]).
     * Carries the active lever until it saturates (ACT-1). Returns the new state, a
     * config-only note, and the lever that acted.
     *
     * Levers whose actuator is unavailable on the device (no devfreq table, no
     * uclamp node, no min-freq floor envelope) are SKIPPED honestly — the loop
     * advances to the next lever rather than faking a write.
     */
    private fun applyLoosenLever(
        current: TdpState,
        caps: TdpCaps,
        goal: GoalProfile,
        activeLever: Lever?,
    ): Triple<TdpState, String, Lever> {
        val order = goal.loosenLeverOrder
        // Resume the active lever if it can still move; else walk priority order.
        val ordered = if (activeLever != null && activeLever in order) {
            listOf(activeLever) + order.filter { it != activeLever }
        } else order

        for (lever in ordered) {
            when (lever) {
                Lever.GPU_FLOOR -> {
                    // Loosen GPU = make it more permissive (lower level index toward min).
                    val min = caps.gpuMinLevel
                    if (min != null && current.gpuFloorLevel != null && current.gpuFloorLevel > min) {
                        return Triple(
                            current.copy(gpuFloorLevel = (current.gpuFloorLevel - 1).coerceAtLeast(min)),
                            "GPU floor → faster level",
                            Lever.GPU_FLOOR,
                        )
                    }
                }
                Lever.GPU_DEVFREQ -> {
                    // Loosen GPU devfreq = raise the max toward the ceiling (give the
                    // GPU more room). Finer than the pwrlevel. Skipped when no table.
                    val stepped = stepDevfreqMaxUp(current.gpuDevfreqMaxHz, caps)
                    if (stepped.changed) {
                        return Triple(
                            current.copy(gpuDevfreqMaxHz = stepped.freq),
                            if (stepped.freq == null) "GPU devfreq max → stock" else "GPU devfreq max → ${stepped.freq / 1_000_000} MHz",
                            Lever.GPU_DEVFREQ,
                        )
                    }
                }
                Lever.MIN_FREQ_FLOOR -> {
                    // Loosen the floor = RAISE it one OPP (a higher floor stops
                    // down-clock dips → smoother). MM-1/MM-2 enforced after.
                    val stepped = stepMinFloorUp(current, caps)
                    if (stepped.changed) {
                        return Triple(
                            current.copy(bigClusterMinKhz = stepped.cap),
                            "big min floor → ${stepped.cap?.div(1000)} MHz",
                            Lever.MIN_FREQ_FLOOR,
                        )
                    }
                }
                Lever.CAP -> {
                    val stepped = stepCapUp(current.bigClusterCapKhz, caps)
                    if (stepped.changed) {
                        return Triple(
                            current.copy(bigClusterCapKhz = stepped.cap),
                            if (stepped.cap == null) "big cap → stock" else "big cap → ${stepped.cap / 1000} MHz",
                            Lever.CAP,
                        )
                    }
                }
                Lever.UNPARK -> {
                    if (current.parkedPrimeCores.isNotEmpty()) {
                        val toUnpark = current.parkedPrimeCores.min()
                        return Triple(
                            current.copy(parkedPrimeCores = current.parkedPrimeCores - toUnpark),
                            "unpark cpu$toUnpark",
                            Lever.UNPARK,
                        )
                    }
                }
                Lever.UCLAMP -> {
                    // Loosen uclamp = RAISE the top-app perf hint toward max (1024).
                    // ACT-2: only when this goal uses uclamp AND the node exists.
                    if (goal.usesUclampNotPark && caps.uclampAvailable) {
                        val cur = current.uclampTopAppMin ?: UCLAMP_NEUTRAL
                        if (cur < UCLAMP_MAX) {
                            return Triple(
                                current.copy(uclampTopAppMin = (cur + UCLAMP_STEP).coerceAtMost(UCLAMP_MAX)),
                                "top-app uclamp.min → ${(cur + UCLAMP_STEP).coerceAtMost(UCLAMP_MAX)}",
                                Lever.UCLAMP,
                            )
                        }
                    }
                }
                else -> { /* park/cap-down etc. are not loosen levers */ }
            }
        }
        // Fully loosened — nothing left to relax.
        return Triple(current, "already at stock (nothing to loosen)", Lever.CAP)
    }

    // ── TIGHTEN ────────────────────────────────────────────────────────────────────
    // Tighten acts on 2 confirming ticks. Cool-down: tighten→tighten K=1,
    // loosen→tighten K=2. One lever per episode: cap → GPU-floor → park (Wave 2
    // inserts min-freq after cap and uclamp after park).

    private fun bandTighten(
        current: TdpState,
        caps: TdpCaps,
        config: AutoTdpProfileConfig,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision {
        // UNIT 2 — ASYMMETRIC REACTION: a tighten driven by a genuine thermal/throttle
        // signal acts THIS tick (FAST, 0 confirm); a band-only tighten on calm thermals
        // keeps the 2-tick confirm so noisy GPU% can never hunt the cap. LOOSEN is
        // untouched (still 1 confirm) — the asymmetry is fast-down / slow-up.
        val urgency = classifyTightenUrgency(signals, goal)
        val confirmTarget = when (urgency) {
            TightenUrgency.FAST -> TIGHTEN_CONFIRM_TICKS_FAST
            TightenUrgency.BAND -> TIGHTEN_CONFIRM_TICKS
        }
        val confirm = base.advanceConfirm(Direction.TIGHTEN)
        if (confirm.confirmTicks < confirmTarget) {
            return hold(
                current, goal, signals, confirm, classification,
                HoldReason.IDLE_HOLDING,
                "confirming tighten toward GPU ${goal.gpuBandLowPct}-${goal.gpuBandHighPct}%",
            )
        }
        val needed = coolDownNeeded(base.lastActedDirection, Direction.TIGHTEN)
        if (base.quietTicks < needed) {
            val cooled = confirm.copy(quietTicks = base.quietTicks + 1)
            return hold(
                current, goal, signals, cooled, classification,
                HoldReason.IDLE_HOLDING,
                "cool-down before tighten (${base.quietTicks + 1}/$needed)",
            )
        }

        val (next0, leverNote, lever) = applyTightenLever(current, caps, config, goal, base.activeLever)
        // UNIT 2 — RATE-LIMITED multi-OPP swing: clamp the cap so it falls by at most
        // MAX_CAP_STEP_PER_TICK OPPs this tick. Strictly-safer ADDITIONAL upper bound — it
        // can only RAISE the post-lever cap back up, never push it lower — applied BEFORE
        // enforceInvariants (which then applies the 40% hard floor on top). The current band
        // path only steps one OPP, so this is defence-in-depth against any future lever
        // computing a larger jump; the 105°C kill path is separate + unaffected.
        val rateLimited = clampCapStep(from = current, to = next0, caps = caps)
        val next = enforceInvariants(rateLimited, caps, goal)
        val acted = next != current
        val newState = base
            .copy(classifier = classification.state)
            .recordAction(Direction.TIGHTEN, lever, didAct = acted)
        return TdpDecision(
            next,
            reason("tighten", goal, leverNote),
            HoldReason.GPU_BOUND_CAPPING,
            newState,
        )
    }

    /**
     * Apply ONE lever in this goal's tighten priority ([GoalProfile.tightenLeverOrder]).
     * Carries the active lever until it saturates (ACT-1).
     *
     * MM-1 / MM-2 hold because the cap and min-floor only ever move between real OPP
     * steps and [enforceInvariants] lowers the floor in lockstep if a cap step would
     * invert it. ACT-2 holds because PARK and UCLAMP are mutually exclusive per goal
     * ([GoalProfile.usesUclampNotPark]) and unavailable actuators are skipped.
     */
    private fun applyTightenLever(
        current: TdpState,
        caps: TdpCaps,
        config: AutoTdpProfileConfig,
        goal: GoalProfile,
        activeLever: Lever?,
    ): Triple<TdpState, String, Lever> {
        val order = goal.tightenLeverOrder
        val ordered = if (activeLever != null && activeLever in order) {
            listOf(activeLever) + order.filter { it != activeLever }
        } else order

        // For a hard-ceiling goal (BATTERY_SAVER / BATTERY_TARGET back-compat) the
        // cap floor is the watts-budget step, not the bottom OPP.
        val capFloorKhz: Int? = if (goal.hasHardPowerCeiling) {
            deriveBudgetCap(caps, config.targetMilliWatts ?: 0)
        } else null

        for (lever in ordered) {
            when (lever) {
                Lever.CAP -> {
                    val stepped = stepCapDown(current.bigClusterCapKhz, caps, capFloorKhz)
                    if (stepped.changed) {
                        return Triple(
                            current.copy(bigClusterCapKhz = stepped.cap),
                            "big cap → ${stepped.cap?.div(1000)} MHz",
                            Lever.CAP,
                        )
                    }
                }
                Lever.MIN_FREQ_FLOOR -> {
                    // Tighten the floor = LOWER it one OPP (let the cluster idle deeper
                    // for power). Never below the bottom OPP; MM-1 keeps it < cap.
                    val stepped = stepMinFloorDown(current, caps)
                    if (stepped.changed) {
                        return Triple(
                            current.copy(bigClusterMinKhz = stepped.cap),
                            if (stepped.cap == null) "big min floor → stock" else "big min floor → ${stepped.cap / 1000} MHz",
                            Lever.MIN_FREQ_FLOOR,
                        )
                    }
                }
                Lever.GPU_DEVFREQ -> {
                    // Tighten GPU devfreq = lower the max toward the floor (less GPU).
                    val stepped = stepDevfreqMaxDown(current.gpuDevfreqMaxHz, caps)
                    if (stepped.changed) {
                        return Triple(
                            current.copy(gpuDevfreqMaxHz = stepped.freq),
                            "GPU devfreq max → ${stepped.freq?.div(1_000_000)} MHz",
                            Lever.GPU_DEVFREQ,
                        )
                    }
                }
                Lever.GPU_FLOOR -> {
                    // Tighten GPU = make it LESS permissive (raise level index toward max).
                    val max = caps.gpuMaxLevel
                    val min = caps.gpuMinLevel
                    if (max != null) {
                        val curLevel = current.gpuFloorLevel ?: min ?: 0
                        if (curLevel < max) {
                            return Triple(
                                current.copy(gpuFloorLevel = (curLevel + 1).coerceAtMost(max)),
                                "GPU floor → slower level",
                                Lever.GPU_FLOOR,
                            )
                        }
                    }
                }
                Lever.PARK -> {
                    // ACT-2: a goal that uses uclamp NEVER parks. Skip to UCLAMP.
                    if (!goal.usesUclampNotPark) {
                        val parked = parkOneMore(current, caps)
                        if (parked != null) {
                            return Triple(
                                current.copy(parkedPrimeCores = current.parkedPrimeCores + parked),
                                "park cpu$parked",
                                Lever.PARK,
                            )
                        }
                    }
                }
                Lever.UCLAMP -> {
                    // Tighten uclamp = LOWER the top-app perf hint (give back headroom).
                    // ACT-2: only when this goal uses uclamp AND the node exists.
                    if (goal.usesUclampNotPark && caps.uclampAvailable) {
                        val cur = current.uclampTopAppMin ?: UCLAMP_NEUTRAL
                        if (cur > UCLAMP_MIN) {
                            return Triple(
                                current.copy(uclampTopAppMin = (cur - UCLAMP_STEP).coerceAtLeast(UCLAMP_MIN)),
                                "top-app uclamp.min → ${(cur - UCLAMP_STEP).coerceAtLeast(UCLAMP_MIN)}",
                                Lever.UCLAMP,
                            )
                        }
                    }
                }
                else -> { /* unpark is not a tighten lever */ }
            }
        }
        return Triple(current, "at tighten floor (nothing left to tighten)", Lever.CAP)
    }

    // ── UNIT 2: tighten urgency (asymmetric fast-down) ───────────────────────────

    /**
     * How urgently a band TIGHTEN should land. [FAST] = a genuine thermal/throttle signal,
     * act THIS tick (no confirm) — reacting to real heat in 1 tick is the point; the
     * multi-tick confirm exists only to ride out band-only GPU% noise, which a hot /
     * fast-heating / kernel-throttling die is NOT. [BAND] = a band-only tighten on calm
     * thermals — keep the conservative 2-tick confirm so noisy GPU% can never hunt the cap.
     */
    private enum class TightenUrgency { FAST, BAND }

    /**
     * Split a band tighten into FAST (thermal/throttle) vs BAND (calm GPU%). FAST fires on
     * ANY (OR) of the genuine heat/throttle signals, all strictly BELOW the 105°C kill
     * (a separate force-revert path never replaced here):
     *   1. smoothed die ≥ soft − [FAST_TIGHTEN_NEAR_SOFT_C] (essentially at soft);
     *   2. dTemp slope ≥ [FAST_TIGHTEN_DTEMP_C_PER_S] °C/s (heating fast); OR
     *   3. any cooling_device cur_state > 0 (kernel throttling NOW).
     * This NEVER originates a tighten — the band edge (gpu < band.low) already decided to
     * tighten; urgency only chooses the confirm CADENCE (1 tick vs 2).
     *
     * NOTE: the design also lists "realFps < goal floor" as a 4th FAST trigger, but the
     * existing contract consumes real FPS ONLY as a DON'T-tighten floor (ContextClassifier
     * §4) and no concrete per-goal FPS floor exists. Wiring an FPS-driven FAST tighten would
     * invert that documented semantic and invent a number, so it is deliberately deferred —
     * the three thermal/throttle arms are the unambiguous, SAFETY-preserving urgency signals.
     * Allocation-light: pure scalars, no collections.
     */
    private fun classifyTightenUrgency(signals: WindowSignals, goal: GoalProfile): TightenUrgency {
        val soft = goal.softDieTempC
        val die = signals.smoothedDieTempC
        val nearSoft = die != null && die >= soft - FAST_TIGHTEN_NEAR_SOFT_C
        val heatingFast = signals.dTempSlopeCPerS?.let { it >= FAST_TIGHTEN_DTEMP_C_PER_S } ?: false
        val throttlingNow = (signals.coolingMaxState ?: 0) > 0
        return if (nearSoft || heatingFast || throttlingNow) TightenUrgency.FAST else TightenUrgency.BAND
    }

    /**
     * UNIT 2 — RATE-LIMITED multi-OPP swing. Clamp [to]'s big cap so it falls by at most
     * [MAX_CAP_STEP_PER_TICK] OPP steps below [from]'s cap this tick. One-directional UPPER
     * bound: it can only RAISE a too-low proposed cap back up, never lower a cap — strictly
     * safer, never bypasses the CAP-FLOOR / MM / thermal-kill invariants (those run AFTER in
     * [enforceInvariants] and on the kill path). Returns [to] unchanged when the cap rose,
     * held, or fell by ≤ the limit, or when the OPP table is degenerate. Allocation-light:
     * OPP-index integer arithmetic only.
     */
    private fun clampCapStep(from: TdpState, to: TdpState, caps: TdpCaps): TdpState {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.size < 2) return to
        val fromIdx = capIndex(from.bigClusterCapKhz, steps)
        val toIdx = capIndex(to.bigClusterCapKhz, steps)
        val fall = fromIdx - toIdx // > 0 ⇒ the cap dropped this many OPPs
        if (fall <= MAX_CAP_STEP_PER_TICK) return to // rose, held, or within the per-tick limit
        val clampedIdx = (fromIdx - MAX_CAP_STEP_PER_TICK).coerceIn(0, steps.lastIndex)
        return to.copy(bigClusterCapKhz = steps[clampedIdx])
    }

    // ── UNIT 2: adaptive tick cadence ────────────────────────────────────────────

    /**
     * The re-eval cadence (ms) the engine REQUESTS for the NEXT tick, surfaced on
     * [TdpDecision.nextTickHintMs]. The daemon honours it by gating how often it processes
     * the 1 Hz telemetry stream (with a hard [FAST_TICK_MS] floor) — no new threads, no
     * faster polling; the shared MonitorService stays 1 Hz.
     *   - WARMING → [FAST_TICK_MS]: die heating (slope ≥ [WARM_DTEMP_C_PER_S]) OR within
     *     [NEAR_SOFT_FAST_TICK_C] of the goal's soft target. React sooner.
     *   - CALM → [CALM_TICK_MS] (the default 1 Hz).
     * Allocation-light: pure scalar comparisons returning one Int. SAFETY: floored at
     * [FAST_TICK_MS] so the cadence can never request faster than 2 Hz (battery safety).
     */
    private fun nextTickHint(signals: WindowSignals, goal: GoalProfile): Int {
        val die = signals.smoothedDieTempC
        val slope = signals.dTempSlopeCPerS
        val heating = slope != null && slope >= WARM_DTEMP_C_PER_S
        val nearSoft = die != null && die >= goal.softDieTempC - NEAR_SOFT_FAST_TICK_C
        return if (heating || nearSoft) FAST_TICK_MS else CALM_TICK_MS
    }

    // ── HOLD ─────────────────────────────────────────────────────────────────────

    private fun bandHold(
        current: TdpState,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision {
        // Inside the dead-band: end any direction-episode and accrue a quiet tick.
        val held = base.copy(
            classifier = classification.state,
            currentDirection = null,
            confirmTicks = 0,
            quietTicks = (base.quietTicks + 1).coerceAtMost(QUIET_TICK_CAP),
            activeLever = null,
        )
        // FP-1 honesty: when the CPU dimension is proxy-only/blind we MUST NOT claim
        // "idle" — surface LOAD_BLIND_HOLDING with an honest note instead.
        return if (signals.cpuProxyOnly) {
            TdpDecision(
                current,
                reason("holding (in band)", goal, "CPU load unreadable"),
                HoldReason.LOAD_BLIND_HOLDING,
                held,
            )
        } else {
            TdpDecision(
                current,
                reason("holding (in band)", goal, "GPU within ${goal.gpuBandLowPct}-${goal.gpuBandHighPct}%"),
                HoldReason.IDLE_HOLDING,
                held,
            )
        }
    }

    /**
     * GUARDRAIL 3 hold: the tick is LOAD-BLIND (no true CPU load AND no real GPU read).
     * Hold the current state and end any in-progress direction episode so a phantom
     * blind tick can never accumulate confirm ticks toward a tighten. Emits
     * [HoldReason.LOAD_BLIND_HOLDING] with an honest note — the controller is holding
     * because the signals it would act on are unavailable, NOT because the device is idle.
     */
    private fun bandHoldLoadBlind(
        current: TdpState,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision {
        val held = base.copy(
            classifier = classification.state,
            currentDirection = null,
            confirmTicks = 0,
            quietTicks = (base.quietTicks + 1).coerceAtMost(QUIET_TICK_CAP),
            activeLever = null,
        )
        return TdpDecision(
            current,
            reason("holding", goal, "LOAD_BLIND_HOLDING — signals unavailable, holding safe"),
            HoldReason.LOAD_BLIND_HOLDING,
            held,
        )
    }

    /** Generic hold that preserves a pending confirm/cool-down state. */
    private fun hold(
        current: TdpState,
        goal: GoalProfile,
        signals: WindowSignals,
        state: ControllerState,
        classification: ClassificationResult,
        holdReason: HoldReason,
        note: String,
    ): TdpDecision {
        val withClassifier = state.copy(classifier = classification.state)
        // FP-1: if the CPU dimension is proxy-only, surface LOAD_BLIND honestly even
        // while holding for timing — but keep the GPU-driven note.
        val effectiveHold = if (signals.cpuProxyOnly && holdReason == HoldReason.IDLE_HOLDING) {
            HoldReason.LOAD_BLIND_HOLDING
        } else holdReason
        return TdpDecision(
            current,
            reason("holding", goal, note),
            effectiveHold,
            withClassifier,
        )
    }

    // ── CPU saturation relax (TRUE-load only — FP-1) ─────────────────────────────

    private fun cpuRelax(
        current: TdpState,
        caps: TdpCaps,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision {
        // A SATURATED CPU core needs CPU headroom NOW — so this relax prioritises the
        // CPU-headroom lever directly (unpark a parked core, or raise the uclamp hint
        // for a uclamp goal) rather than walking the GPU-biased band-loosen order. Only
        // when there is no CPU-headroom move left does it fall back to the general
        // loosen order (relaxing the cap/GPU still indirectly helps a saturated CPU).
        val (next0, leverNote, lever) = applyCpuRelaxLever(current, caps, goal)
            ?: applyLoosenLever(current, caps, goal, base.activeLever)
        val next = enforceInvariants(next0, caps, goal)
        val acted = next != current
        val newState = base
            .copy(classifier = classification.state)
            .recordAction(Direction.LOOSEN, lever, didAct = acted)
        return TdpDecision(
            next,
            reason("cpu-saturated → relax", goal, leverNote),
            HoldReason.CPU_BOUND_RELAXING,
            newState,
        )
    }

    /**
     * The CPU-headroom lever for a saturation relax: unpark a parked prime core (a
     * parking goal) or raise the top-app uclamp hint (a uclamp goal). Returns null
     * when no CPU-headroom move is available, so [cpuRelax] falls back to the general
     * loosen order. ACT-2 holds because the choice keys off [GoalProfile.usesUclampNotPark].
     */
    private fun applyCpuRelaxLever(
        current: TdpState,
        caps: TdpCaps,
        goal: GoalProfile,
    ): Triple<TdpState, String, Lever>? {
        if (goal.usesUclampNotPark) {
            if (caps.uclampAvailable) {
                val cur = current.uclampTopAppMin ?: UCLAMP_NEUTRAL
                if (cur < UCLAMP_MAX) {
                    return Triple(
                        current.copy(uclampTopAppMin = (cur + UCLAMP_STEP).coerceAtMost(UCLAMP_MAX)),
                        "top-app uclamp.min → ${(cur + UCLAMP_STEP).coerceAtMost(UCLAMP_MAX)}",
                        Lever.UCLAMP,
                    )
                }
            }
        } else {
            if (current.parkedPrimeCores.isNotEmpty()) {
                val toUnpark = current.parkedPrimeCores.min()
                return Triple(
                    current.copy(parkedPrimeCores = current.parkedPrimeCores - toUnpark),
                    "unpark cpu$toUnpark",
                    Lever.UNPARK,
                )
            }
        }
        return null
    }

    // ── THERMAL PRE-EMPT (exempt from one-lever) ─────────────────────────────────

    private fun preemptTighten(
        current: TdpState,
        caps: TdpCaps,
        config: AutoTdpProfileConfig,
        goal: GoalProfile,
        signals: WindowSignals,
        base: ControllerState,
        classification: ClassificationResult,
    ): TdpDecision {
        // Pre-emptive tighten may move cap AND GPU-floor together (safety > smoothness).
        var next = current
        val notes = mutableListOf<String>()

        val capFloorKhz: Int? = if (goal.hasHardPowerCeiling) {
            deriveBudgetCap(caps, config.targetMilliWatts ?: 0)
        } else null

        val cap = stepCapDown(next.bigClusterCapKhz, caps, capFloorKhz)
        if (cap.changed) {
            next = next.copy(bigClusterCapKhz = cap.cap)
            notes += "big cap → ${cap.cap?.div(1000)} MHz"
        }
        val max = caps.gpuMaxLevel
        if (max != null) {
            val curLevel = next.gpuFloorLevel ?: caps.gpuMinLevel ?: 0
            if (curLevel < max) {
                next = next.copy(gpuFloorLevel = (curLevel + 1).coerceAtMost(max))
                notes += "GPU floor → slower level"
            }
        }
        if (notes.isEmpty()) {
            // Nothing left to tighten on cap/GPU — drop the CPU headroom lever as a
            // last resort. ACT-2: a uclamp goal lowers the hint; everyone else parks.
            if (goal.usesUclampNotPark) {
                if (caps.uclampAvailable) {
                    val cur = next.uclampTopAppMin ?: UCLAMP_NEUTRAL
                    if (cur > UCLAMP_MIN) {
                        next = next.copy(uclampTopAppMin = (cur - UCLAMP_STEP).coerceAtLeast(UCLAMP_MIN))
                        notes += "top-app uclamp.min → ${next.uclampTopAppMin}"
                    } else notes += "at tighten floor"
                } else notes += "at tighten floor"
            } else {
                val parked = parkOneMore(next, caps)
                if (parked != null) {
                    next = next.copy(parkedPrimeCores = next.parkedPrimeCores + parked)
                    notes += "park cpu$parked"
                } else {
                    notes += "at tighten floor"
                }
            }
        }

        // Enforce all invariants (MM-1 min<cap lockstep, devfreq bounds, ACT-2) on
        // the multi-lever pre-empt state before it is emitted.
        next = enforceInvariants(next, caps, goal)

        // Pre-empt resets the lever episode (it acted across levers) and bypasses
        // cool-down; record a tighten so the NEXT loosen still honours the 3-tick gap.
        val newState = base.copy(
            classifier = classification.state,
            currentDirection = Direction.TIGHTEN,
            confirmTicks = TIGHTEN_CONFIRM_TICKS,
            quietTicks = 0,
            lastActedDirection = Direction.TIGHTEN,
            activeLever = null,
        )
        return TdpDecision(
            next,
            reason("thermal pre-empt → tighten", goal, notes.joinToString(", ")),
            HoldReason.GPU_BOUND_CAPPING,
            newState,
        )
    }

    // ── Cap stepping helpers ─────────────────────────────────────────────────────

    private data class CapStep(val cap: Int?, val changed: Boolean)

    /**
     * CAP-FLOOR (8th invariant). The index of the lowest OPP step the cap is allowed
     * to reach: the first OPP at/above [CAP_HARD_FLOOR_FRACTION] of the top OPP. This
     * is a HARD floor — no goal, classifier, or signal state may push the cap below it.
     *
     * Returns 0 only for a degenerate table (empty / single step) where no real floor
     * can be expressed. By construction the returned index is always a valid index into
     * [steps], so [steps][hardCapFloorIndex] is a real OPP (MM-2 preserved).
     */
    private fun hardCapFloorIndex(steps: List<Int>): Int {
        if (steps.size < 2) return 0
        val top = steps.last()
        val threshold = (top * CAP_HARD_FLOOR_FRACTION)
        val idx = steps.indexOfFirst { it >= threshold }
        // Clamp into [0, lastIndex - 1]: the floor is a usable working OPP, never the
        // very top step (which would forbid all capping). If 40% somehow lands on the
        // top OPP (a 2-step table), fall back to index 0 so capping is still possible.
        return idx.coerceIn(0, (steps.lastIndex - 1).coerceAtLeast(0))
    }

    /** The absolute-kHz CAP-FLOOR for [steps], or null for a degenerate table. */
    private fun hardCapFloorKhz(steps: List<Int>): Int? =
        if (steps.isEmpty()) null else steps[hardCapFloorIndex(steps)]

    /**
     * Step the big-cluster cap DOWN one OPP toward [floorKhz] (or the bottom OPP
     * when null), but NEVER below the CAP-FLOOR hard invariant (40% of the top OPP).
     * Returns changed=false at the effective floor.
     *
     * The effective floor index is `max(budget-floor, hard-floor)`: a hard-power-ceiling
     * goal's watts-budget floor can only ever RAISE the floor, never lower it past the
     * 40% guarantee. This is what makes the 384 MHz collapse impossible by construction.
     */
    private fun stepCapDown(currentCap: Int?, caps: TdpCaps, floorKhz: Int?): CapStep {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty()) return CapStep(currentCap, false)
        val budgetFloorIdx = floorKhz?.let { f ->
            steps.indexOfFirst { it >= f }.let { if (it < 0) 0 else it }
        } ?: 0
        // CAP-FLOOR: the cap may never decrement below 40% of the top OPP.
        val hardFloorIdx = hardCapFloorIndex(steps)
        val floorIdx = maxOf(budgetFloorIdx, hardFloorIdx)
        // Starting index: an uncapped cluster sits at the top OPP.
        val curIdx = currentCap?.let { c ->
            steps.indexOfFirst { it >= c }.let { if (it < 0) steps.lastIndex else it }
        } ?: steps.lastIndex
        val nextIdx = (curIdx - 1).coerceAtLeast(floorIdx)
        return if (nextIdx < curIdx) CapStep(steps[nextIdx], true) else CapStep(currentCap, false)
    }

    /**
     * Step the big-cluster cap UP one OPP. When the next step reaches the top OPP
     * the cap is CLEARED (null = stock) so a redundant top-OPP cap is never held.
     */
    private fun stepCapUp(currentCap: Int?, caps: TdpCaps): CapStep {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty() || currentCap == null) return CapStep(currentCap, false)
        val curIdx = steps.indexOfFirst { it >= currentCap }.let {
            if (it < 0) steps.lastIndex else it
        }
        val nextIdx = curIdx + 1
        return when {
            nextIdx >= steps.lastIndex -> CapStep(null, true) // reached/over top → clear
            else -> CapStep(steps[nextIdx], true)
        }
    }

    // ── Min-freq FLOOR stepping (MM-1 / MM-2) ────────────────────────────────────

    /**
     * The big-cluster cap as an OPP index. An uncapped cluster sits at the top OPP.
     * Used to keep the min-freq floor strictly below the cap (MM-1).
     */
    private fun capIndex(currentCap: Int?, steps: List<Int>): Int =
        currentCap?.let { c ->
            steps.indexOfFirst { it >= c }.let { if (it < 0) steps.lastIndex else it }
        } ?: steps.lastIndex

    /**
     * Raise the big-cluster min-freq FLOOR one OPP (smoothness). The floor may never
     * reach the cap (MM-1: floor < cap), so the ceiling for the floor index is
     * `capIdx - 1`. Returns changed=false when already at that ceiling or the OPP
     * table is empty. MM-2 holds: every returned value is a real OPP step.
     */
    private fun stepMinFloorUp(current: TdpState, caps: TdpCaps): CapStep {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.size < 2) return CapStep(current.bigClusterMinKhz, false)
        val capIdx = capIndex(current.bigClusterCapKhz, steps)
        val ceilIdx = capIdx - 1 // MM-1: floor strictly below the cap
        if (ceilIdx < 0) return CapStep(current.bigClusterMinKhz, false)
        // An unset floor sits at the bottom OPP (index 0).
        val curIdx = current.bigClusterMinKhz?.let { f ->
            steps.indexOfFirst { it >= f }.let { if (it < 0) 0 else it }
        } ?: 0
        val nextIdx = (curIdx + 1).coerceAtMost(ceilIdx)
        return if (nextIdx > curIdx) CapStep(steps[nextIdx], true) else CapStep(current.bigClusterMinKhz, false)
    }

    /**
     * Lower the big-cluster min-freq FLOOR one OPP (let the cluster idle deeper for
     * power) — but NEVER below the CAP-FLOOR hard invariant (40% of the top OPP).
     *
     * Returns changed=false when there is no floor set (already at stock) OR the floor
     * is already at the hard-floor OPP. We deliberately do NOT clear the floor to null
     * here: null means "stock kernel min" (the bottom OPP), which is BELOW the hard
     * floor and would defeat the collapse guarantee (DEFECT A dragged min=384 MHz in
     * lockstep with the cap). The min lever bottoms out at the hard floor, not stock.
     */
    private fun stepMinFloorDown(current: TdpState, caps: TdpCaps): CapStep {
        val steps = caps.bigClusterOppStepsKhz
        val floor = current.bigClusterMinKhz ?: return CapStep(null, false) // already stock
        if (steps.isEmpty()) return CapStep(floor, false)
        val hardFloorIdx = hardCapFloorIndex(steps)
        val curIdx = steps.indexOfFirst { it >= floor }.let { if (it < 0) 0 else it }
        return if (curIdx > hardFloorIdx) {
            CapStep(steps[curIdx - 1], true)
        } else {
            // Already at (or below) the hard floor — cannot tighten the min further.
            CapStep(floor, false)
        }
    }

    // ── GPU devfreq min/max stepping (bounds: floor < max ≤ ceil) ─────────────────

    private data class DevfreqStep(val freq: Long?, val changed: Boolean)

    /**
     * Raise the GPU devfreq MAX one OPP toward the ceiling (loosen — give the GPU
     * more room). Cleared to null (stock) when it reaches the ceiling. Skipped
     * (changed=false) when no devfreq table is discoverable.
     */
    private fun stepDevfreqMaxUp(currentMax: Long?, caps: TdpCaps): DevfreqStep {
        val steps = caps.gpuDevfreqStepsHz
        if (steps.size < 2 || currentMax == null) return DevfreqStep(currentMax, false)
        val curIdx = steps.indexOfFirst { it >= currentMax }.let { if (it < 0) steps.lastIndex else it }
        val nextIdx = curIdx + 1
        return when {
            nextIdx >= steps.lastIndex -> DevfreqStep(null, true) // reached top → clear
            else -> DevfreqStep(steps[nextIdx], true)
        }
    }

    /**
     * Lower the GPU devfreq MAX one OPP toward the floor (tighten — less GPU). Never
     * below the devfreq floor; the min always stays strictly below it (enforced in
     * [enforceInvariants]). Skipped when no devfreq table is discoverable.
     */
    private fun stepDevfreqMaxDown(currentMax: Long?, caps: TdpCaps): DevfreqStep {
        val steps = caps.gpuDevfreqStepsHz
        if (steps.size < 2) return DevfreqStep(currentMax, false)
        // An unset max sits at the ceiling (top step).
        val curIdx = currentMax?.let { m ->
            steps.indexOfFirst { it >= m }.let { if (it < 0) steps.lastIndex else it }
        } ?: steps.lastIndex
        // Floor: never drop the GPU max below devfreq index 1 (index 0 is reserved as
        // the min's home so max stays strictly above min — bounds invariant).
        val nextIdx = (curIdx - 1).coerceAtLeast(1)
        return if (nextIdx < curIdx) DevfreqStep(steps[nextIdx], true) else DevfreqStep(currentMax, false)
    }

    // ── Invariant enforcement (final gate before emitting any state) ─────────────

    /**
     * Clamp [state] so the SEVEN INVARIANTS hold before it is emitted. This is the
     * single defence-in-depth gate the direction handlers call on their result:
     *
     *  - **MM-1:** [TdpState.bigClusterMinKhz] strictly < the effective cap. If a
     *    cap step pushed the cap at/below the floor, the floor is lowered in lockstep
     *    to one OPP below the new cap (never the other way — the cap is the safety
     *    knob, the floor is the comfort knob).
     *  - **MM-2:** both min and cap are members of [TdpCaps.bigClusterOppStepsKhz]
     *    (they always are by construction, but a defensive snap guards against a
     *    pre-seeded test state with an off-table value).
     *  - **GPU devfreq bounds:** min < max, both within [floor, ceil].
     *  - **ACT-2:** {parked cores} XOR {uclamp hint}. If both are somehow set, the
     *    goal's choice wins: a uclamp goal drops any park; a parking goal drops any
     *    uclamp.
     *  - **CAP-FLOOR (8th):** the big-cluster cap is never below 40% of the top OPP
     *    ([CAP_HARD_FLOOR_FRACTION]). If a cap somehow arrived below the hard floor
     *    (off-table seed, future lever bug), it is raised to the nearest OPP at/above
     *    the floor — the cluster is NEVER floored. The min-floor is mirrored up to the
     *    hard floor too, subordinate to MM-1 (the cap is the safety knob; the min is
     *    the comfort knob and yields to MM-1's strict min < cap).
     */
    private fun enforceInvariants(state: TdpState, caps: TdpCaps, goal: GoalProfile): TdpState {
        var s = state
        val steps = caps.bigClusterOppStepsKhz

        // ── MM-2: snap min + cap to real OPP steps (defensive) ───────────────────
        if (steps.isNotEmpty()) {
            s.bigClusterCapKhz?.let { cap ->
                if (cap !in steps) {
                    val snapped = steps.lastOrNull { it <= cap } ?: steps.first()
                    s = s.copy(bigClusterCapKhz = snapped)
                }
            }
            s.bigClusterMinKhz?.let { floor ->
                if (floor !in steps) {
                    val snapped = steps.firstOrNull { it >= floor } ?: steps.last()
                    s = s.copy(bigClusterMinKhz = snapped)
                }
            }
        }

        // ── CAP-FLOOR (8th invariant): cap ≥ 40% of top OPP, never floor the cluster ─
        // Defence-in-depth backstop to [stepCapDown]'s hard-floor clamp. If a cap
        // arrived below the hard floor by ANY path (a pre-seeded off-table state, a
        // future lever that forgot the clamp), raise it to the nearest OPP at/above the
        // floor. This makes the 384 MHz cap→floor collapse (DEFECT A) impossible by
        // construction at the single gate every emitted state passes through.
        val hardFloorKhz = hardCapFloorKhz(steps)
        if (hardFloorKhz != null) {
            s.bigClusterCapKhz?.let { cap ->
                if (cap < hardFloorKhz) {
                    s = s.copy(bigClusterCapKhz = hardFloorKhz)
                }
            }
            // Mirror the floor for the MIN_FREQ_FLOOR lever: a set min must not sit
            // below the hard floor either. MM-1 below still has the final say (min must
            // stay strictly < cap), so this only ever RAISES the min toward the hard
            // floor when there is OPP headroom under the cap.
            s.bigClusterMinKhz?.let { floor ->
                if (floor < hardFloorKhz) {
                    s = s.copy(bigClusterMinKhz = hardFloorKhz)
                }
            }
        }

        // ── MM-1: floor strictly below the effective cap, lockstep lower ─────────
        val floor = s.bigClusterMinKhz
        if (floor != null && steps.isNotEmpty()) {
            val capIdx = capIndex(s.bigClusterCapKhz, steps)
            val floorIdx = steps.indexOfFirst { it >= floor }.let { if (it < 0) 0 else it }
            if (floorIdx >= capIdx) {
                // Invert avoided: drop the floor to one OPP below the cap. If the cap
                // is already at the bottom OPP, clear the floor entirely (stock).
                val newFloorIdx = capIdx - 1
                s = if (newFloorIdx < 0) {
                    s.copy(bigClusterMinKhz = null)
                } else {
                    s.copy(bigClusterMinKhz = steps[newFloorIdx])
                }
            }
        }

        // ── GPU devfreq bounds: floor ≤ min < max ≤ ceil ─────────────────────────
        val dFloor = caps.gpuDevfreqFloorHz
        val dCeil = caps.gpuDevfreqCeilHz
        if (dFloor != null && dCeil != null && dFloor < dCeil) {
            var dmin = s.gpuDevfreqMinHz
            var dmax = s.gpuDevfreqMaxHz
            if (dmin != null) dmin = dmin.coerceIn(dFloor, dCeil)
            if (dmax != null) dmax = dmax.coerceIn(dFloor, dCeil)
            // Enforce strict min < max when both are set.
            if (dmin != null && dmax != null && dmin >= dmax) {
                // The max is the active GPU lever; keep it, push the min one step below.
                val steps2 = caps.gpuDevfreqStepsHz
                dmin = if (steps2.isNotEmpty()) {
                    (steps2.lastOrNull { it < dmax!! } ?: dFloor)
                } else {
                    dFloor
                }
            }
            s = s.copy(gpuDevfreqMinHz = dmin, gpuDevfreqMaxHz = dmax)
        } else if (dFloor == null || dCeil == null) {
            // No discoverable devfreq envelope → never emit a devfreq write (honesty).
            if (s.gpuDevfreqMinHz != null || s.gpuDevfreqMaxHz != null) {
                s = s.copy(gpuDevfreqMinHz = null, gpuDevfreqMaxHz = null)
            }
        }

        // ── ACT-2: {park} XOR {uclamp} — the goal's choice wins ──────────────────
        if (s.parkedPrimeCores.isNotEmpty() && s.uclampTopAppMin != null) {
            s = if (goal.usesUclampNotPark) {
                s.copy(parkedPrimeCores = emptySet())
            } else {
                s.copy(uclampTopAppMin = null)
            }
        }
        // A goal that does NOT use uclamp must never carry a uclamp hint, and a goal
        // that DOES use uclamp must never carry parked cores (defence-in-depth).
        if (!goal.usesUclampNotPark && s.uclampTopAppMin != null) {
            s = s.copy(uclampTopAppMin = null)
        }
        if (goal.usesUclampNotPark && s.parkedPrimeCores.isNotEmpty()) {
            s = s.copy(parkedPrimeCores = emptySet())
        }

        return s
    }

    // ── Park helper (cpu0 sacred, min-online floor) ──────────────────────────────

    /**
     * Returns the next prime core to park (highest index first), or null when
     * parking would breach the min-online floor / no candidate / cpu0 only.
     *
     * NOTE (MEDIUM, deferred — see audit): the online-after estimate uses
     * [TdpCaps.totalOnlineCores], captured at session start. If a core hotplugs offline
     * mid-session (kernel thermal hotplug) this can be stale-high and slightly over-park.
     * Recomputing the live online count would require threading the telemetry window through
     * both [parkOneMore] call sites and their tighten-ladder callers — an invasive change to
     * the engine's verified decision path for a narrow, soft-degradation risk (the
     * [TdpCaps.minOnlineCores] floor still keeps the device usable, and over-parking by one
     * core is recoverable; this is NOT the stuck/hot/ANR severity class). Deferred to keep
     * AutoTdpService's verified behaviour intact; revisit if telemetry is already in scope.
     */
    private fun parkOneMore(current: TdpState, caps: TdpCaps): Int? {
        val candidates = caps.primeCoreIndices
            .filter { it != 0 && it !in current.parkedPrimeCores } // cpu0 NEVER
            .sortedDescending()
        if (candidates.isEmpty()) return null
        val onlineAfter = caps.totalOnlineCores - current.parkedPrimeCores.size - 1
        if (onlineAfter < caps.minOnlineCores) return null
        return candidates.first()
    }

    // ── Cool-down lookup ─────────────────────────────────────────────────────────

    private fun coolDownNeeded(last: Direction?, next: Direction): Int = when {
        last == Direction.TIGHTEN && next == Direction.LOOSEN -> K_TIGHTEN_TO_LOOSEN
        last == Direction.TIGHTEN && next == Direction.TIGHTEN -> K_TIGHTEN_TO_TIGHTEN
        last == Direction.LOOSEN && next == Direction.TIGHTEN -> K_LOOSEN_TO_TIGHTEN
        else -> 0 // first action of a session / loosen→loosen: no cool-down
    }

    // ── Reason string builder (H-1: config/intent ONLY, never measured) ──────────

    /**
     * Build a band-controller reason string. By construction this carries only the
     * goal's CONFIG (band edges, intent verb) and the lever NOTE (which actuator,
     * what target MHz/level) — never a measured mW/°C/fps. Measured quantities live
     * exclusively in [AutoTdpEffect]'s MEASURED tier.
     */
    private fun reason(verb: String, goal: GoalProfile, note: String): String =
        "$verb — targeting GPU ${goal.gpuBandLowPct}-${goal.gpuBandHighPct}%" +
            if (note.isNotBlank()) ", $note" else ""

    // ════════════════════════════════════════════════════════════════════════════
    //  SIGNAL EXTRACTION
    // ════════════════════════════════════════════════════════════════════════════

    private data class WindowSignals(
        /** EWMA-smoothed GPU busy% across the window (α=0.4). */
        val smoothedGpuPct: Int,
        /** Raw GPU EWMA accumulator (Double) to persist into ControllerState. */
        val gpuEwmaRaw: Double,
        /** Raw dTemp-slope EWMA accumulator to persist; null until ≥3 die samples. */
        val dTempSlopeRaw: Double?,
        /** Smoothed (mean) GPU die-temp °C, preferring gpuDieTempMilliC then zones. */
        val smoothedDieTempC: Int?,
        /** EWMA dTemp slope °C/s (α=0.5) once ≥[DTEMP_MIN_TICKS] samples exist; else null. */
        val dTempSlopeCPerS: Double?,
        /** Max cooling_device cur_state seen this window (>0 = kernel throttling). */
        val coolingMaxState: Int?,
        /** True when a big/prime core is saturated AND the load is TRUE jiffie data. */
        val cpuSaturatedTrueLoad: Boolean,
        /** True when the CPU load dimension is proxy-only / blind (FP-1 honesty). */
        val cpuProxyOnly: Boolean,
        /**
         * True only when the latest window sample carried a non-null gpuLoadPct, i.e.
         * the GPU busy% the band controller reasons over is a REAL measurement this
         * tick — not a null read coerced to 0 (the phantom-idle that drove DEFECT A).
         * False ⇒ the GPU signal was absent; the controller must not TIGHTEN on it.
         */
        val gpuSignalValid: Boolean,
        /** Derived heating trend for the AUTO→goal split. */
        val thermalTrend: ThermalTrend,
    ) {
        /**
         * The tick is LOAD-BLIND when there is NEITHER true CPU jiffie load (the window
         * is proxy-only / blind) NOR a real GPU read this tick. A blind tick must hold,
         * never tighten — it would otherwise tighten on a phantom-idle (DEFECT A). A
         * genuine CPU-saturation LOOSEN stays gated on true load (arm 2 in decide()),
         * so this asymmetry is honest: blind ⇒ no tighten, but real saturation ⇒ relax.
         */
        val isLoadBlind: Boolean get() = cpuProxyOnly && !gpuSignalValid

        /**
         * Thermal pre-empt = OR of:
         *  (1) smoothed die ≥ soft_target;
         *  (2) dTemp slope ≥ +3°C/s AND within 8°C of soft;
         *  (3) any cooling_device cur_state > 0 (kernel throttling NOW).
         */
        fun thermalPreempt(goal: GoalProfile): Boolean {
            val soft = goal.softDieTempC
            val die = smoothedDieTempC
            val arm1 = die != null && die >= soft
            val arm2 = die != null && dTempSlopeCPerS != null &&
                dTempSlopeCPerS >= DTEMP_PREEMPT_C_PER_S && (soft - die) <= DTEMP_NEAR_SOFT_C
            val arm3 = (coolingMaxState ?: 0) > 0
            return arm1 || arm2 || arm3
        }
    }

    private fun computeSignals(
        window: List<Telemetry>,
        caps: TdpCaps,
        state: ControllerState,
    ): WindowSignals {
        val monitored = caps.primeCoreIndices.toSet()

        // ── GPU busy% EWMA (α=0.4) — a true 1-pole filter at 1 Hz ─────────────────
        // When carried state exists, fold ONLY the newest sample into the carried
        // accumulator (the window is the daemon's buffer; older samples were already
        // folded on prior ticks). On the very first tick (no carried EWMA) seed from
        // the window mean so we start from a representative value, not a single noisy
        // sample. This avoids re-smoothing the whole window every tick.
        // gpuSignalValid: the latest sample's GPU busy% is a REAL read this tick.
        // We KEEP the `?: 0` coercion for the EWMA accumulator (so the smoothed value
        // and the HUD stay numeric), but the controller is told separately whether the
        // signal was actually present — a null→0 phantom must never originate a tighten
        // (the FP-1-for-GPU rule that closes DEFECT A's phantom-idle path).
        val gpuSignalValid = window.last().gpuLoadPct != null
        val latestGpu = (window.last().gpuLoadPct ?: 0).toDouble()
        val gpuEwma: Double = when (val prior = state.gpuEwma) {
            null -> window.map { (it.gpuLoadPct ?: 0).toDouble() }.average()
            else -> GPU_EWMA_ALPHA * latestGpu + (1 - GPU_EWMA_ALPHA) * prior
        }
        val smoothedGpu = gpuEwma.toInt().coerceIn(0, 100)

        // ── Die temp (prefer gpuDieTempMilliC; fall back to hottest zone) ─────────
        // HIGH-3: only PREFER the GPU die read when it is a SANE milli-°C value. SysfsProber
        // now normalizes/rejects bad units, but as defense-in-depth the engine also refuses
        // an out-of-band die so it can never win over the skin zones (a deci-°C device
        // reading 0 °C must not blind the thermal pre-empt; a micro-°C device must not pin
        // it). Outside the band ⇒ treat die as absent and fall back to the hottest zone.
        val dieTempsC = window.mapNotNull { s ->
            val sane = s.gpuDieTempMilliC?.takeIf { it in DIE_SANE_MILLI_MIN..DIE_SANE_MILLI_MAX }
            val milli = sane
                ?: s.zoneTempsMilliC.maxByOrNull { it.tempMilliC }?.tempMilliC
            milli?.let { it / 1000 }
        }
        val smoothedDie = if (dieTempsC.isNotEmpty()) dieTempsC.average().toInt() else null

        // ── dTemp slope EWMA (α=0.5) over ≥3 ticks; never a 1-tick raw delta ──────
        // Fold ONLY the newest per-tick delta into the carried slope when prior state
        // exists — matching the GPU EWMA pattern above. The window is the daemon's rolling
        // buffer; older deltas were already folded on prior ticks. Re-folding the WHOLE
        // overlapping window every tick over-inflated the slope (each older delta counted
        // many times), driving spurious early tightens / COOL_QUIET routing. On the FIRST
        // computation (no carried EWMA) seed by folding the window's deltas once.
        val dTempSlope: Double? = if (dieTempsC.size >= DTEMP_MIN_TICKS) {
            val prior = state.dTempSlopeEwma
            if (prior != null) {
                // Newest delta only (1 Hz → °C/s), folded into the carried EWMA.
                val newest = (dieTempsC.last() - dieTempsC[dieTempsC.size - 2]).toDouble()
                DTEMP_EWMA_ALPHA * newest + (1 - DTEMP_EWMA_ALPHA) * prior
            } else {
                // Seed: fold the window's deltas once to start from a representative value.
                var slope: Double? = null
                for (i in 1 until dieTempsC.size) {
                    val d = (dieTempsC[i] - dieTempsC[i - 1]).toDouble()
                    slope = if (slope == null) d else DTEMP_EWMA_ALPHA * d + (1 - DTEMP_EWMA_ALPHA) * slope
                }
                slope
            }
        } else null

        // ── Cooling-device max cur_state across the window ────────────────────────
        val coolingMax = window.mapNotNull { it.coolingDeviceMaxState }.maxOrNull()

        // ── CPU saturation — FP-1: TRUE-load gated ONLY ───────────────────────────
        // Freq-proxy may NOT originate the unpark. We require hasTrueLoadData AND a
        // saturated monitored core in the SAME sample.
        var cpuSatTrue = false
        var anyProxyOrBlind = false
        for (sample in window) {
            val trueLoad = sample.hasTrueLoadData
            if (!trueLoad) {
                anyProxyOrBlind = true
                continue // proxy/blind sample cannot ORIGINATE a state change
            }
            val saturated = monitored.any { idx ->
                (sample.perCoreLoadPct.getOrNull(idx) ?: 0) >= CPU_SATURATED_THRESHOLD
            }
            if (saturated) cpuSatTrue = true
        }
        // "Proxy-only" for honesty = no sample in the window carried true load data.
        val cpuProxyOnly = window.none { it.hasTrueLoadData }

        // ── Thermal trend for AUTO → goal split ───────────────────────────────────
        val trend = when {
            dTempSlope != null && dTempSlope >= 1.0 -> ThermalTrend.RISING
            smoothedDie != null && smoothedDie >= 80 -> ThermalTrend.RISING
            else -> ThermalTrend.COOL
        }

        return WindowSignals(
            smoothedGpuPct = smoothedGpu,
            gpuEwmaRaw = gpuEwma,
            dTempSlopeRaw = dTempSlope,
            smoothedDieTempC = smoothedDie,
            dTempSlopeCPerS = dTempSlope,
            coolingMaxState = coolingMax,
            cpuSaturatedTrueLoad = cpuSatTrue,
            cpuProxyOnly = cpuProxyOnly && anyProxyOrBlind,
            gpuSignalValid = gpuSignalValid,
            thermalTrend = trend,
        )
    }

    // ── BATTERY_SAVER / BATTERY_TARGET cap derivation (unchanged contract) ───────

    /**
     * Derives a big-cluster OPP cap from a milliwatt budget.
     *
     * fraction = targetMilliWatts / [MAX_DRAW_PROXY_MW] (dimensionless mW/mW),
     * clamped to [0,1]; cap = steps[floor(fraction*(steps.size-1))]. Returns null
     * for an empty OPP table or a non-positive budget.
     */
    internal fun deriveBudgetCap(caps: TdpCaps, targetMilliWatts: Long): Int? {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty() || targetMilliWatts <= 0) return null
        val fraction = (targetMilliWatts.toDouble() / MAX_DRAW_PROXY_MW).coerceIn(0.0, 1.0)
        val idx = (fraction * (steps.size - 1)).toInt().coerceIn(0, steps.lastIndex)
        return steps[idx]
    }

    /** Cap so a stuck quiet-tick counter never overflows in a long idle session. */
    private const val QUIET_TICK_CAP = 100
}

/**
 * The output of [AutoTdpEngine.decide].
 *
 * [target], [reason], [holdReason] are UNCHANGED from the prior contract so the
 * daemon ([AutoTdpService]) needs no edit this wave. [controllerState] is the
 * carried band/lever/cool-down/classifier state: the daemon persists it and threads
 * it back into the next [AutoTdpEngine.decide] call. It defaults to INITIAL so any
 * legacy 3-field construction still compiles.
 *
 * [resolvedGoal] (Wave 4a) is a READ-ONLY echo of the goal the engine actually
 * followed this tick — the requested goal, or (when AUTO was requested) the concrete
 * goal the classifier resolved AUTO to. It is purely informational: it lets the
 * daemon surface `activeGoal` to the HUD without re-deriving the classifier/thermal
 * logic. It does NOT change any decision — the value is the one already computed at
 * the single resolve point in [AutoTdpEngine.decide]. The detected workload context
 * is read separately from [controllerState]'s classifier (`classifier.stable`).
 * Defaults to [GoalProfile.DEFAULT] so legacy constructions still compile.
 */
data class TdpDecision(
    val target: TdpState,
    val reason: String,
    val holdReason: HoldReason = HoldReason.NO_TELEMETRY,
    val controllerState: ControllerState = ControllerState.INITIAL,
    val resolvedGoal: GoalProfile = GoalProfile.DEFAULT,
    /**
     * Hint to the daemon for how long to wait before the next tick (ms).
     * Null = use the current fixed cadence unchanged (default behavior preserved).
     * UNIT 2 (adaptive cadence) fills this field; the daemon clamps it to a 500 ms floor.
     */
    val nextTickHintMs: Int? = null,
    /**
     * UNIT 4 — true when the user picked TARGET_FPS_FLOOR but no REAL frame-rate source
     * is available this tick, so the engine DEGRADED the goal to BALANCED_SMART. The UI
     * surfaces a banner ("FPS floor needs a real frame-rate source; using Balanced.").
     * Defaults to false so every legacy / non-FPS-floor decision is unaffected. This is
     * an honesty flag — it never changes the target/reason/controllerState.
     */
    val fpsFloorDegraded: Boolean = false,
)
