package io.github.mayusi.calibratesoc.data.autotdp

/**
 * A control direction for the band controller. LOOSEN gives the workload more
 * headroom (relax caps / unpark); TIGHTEN reclaims power (cap / park).
 */
enum class Direction { LOOSEN, TIGHTEN }

/**
 * A single actuator the controller can drive. Listed in (roughly) priority order.
 *
 * WAVE 1 uses only {CAP, GPU_FLOOR, PARK, UNPARK}. MIN_FREQ_FLOOR, GPU_DEVFREQ and
 * UCLAMP are declared now (so the lever-priority code and Wave-2 wiring reference a
 * stable enum) but are never selected by the Wave-1 lever functions. Wave 2 slots
 * them into the priority orders:
 *   loosen: GPU-floor → [MIN_FREQ_FLOOR] → cap → unpark → [UCLAMP_MAX]
 *   tighten: cap → [MIN_FREQ_FLOOR] → GPU-floor → park → [UCLAMP_MAX]
 */
enum class Lever {
    /** scaling_max_freq cap on the big cluster (Wave 1). */
    CAP,
    /** Adreno max_pwrlevel floor (Wave 1). */
    GPU_FLOOR,
    /** Park a prime core (Wave 1, tighten only). */
    PARK,
    /** Unpark a prime core (Wave 1, loosen only). */
    UNPARK,

    // ── Wave 2 seams (declared, never selected in Wave 1) ───────────────────────
    /** scaling_min_freq floor on big/prime — the #1 smoothness win. WAVE 2. */
    MIN_FREQ_FLOOR,
    /** GPU devfreq min/max freq (finer than the 0–7 pwrlevel). WAVE 2. */
    GPU_DEVFREQ,
    /** uclamp.min/max perf-hint on the top app (park's XOR sibling). WAVE 2. */
    UCLAMP,
}

/**
 * The carried state of the band controller, threaded through [TdpDecision].
 *
 * The engine is PURE: every per-tick memory the controller needs (smoothing
 * accumulators, the active direction-episode, the cross-actuator cool-down counter,
 * the lever currently being ridden, and the context-classifier state) lives HERE.
 * The daemon persists [TdpDecision.controllerState] and passes it back into the next
 * [AutoTdpEngine.decide] call. Nothing in this file is Android, I/O, or a clock read.
 *
 * @property gpuEwma           carried GPU busy% EWMA accumulator (α=0.4 continuity
 *                             across ticks). Null until the first sample is seen.
 * @property dTempSlopeEwma    carried dTemp-slope EWMA accumulator (α=0.5). Null
 *                             until ≥3 die-temp samples exist.
 * @property currentDirection  the direction of the in-progress episode, or null when
 *                             holding inside the dead-band.
 * @property confirmTicks      consecutive ticks the controller has been confirming
 *                             [currentDirection] (loosen needs 1, tighten needs 2).
 * @property quietTicks        consecutive non-acting ticks since the last action —
 *                             the cross-actuator cool-down counter.
 * @property lastActedDirection the direction of the last APPLIED action (drives the
 *                             cool-down K lookup). Null before any action.
 * @property activeLever       the lever currently being ridden in the active episode
 *                             (ACT-1: one lever per direction-episode). Null when no
 *                             episode is active.
 * @property classifier        the carried [ClassifierState] for the context classifier.
 * @property fan                the carried [FanGovernorState] for the rate-limited
 *                              fan_mode actuator (Wave 2). Tracks the last applied
 *                              preset, the tick it changed on, and the temperature
 *                              at that change so the governor can enforce ≥10s
 *                              spacing + 5°C hysteresis + monotonic-per-direction.
 * @property tick               monotonic decision-tick counter (1 Hz). Advanced once
 *                              per [AutoTdpEngine.decide] and used ONLY by the fan
 *                              governor's rate-limit window; carries no I/O / clock.
 */
