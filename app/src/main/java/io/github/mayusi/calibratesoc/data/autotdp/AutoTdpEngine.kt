package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.hasTrueLoadData

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

    /** A big/prime core at/above this TRUE-load% is the CPU bottleneck (unpark). */
    private const val CPU_SATURATED_THRESHOLD = 85

    /** Cross-actuator cool-down counts (LAW). */
    private const val K_TIGHTEN_TO_LOOSEN = 3   // tighten→loosen needs 3 quiet ticks
    private const val K_TIGHTEN_TO_TIGHTEN = 1  // consecutive tighten cadence
    private const val K_LOOSEN_TO_TIGHTEN = 2   // loosen→tighten cadence

    /** Confirming-tick law: loosen acts on 1, tighten on 2. */
    private const val LOOSEN_CONFIRM_TICKS = 1
    private const val TIGHTEN_CONFIRM_TICKS = 2

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

        // ── Resolve the active goal (AUTO → classifier) ──────────────────────────
        val requestedGoal = goalOverride ?: GoalProfile.fromLegacyProfile(config.profile)
        val classification = ContextClassifier.classify(
            window = window,
            smoothedGpuPct = signals.smoothedGpuPct,
            prior = controllerState.classifier,
        )
        val activeGoal = if (requestedGoal == GoalProfile.AUTO) {
            ContextClassifier.goalFor(classification.context, signals.thermalTrend)
        } else {
            requestedGoal
        }

        // Carry the freshly-advanced classifier state AND the updated EWMA
        // accumulators into every returned decision so the 1-pole filters persist
        // across ticks. (The direction handlers each re-apply classifier = state too;
        // they inherit the EWMA values from baseState.) [tick] is advanced once per
        // decision so the fan governor's rate-limit window counts real ticks.
        val baseState = controllerState.copy(
            classifier = classification.state,
            gpuEwma = signals.gpuEwmaRaw,
            dTempSlopeEwma = signals.dTempSlopeRaw ?: controllerState.dTempSlopeEwma,
            tick = controllerState.tick + 1,
        )

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
        return applyFanGovernor(raw, caps, activeGoal, signals).copy(resolvedGoal = activeGoal)
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
        val confirm = base.advanceConfirm(Direction.TIGHTEN)
        if (confirm.confirmTicks < TIGHTEN_CONFIRM_TICKS) {
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
        val next = enforceInvariants(next0, caps, goal)
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
     * Step the big-cluster cap DOWN one OPP toward [floorKhz] (or the bottom OPP
     * when null). Never goes below the floor. Returns changed=false at the floor.
     */
    private fun stepCapDown(currentCap: Int?, caps: TdpCaps, floorKhz: Int?): CapStep {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty()) return CapStep(currentCap, false)
        val floorIdx = floorKhz?.let { f ->
            steps.indexOfFirst { it >= f }.let { if (it < 0) 0 else it }
        } ?: 0
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
     * power). At the bottom OPP the floor is CLEARED (null = stock). Returns
     * changed=false when there is no floor set (already at stock).
     */
    private fun stepMinFloorDown(current: TdpState, caps: TdpCaps): CapStep {
        val steps = caps.bigClusterOppStepsKhz
        val floor = current.bigClusterMinKhz ?: return CapStep(null, false) // already stock
        if (steps.isEmpty()) return CapStep(floor, false)
        val curIdx = steps.indexOfFirst { it >= floor }.let { if (it < 0) 0 else it }
        return when {
            curIdx <= 0 -> CapStep(null, true)          // at/below bottom OPP → clear
            else -> CapStep(steps[curIdx - 1], true)
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
        /** Derived heating trend for the AUTO→goal split. */
        val thermalTrend: ThermalTrend,
    ) {
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
        val latestGpu = (window.last().gpuLoadPct ?: 0).toDouble()
        val gpuEwma: Double = when (val prior = state.gpuEwma) {
            null -> window.map { (it.gpuLoadPct ?: 0).toDouble() }.average()
            else -> GPU_EWMA_ALPHA * latestGpu + (1 - GPU_EWMA_ALPHA) * prior
        }
        val smoothedGpu = gpuEwma.toInt().coerceIn(0, 100)

        // ── Die temp (prefer gpuDieTempMilliC; fall back to hottest zone) ─────────
        val dieTempsC = window.mapNotNull { s ->
            val milli = s.gpuDieTempMilliC
                ?: s.zoneTempsMilliC.maxByOrNull { it.tempMilliC }?.tempMilliC
            milli?.let { it / 1000 }
        }
        val smoothedDie = if (dieTempsC.isNotEmpty()) dieTempsC.average().toInt() else null

        // ── dTemp slope EWMA (α=0.5) over ≥3 ticks; never a 1-tick raw delta ──────
        val dTempSlope: Double? = if (dieTempsC.size >= DTEMP_MIN_TICKS) {
            // Per-tick deltas (1 Hz → °C/s). Smooth them with α=0.5.
            var slope: Double? = state.dTempSlopeEwma
            for (i in 1 until dieTempsC.size) {
                val d = (dieTempsC[i] - dieTempsC[i - 1]).toDouble()
                slope = if (slope == null) d else DTEMP_EWMA_ALPHA * d + (1 - DTEMP_EWMA_ALPHA) * slope
            }
            slope
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
)