data class ControllerState(
    val gpuEwma: Double? = null,
    val dTempSlopeEwma: Double? = null,
    val currentDirection: Direction? = null,
    val confirmTicks: Int = 0,
    val quietTicks: Int = 0,
    val lastActedDirection: Direction? = null,
    val activeLever: Lever? = null,
    val classifier: ClassifierState = ClassifierState.INITIAL,
    val fan: FanGovernorState = FanGovernorState.INITIAL,
    val tick: Long = 0L,
    // ── Unit 1 (per-game learning) carried flags ────────────────
    /**
     * UNIT 1 (per-game learning): true once the learned cap has been applied as the
     * STARTING operating point this session. Seeding happens ONCE (the first tick a
     * seed is present); this latch makes it idempotent so the reactive controller owns
     * the cap from then on. Default false (cold start).
     */
    val learnedSeedApplied: Boolean = false,
    /**
     * UNIT 1 (per-game learning): true once the ONE proactive thermal tighten has fired
     * for the current learned-onset window. Gates the proactive arm to fire AT MOST once
     * per session near 0.85 × the learned onset; the normal band controller loosens it
     * back. Default false.
     */
    val proactivePreemptArmed: Boolean = false,
) {

    /**
     * Begin/continue confirming a [direction]. When the direction matches the
     * in-progress episode the confirm counter increments; when it flips, the
     * episode resets to a fresh count of 1 (and the active lever is released so the
     * new direction starts at the top of its own priority order).
     *
     * Returns a NEW state with the advanced confirm count and (on a flip) a cleared
     * [activeLever] AND a reset [quietTicks]. Resetting quiet ticks on a flip is what
     * makes the cross-actuator cool-down honest: the K-tick gap must be measured FROM
     * the moment we start wanting the new direction, not from quiet ticks that
     * accrued while still pursuing the OLD direction. Within an unchanged direction
     * [quietTicks] is left to the caller (it decides "quiet" vs "acting").
     */
    fun advanceConfirm(direction: Direction): ControllerState {
        return if (currentDirection == direction) {
            copy(confirmTicks = confirmTicks + 1)
        } else {
            // Direction flip: new episode. Release the old lever AND reset quietTicks
            // so the new direction's cool-down counts fresh ticks from this flip.
            copy(
                currentDirection = direction,
                confirmTicks = 1,
                quietTicks = 0,
                activeLever = null,
            )
        }
    }

    /**
     * Record that an action was (or was not) APPLIED this tick on [lever] in
     * [direction].
     *
     * - When [didAct] is true: reset [quietTicks] to 0 (we just acted), set
     *   [lastActedDirection], and pin [activeLever] = lever (ACT-1 — keep riding
     *   this lever next tick until it saturates).
     * - When [didAct] is false (the lever could not move — saturated): release the
     *   active lever so the NEXT same-direction tick advances to the next lever in
     *   priority, and accrue a quiet tick (no actuator moved).
     */
    fun recordAction(direction: Direction, lever: Lever, didAct: Boolean): ControllerState {
        return if (didAct) {
            copy(
                currentDirection = direction,
                quietTicks = 0,
                lastActedDirection = direction,
                activeLever = lever,
            )
        } else {
            // Lever saturated — hand off to the next lever next tick; this tick was
            // effectively quiet (nothing moved), which also advances cool-down.
            copy(
                currentDirection = direction,
                quietTicks = quietTicks + 1,
                activeLever = null,
            )
        }
    }

    companion object {
        /** The clean starting state for a fresh daemon session. */
        val INITIAL = ControllerState()
    }
}

/**
 * Carried state for the rate-limited fan_mode governor (Wave 2).
 *
 * The Odin exposes only discrete fan presets (NOT a raw PWM curve), so the
 * governor maps thermal state → one of those presets per goal. Three LAWS apply
 * (TIGHTENED CONTROL SPEC):
 *   - **≥10s between changes:** at 1 Hz that is ≥ [FanGovernor.MIN_CHANGE_TICKS]
 *     ticks since the last applied change.
 *   - **5°C hysteresis:** the die temp must have moved at least
 *     [FanGovernor.HYSTERESIS_C] from the temp at the last change before the
 *     governor will move again.
 *   - **monotonic per direction:** within a heating episode the fan only steps
 *     UP (cooler→hotter presets); within a cooling episode only DOWN. A reversal
 *     requires the hysteresis + rate-limit to clear first.
 *
 * PURE: nothing here reads a clock or sysfs. The engine advances [ControllerState.tick]
 * and feeds the smoothed die temp; the governor decides whether a new preset may be
 * emitted this tick.
 *
 * @property lastAppliedMode  the fan preset last emitted by the governor, or null
 *                            when the governor has never actuated this session.
 * @property lastChangeTick   the [ControllerState.tick] at which [lastAppliedMode]
 *                            was applied. -1 when never applied (so the first change
 *                            is always allowed by the rate-limit).
 * @property lastChangeTempC  the smoothed die temp (°C) at the last change. Used for
 *                            the 5°C hysteresis gate. Null when never applied.
 */
data class FanGovernorState(
    val lastAppliedMode: Int? = null,
    val lastChangeTick: Long = -1L,
    val lastChangeTempC: Int? = null,
) {
    companion object {
        val INITIAL = FanGovernorState()
    }
}

/**
 * Pure decision logic for the fan governor. Separated from the engine so the
 * rate-limit + hysteresis + monotonic LAWS are independently unit-testable.
 */
object FanGovernor {

    /** ≥10s between changes → at 1 Hz, ≥10 ticks since the last applied change. */
    const val MIN_CHANGE_TICKS = 10L

    /** 5°C hysteresis: the die must move this far from the last-change temp. */
    const val HYSTERESIS_C = 5

    /**
     * Decide the fan preset to APPLY this tick, given the goal's desired preset
     * for the current thermal state. Returns:
     *   - null  → do not touch the fan this tick (rate-limited, within hysteresis,
     *             a non-monotonic reversal, or no change requested).
     *   - Int   → the preset to emit (always == [desiredMode] when non-null).
     *
     * @param desiredMode    the preset the goal's fan policy wants for [dieTempC],
     *                       or null when the goal doesn't drive the fan.
     * @param dieTempC       the smoothed die temp (°C) this tick, or null when no
     *                       die temp is available (governor holds — never guesses).
     * @param tick           the current monotonic decision tick.
     * @param state          the carried [FanGovernorState].
     * @return the preset to apply (echoes [desiredMode]) or null to hold.
     */
    fun decide(
        desiredMode: Int?,
        dieTempC: Int?,
        tick: Long,
        state: FanGovernorState,
    ): Int? {
        // No goal-driven fan target, or no temperature to reason about → hold.
        if (desiredMode == null || dieTempC == null) return null
        // No change vs the last applied preset → nothing to do.
        if (desiredMode == state.lastAppliedMode) return null

        // First-ever change this session: allowed (no prior change to rate-limit
        // against), still recorded so subsequent changes are spaced.
        if (state.lastAppliedMode == null) return desiredMode

        // Rate-limit: ≥10 ticks since the last applied change.
        val ticksSince = tick - state.lastChangeTick
        if (ticksSince < MIN_CHANGE_TICKS) return null

        // Hysteresis: the die must have moved ≥5°C from the last-change temp.
        val lastTemp = state.lastChangeTempC
        if (lastTemp != null && kotlin.math.abs(dieTempC - lastTemp) < HYSTERESIS_C) return null

        // Monotonic-per-direction: a step UP (to a higher/cooler-targeting preset
        // index) is only allowed when the die is HOTTER than at the last change; a
        // step DOWN only when COOLER. This prevents flapping between presets on a
        // plateau. (Preset ordinal carries the intent: higher index = more cooling
        // for the AYN scale where Sport/Performance > Smart > Quiet.) We compare the
        // requested preset against the last applied and require the temperature
        // delta to agree with the direction.
        if (lastTemp != null) {
            val wantsMoreCooling = desiredMode > state.lastAppliedMode
            val gotHotter = dieTempC > lastTemp
            if (wantsMoreCooling != gotHotter) return null
        }

        return desiredMode
    }

    /** Fold an applied change into the carried state. Call ONLY when [decide]
     *  returned non-null and the engine actually emitted the new preset. */
    fun recordChange(applied: Int, dieTempC: Int?, tick: Long): FanGovernorState =
        FanGovernorState(
            lastAppliedMode = applied,
            lastChangeTick = tick,
            lastChangeTempC = dieTempC,
        )
}
