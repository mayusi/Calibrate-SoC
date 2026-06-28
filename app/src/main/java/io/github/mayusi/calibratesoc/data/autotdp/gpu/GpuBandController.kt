package io.github.mayusi.calibratesoc.data.autotdp.gpu

import io.github.mayusi.calibratesoc.data.autotdp.Direction
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptiveSetpoints

/**
 * UNIT 2 (ADAPTIVE MODE) — the GPU band controller: a GPU-load-driven GPU clock
 * governor that mirrors the CPU band controller (`AutoTdpEngine`) discipline 1:1.
 *
 * PURE: no Android, no I/O, no time. Inputs are value objects ([GpuSignals],
 * [AdaptiveSetpoints], [GpuControllerState], [TdpCaps]); the output is a [GpuDecision]
 * + the next carried [GpuControllerState]. Every path is unit-testable without a device.
 *
 * ## The model (per tick) — copies the CPU band discipline for the GPU
 *
 *  1. **Smooth the GPU busy% signal** (EWMA α=0.4) and compare it to the GPU band from
 *     [AdaptiveSetpoints.gpuBand]:
 *       - above the band high → LOOSEN (raise the GPU clock one OPP — GPU is the
 *         bottleneck, give it headroom)
 *       - below the band low  → TIGHTEN (lower the GPU clock one OPP — GPU underused,
 *         reclaim power)
 *       - inside the band     → HOLD (the dead-band kills oscillation)
 *  2. **One notch per direction-episode** (clock up XOR down), confirm-gated: a loosen
 *     confirms over [LOOSEN_CONFIRM_TICKS] ticks; a calm BAND tighten over
 *     [TIGHTEN_CONFIRM_TICKS]; a FAST thermal tighten acts THIS tick (0 confirm).
 *  3. **Fast-down / slow-up** (asymmetric reaction): a tighten driven by a genuine
 *     thermal/throttle signal (GPU near [AdaptiveSetpoints.gpuSoftTempC] OR dTemp slope
 *     ≥ [FAST_TIGHTEN_DTEMP_C_PER_S] OR coolingMaxState > 0) lands in 1 tick and may
 *     step DOWN by up to [MAX_GPU_STEP_PER_TICK] OPPs; a band-only tighten keeps the
 *     conservative 2-tick confirm and a single step. A LOOSEN is always confirm-gated +
 *     one step.
 *  4. **GPU FLOOR INVARIANT (LAW):** the GPU clock NEVER drops below
 *     [AdaptiveSetpoints.gpuFloorFraction] × [TdpCaps.gpuDevfreqCeilHz], snapped to a
 *     real OPP, and never below the lowest stock OPP. Enforced on every TIGHTEN — this
 *     prevents a 384 MHz-style GPU collapse.
 *  5. **Load-blind safe:** no real GPU read this tick → HOLD at the current operating
 *     point; never originate a loosen on a phantom-idle.
 *  6. **Adaptive cadence:** emit [GpuDecision.nextTickHintMs] (warming → 500, calm →
 *     1000), with a 500 ms floor. Unit 5 min()s it with the CPU controller's hint.
 *
 * The controller governs WITHIN the stock GPU range by default. When GPU overclock is
 * active the coordinator (Unit 5) passes a higher [effectiveCeilHz] so the band can
 * govern up to the OC ceiling — beyond-stock OC actuation itself is Unit 3's territory,
 * NOT applied here.
 */
object GpuBandController {

    // ── Smoothing / control constants (LAW — mirror the CPU controller) ──────────

    /** GPU busy% EWMA smoothing factor — identical to the CPU controller's α=0.4. */
    private const val GPU_EWMA_ALPHA = 0.4

    /** Confirming-tick law: a loosen acts on 1 confirm tick. */
    private const val LOOSEN_CONFIRM_TICKS = 1

    /** A calm BAND tighten keeps the conservative 2-tick confirm (anti-hunt). */
    private const val TIGHTEN_CONFIRM_TICKS = 2

    /** A FAST thermal/throttle tighten acts THIS tick (no confirm) — react to real heat. */
    private const val TIGHTEN_CONFIRM_TICKS_FAST = 0

    /**
     * Cross-direction cool-down (mirrors the CPU controller's K gaps): a loosen→tighten
     * flip waits this many quiet ticks before the first tighten lands, so a momentary
     * GPU%-noise dip can never immediately reverse a fresh loosen. A FAST thermal tighten
     * is EXEMPT (genuine heat reacts now). Tighten→loosen waits longer (slow-up).
     */
    private const val K_LOOSEN_TO_TIGHTEN = 2
    private const val K_TIGHTEN_TO_LOOSEN = 3

    /** dTemp slope (°C/s) at/above which a band tighten is URGENT (die heating fast). */
    private const val FAST_TIGHTEN_DTEMP_C_PER_S = 2.0

    /** "GPU die within N°C of soft" window that makes a band tighten urgent. */
    private const val FAST_TIGHTEN_NEAR_SOFT_C = 3

    /**
     * RATE-LIMITED multi-OPP swing (GPU-collapse guard — mirrors the CPU
     * MAX_CAP_STEP_PER_TICK). A single tick may step the GPU clock DOWN by at most this
     * many OPPs. The floor invariant then clamps on top — this never bypasses it.
     */
    private const val MAX_GPU_STEP_PER_TICK = 2

    // ── Adaptive tick cadence (mirror the CPU controller) ────────────────────────

    /** Default (calm) re-eval cadence: 1 Hz. */
    private const val CALM_TICK_MS = 1000

    /** Faster (warming) re-eval cadence — the hard 500 ms / 2 Hz floor. */
    private const val FAST_TICK_MS = 500

    /** dTemp slope (°C/s) at/above which the controller REQUESTS the faster re-eval tick. */
    private const val WARM_DTEMP_C_PER_S = 1.0

    /** "GPU die within N°C of soft" window that also requests the [FAST_TICK_MS] cadence. */
    private const val NEAR_SOFT_FAST_TICK_C = 5

    /** Cap on accrued quiet ticks (prevents unbounded growth across long calm runs). */
    private const val QUIET_TICK_CAP = 60

    /**
     * Fraction of the operating GPU MAX held below it as the devfreq MIN, for frame
     * consistency (mirrors the CPU controller's min-floor-below-cap idea). The min is
     * always snapped to a real OPP strictly below the max and never below the floor.
     */
    private const val MIN_BELOW_MAX_FRACTION = 0.70

    /**
     * Decide the GPU clock target for this tick.
     *
     * @param gpuBusyPct      the raw GPU busy% this tick (0..100), or null when no GPU
     *                        read was available (load-blind → HOLD).
     * @param signals         the SHARED read-only signals (the same value object Unit 5
     *                        feeds the CPU engine): GPU die temp, dTemp slope, and the
     *                        kernel cooling state. Consumed for the FAST thermal regime.
     * @param setpoints       the resolved adaptive setpoints (Unit 1) — supplies the GPU
     *                        band, floor fraction, and soft die temp.
     * @param state           the carried [GpuControllerState].
     * @param caps            the device GPU envelope (devfreq OPP table + bounds + the
     *                        pwrlevel range).
     * @param effectiveCeilHz the effective GPU devfreq ceiling the band may govern up to.
     *                        Defaults to [TdpCaps.gpuDevfreqCeilHz] (stock). Unit 5 passes
     *                        the OC ceiling when overclock is active; this controller
     *                        never exceeds whatever ceiling it is given.
     * @return the [GpuDecision] target + the next carried [GpuControllerState].
     */
    fun decide(
        gpuBusyPct: Int?,
        signals: GpuSignals,
        setpoints: AdaptiveSetpoints,
        state: GpuControllerState,
        caps: TdpCaps,
        effectiveCeilHz: Long? = caps.gpuDevfreqCeilHz,
    ): Pair<GpuDecision, GpuControllerState> {

        // ── Cadence hint (computed once; stamped on every returned decision) ─────
        val nextHint = nextTickHint(signals, setpoints)

        // ── LOAD-BLIND HOLD (mirror of the CPU controller's FP-1 / DEFECT-A guard) ─
        // No real GPU read this tick ⇒ the controller is reasoning over a phantom. HOLD
        // at the current operating point and END any direction episode so a blind tick
        // can never accumulate confirm ticks toward a tighten or originate a loosen.
        if (gpuBusyPct == null) {
            return hold(
                state = state,
                nextHint = nextHint,
                reason = "holding (GPU load unreadable)",
                clearEwma = false,
            )
        }

        // ── GPU busy% EWMA (α=0.4) — a true 1-pole filter, carried across ticks ───
        val prior = state.gpuEwma
        val ewma: Double = if (prior == null) {
            gpuBusyPct.toDouble()
        } else {
            GPU_EWMA_ALPHA * gpuBusyPct.toDouble() + (1 - GPU_EWMA_ALPHA) * prior
        }
        val smoothedGpu = ewma.toInt().coerceIn(0, 100)
        // Thread the fresh accumulator through whatever path we take below.
        val withEwma = state.copy(gpuEwma = ewma)

        // ── BAND CONTROL: GPU busy% vs the adaptive band ─────────────────────────
        val band = setpoints.gpuBand
        return when {
            smoothedGpu > band.high ->
                loosen(withEwma, setpoints, caps, effectiveCeilHz, nextHint)
            smoothedGpu < band.low ->
                tighten(withEwma, signals, setpoints, caps, effectiveCeilHz, nextHint)
            else ->
                bandHold(withEwma, band, nextHint)
        }
    }

    // ── HOLD ─────────────────────────────────────────────────────────────────────

    /** Inside the dead-band: hold, end the episode, accrue a quiet tick. */
    private fun bandHold(
        base: GpuControllerState,
        band: io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuBand,
        nextHint: Int,
    ): Pair<GpuDecision, GpuControllerState> = hold(
        state = base,
        nextHint = nextHint,
        reason = "holding (GPU in ${band.low}-${band.high}% band)",
        clearEwma = false,
    )

    /**
     * Emit a HOLD: leave the actuators alone (all targets null = readback-discipline
     * "don't touch"), end any in-progress direction episode, and accrue a quiet tick so
     * the cross-direction cool-down keeps advancing while we sit still.
     */
    private fun hold(
        state: GpuControllerState,
        nextHint: Int,
        reason: String,
        clearEwma: Boolean,
    ): Pair<GpuDecision, GpuControllerState> {
        val held = state.copy(
            gpuEwma = if (clearEwma) null else state.gpuEwma,
            currentDirection = null,
            confirmTicks = 0,
            quietTicks = (state.quietTicks + 1).coerceAtMost(QUIET_TICK_CAP),
        )
        return GpuDecision(
            targetGpuLevel = null,
            targetGpuDevfreqMinHz = null,
            targetGpuDevfreqMaxHz = null,
            nextTickHintMs = nextHint,
            reason = reason,
        ) to held
    }

    // ── LOOSEN (raise the GPU clock one OPP — always confirm-gated, slow-up) ──────

    private fun loosen(
        base: GpuControllerState,
        setpoints: AdaptiveSetpoints,
        caps: TdpCaps,
        effectiveCeilHz: Long?,
        nextHint: Int,
    ): Pair<GpuDecision, GpuControllerState> {
        val confirm = base.advanceConfirm(Direction.LOOSEN)
        if (confirm.confirmTicks < LOOSEN_CONFIRM_TICKS) {
            return confirmingHold(confirm, nextHint, "confirming GPU loosen")
        }
        // Cross-direction cool-down: a tighten→loosen flip waits K quiet ticks.
        val needed = if (base.lastActedDirection == Direction.TIGHTEN) K_TIGHTEN_TO_LOOSEN else 0
        if (confirm.quietTicks < needed) {
            val cooled = confirm.copy(quietTicks = confirm.quietTicks + 1)
            return GpuDecision(null, null, null, nextHint, "cool-down before GPU loosen") to cooled
        }

        val ceil = effectiveCeilHz
        val steps = caps.gpuDevfreqStepsHz
        // Devfreq path: raise the MAX one OPP toward the effective ceiling.
        if (ceil != null && steps.size >= 2) {
            val curMax = base.gpuDevfreqMaxHz ?: floorMaxHz(setpoints, caps, ceil)
            val raised = stepUp(curMax, steps, ceil)
            return if (raised != null && raised != curMax) {
                val (minHz, maxHz) = windowFor(raised, setpoints, caps, ceil)
                act(base, Direction.LOOSEN, level = null, minHz = minHz, maxHz = maxHz,
                    nextHint = nextHint, reason = "loosen GPU → ${maxHz / 1_000_000} MHz")
            } else {
                // Already at the ceiling — nothing to loosen; record as a quiet (saturated) act.
                saturated(base, Direction.LOOSEN, nextHint, "GPU at ceiling (nothing to loosen)")
            }
        }
        // Pwrlevel mirror (coarse devices without a devfreq table): lower the level index.
        return loosenPwrLevel(base, caps, nextHint)
    }

    // ── TIGHTEN (lower the GPU clock — fast-down on heat, floor-invariant clamped) ─

    private fun tighten(
        base: GpuControllerState,
        signals: GpuSignals,
        setpoints: AdaptiveSetpoints,
        caps: TdpCaps,
        effectiveCeilHz: Long?,
        nextHint: Int,
    ): Pair<GpuDecision, GpuControllerState> {
        // ASYMMETRIC: a thermal/throttle tighten acts THIS tick (FAST); a calm band-only
        // tighten keeps the 2-tick confirm so noisy GPU% can never hunt the clock down.
        val fast = isFastRegime(signals, setpoints)
        val confirmTarget = if (fast) TIGHTEN_CONFIRM_TICKS_FAST else TIGHTEN_CONFIRM_TICKS
        val confirm = base.advanceConfirm(Direction.TIGHTEN)
        if (confirm.confirmTicks < confirmTarget) {
            return confirmingHold(confirm, nextHint, "confirming GPU tighten")
        }
        // Cross-direction cool-down — EXEMPT in the FAST regime (real heat reacts now).
        if (!fast) {
            val needed = if (base.lastActedDirection == Direction.LOOSEN) K_LOOSEN_TO_TIGHTEN else 0
            if (confirm.quietTicks < needed) {
                val cooled = confirm.copy(quietTicks = confirm.quietTicks + 1)
                return GpuDecision(null, null, null, nextHint, "cool-down before GPU tighten") to cooled
            }
        }

        val ceil = effectiveCeilHz
        val steps = caps.gpuDevfreqStepsHz
        // Devfreq path: lower the MAX toward the floor, by up to MAX_GPU_STEP_PER_TICK in
        // the FAST regime (fast-down), one OPP otherwise. NEVER below the floor invariant.
        if (ceil != null && steps.size >= 2) {
            val floorHz = floorMaxHz(setpoints, caps, ceil)
            val curMax = base.gpuDevfreqMaxHz ?: ceil
            val stepN = if (fast) MAX_GPU_STEP_PER_TICK else 1
            val lowered = stepDown(curMax, steps, floorHz, stepN)
            return if (lowered != null && lowered != curMax) {
                val (minHz, maxHz) = windowFor(lowered, setpoints, caps, ceil)
                act(base, Direction.TIGHTEN, level = null, minHz = minHz, maxHz = maxHz,
                    nextHint = nextHint, reason = "tighten GPU → ${maxHz / 1_000_000} MHz")
            } else {
                // At the floor invariant — cannot tighten further (the floor LAW holds).
                saturated(base, Direction.TIGHTEN, nextHint, "GPU at floor (invariant)")
            }
        }
        // Pwrlevel mirror: raise the level index toward the floor level (slower GPU).
        return tightenPwrLevel(base, setpoints, caps, nextHint)
    }

    // ── Pwrlevel mirror (devices with no devfreq OPP table) ──────────────────────

    private fun loosenPwrLevel(
        base: GpuControllerState,
        caps: TdpCaps,
        nextHint: Int,
    ): Pair<GpuDecision, GpuControllerState> {
        val min = caps.gpuMinLevel
        val max = caps.gpuMaxLevel
        if (min == null || max == null) {
            return saturated(base, Direction.LOOSEN, nextHint, "no GPU pwrlevel control")
        }
        val cur = base.gpuLevel ?: max
        return if (cur > min) {
            val next = (cur - 1).coerceAtLeast(min)
            actLevel(base, Direction.LOOSEN, next, nextHint, "loosen GPU → level $next")
        } else {
            saturated(base, Direction.LOOSEN, nextHint, "GPU at fastest level")
        }
    }

    private fun tightenPwrLevel(
        base: GpuControllerState,
        setpoints: AdaptiveSetpoints,
        caps: TdpCaps,
        nextHint: Int,
    ): Pair<GpuDecision, GpuControllerState> {
        val min = caps.gpuMinLevel
        val max = caps.gpuMaxLevel
        if (min == null || max == null) {
            return saturated(base, Direction.TIGHTEN, nextHint, "no GPU pwrlevel control")
        }
        // GPU FLOOR INVARIANT on the coarse lever: map the floor fraction onto the level
        // range. Higher level index = slower; the floor is the SLOWEST level allowed.
        val floorLevel = pwrLevelFloor(setpoints, min, max)
        val cur = base.gpuLevel ?: min
        return if (cur < floorLevel) {
            val next = (cur + 1).coerceAtMost(floorLevel)
            actLevel(base, Direction.TIGHTEN, next, nextHint, "tighten GPU → level $next")
        } else {
            saturated(base, Direction.TIGHTEN, nextHint, "GPU at floor level (invariant)")
        }
    }

    // ── GPU FLOOR INVARIANT (LAW) ────────────────────────────────────────────────

    /**
     * The lowest GPU devfreq MAX (Hz) the controller may ever drive the GPU to: the
     * smallest real OPP that is ≥ [AdaptiveSetpoints.gpuFloorFraction] × [ceilHz], and
     * never below the lowest stock OPP. This is the construction-level guarantee that a
     * GPU 384 MHz-style collapse cannot recur — every tighten is clamped to it.
     */
    private fun floorMaxHz(setpoints: AdaptiveSetpoints, caps: TdpCaps, ceilHz: Long): Long {
        val steps = caps.gpuDevfreqStepsHz
        val lowestStock = steps.firstOrNull() ?: caps.gpuDevfreqFloorHz ?: ceilHz
        val target = (setpoints.gpuFloorFraction.toDouble() * ceilHz.toDouble()).toLong()
        // Smallest real OPP ≥ the fractional target; if none reaches it, the top OPP ≤ ceil.
        val snapped = steps.firstOrNull { it >= target }
            ?: steps.lastOrNull { it <= ceilHz }
            ?: lowestStock
        // Never below the lowest stock OPP — the floor LAW's hard lower bound.
        return maxOf(snapped, lowestStock)
    }

    /** Map the floor fraction onto the coarse pwrlevel range (higher index = slower). */
    private fun pwrLevelFloor(setpoints: AdaptiveSetpoints, minLevel: Int, maxLevel: Int): Int {
        if (maxLevel <= minLevel) return minLevel
        val span = (maxLevel - minLevel).toDouble()
        // Higher floor fraction (faster floor) ⇒ a LOWER (faster) level cap. The slowest
        // level we permit is (1 - fraction) of the way from min toward max.
        val offset = ((1.0 - setpoints.gpuFloorFraction.toDouble()).coerceIn(0.0, 1.0) * span).toInt()
        return (minLevel + offset).coerceIn(minLevel, maxLevel)
    }

    // ── OPP snapping (devfreq) ───────────────────────────────────────────────────

    /** Raise [curMax] one OPP toward [ceilHz] (never above the effective ceiling). */
    private fun stepUp(curMax: Long, steps: List<Long>, ceilHz: Long): Long? {
        val curIdx = indexOfOpp(curMax, steps)
        var nextIdx = curIdx + 1
        // Never above the effective ceiling.
        while (nextIdx <= steps.lastIndex && steps[nextIdx] > ceilHz) nextIdx--
        return if (nextIdx in 0..steps.lastIndex && nextIdx > curIdx) steps[nextIdx] else curMax
    }

    /**
     * Lower [curMax] by [stepN] OPPs toward [floorHz], clamped so the result is the
     * smallest OPP that is still ≥ [floorHz] (the GPU FLOOR INVARIANT). Returns the
     * floor OPP when the requested drop would breach it — never below.
     */
    private fun stepDown(curMax: Long, steps: List<Long>, floorHz: Long, stepN: Int): Long? {
        val curIdx = indexOfOpp(curMax, steps)
        val floorIdx = indexOfOpp(floorHz, steps).let { idx ->
            // Snap UP to the first OPP ≥ floorHz (the invariant is a lower bound).
            if (steps[idx] < floorHz && idx < steps.lastIndex) idx + 1 else idx
        }
        val targetIdx = (curIdx - stepN).coerceAtLeast(floorIdx)
        return if (targetIdx < curIdx) steps[targetIdx] else curMax
    }

    /** Build the (min, max) devfreq window for an operating [maxHz], min held below it. */
    private fun windowFor(
        maxHz: Long,
        setpoints: AdaptiveSetpoints,
        caps: TdpCaps,
        ceilHz: Long,
    ): Pair<Long, Long> {
        val steps = caps.gpuDevfreqStepsHz
        val maxIdx = indexOfOpp(maxHz, steps)
        val floorHz = floorMaxHz(setpoints, caps, ceilHz)
        // Target min ≈ MIN_BELOW_MAX_FRACTION of the max, snapped to a real OPP, but
        // strictly below the max OPP AND never below the floor invariant.
        val targetMin = (maxHz.toDouble() * MIN_BELOW_MAX_FRACTION).toLong()
        var minIdx = steps.indexOfLast { it <= targetMin }.let { if (it < 0) 0 else it }
        // Strictly below the max OPP.
        if (minIdx >= maxIdx) minIdx = (maxIdx - 1).coerceAtLeast(0)
        // Never below the floor invariant OPP (but still strictly below max).
        val floorIdx = indexOfOpp(floorHz, steps)
        if (minIdx < floorIdx) minIdx = floorIdx.coerceAtMost((maxIdx - 1).coerceAtLeast(0))
        return steps[minIdx] to maxHz
    }

    /** Index of the first OPP ≥ [hz] (clamped into range); the OPP this freq lives at. */
    private fun indexOfOpp(hz: Long, steps: List<Long>): Int {
        val idx = steps.indexOfFirst { it >= hz }
        return if (idx < 0) steps.lastIndex else idx
    }

    // ── State-folding helpers (mirror recordAction's acted/saturated split) ──────

    /** Record an APPLIED devfreq-window move: reset quiet ticks, pin direction + window. */
    private fun act(
        base: GpuControllerState,
        direction: Direction,
        level: Int?,
        minHz: Long,
        maxHz: Long,
        nextHint: Int,
        reason: String,
    ): Pair<GpuDecision, GpuControllerState> {
        val newState = base.copy(
            currentDirection = direction,
            quietTicks = 0,
            lastActedDirection = direction,
            gpuDevfreqMinHz = minHz,
            gpuDevfreqMaxHz = maxHz,
            gpuLevel = level ?: base.gpuLevel,
        )
        return GpuDecision(
            targetGpuLevel = level ?: base.gpuLevel,
            targetGpuDevfreqMinHz = minHz,
            targetGpuDevfreqMaxHz = maxHz,
            nextTickHintMs = nextHint,
            reason = reason,
        ) to newState
    }

    /** Record an APPLIED pwrlevel move (coarse mirror, no devfreq table). */
    private fun actLevel(
        base: GpuControllerState,
        direction: Direction,
        level: Int,
        nextHint: Int,
        reason: String,
    ): Pair<GpuDecision, GpuControllerState> {
        val newState = base.copy(
            currentDirection = direction,
            quietTicks = 0,
            lastActedDirection = direction,
            gpuLevel = level,
        )
        return GpuDecision(
            targetGpuLevel = level,
            targetGpuDevfreqMinHz = base.gpuDevfreqMinHz,
            targetGpuDevfreqMaxHz = base.gpuDevfreqMaxHz,
            nextTickHintMs = nextHint,
            reason = reason,
        ) to newState
    }

    /**
     * The lever was saturated (already at ceiling/floor — nothing moved). Hold the
     * actuators, accrue a quiet tick (advances cool-down), keep the direction so the
     * episode is honestly "still wanting this direction but stuck".
     */
    private fun saturated(
        base: GpuControllerState,
        direction: Direction,
        nextHint: Int,
        reason: String,
    ): Pair<GpuDecision, GpuControllerState> {
        val newState = base.copy(
            currentDirection = direction,
            quietTicks = (base.quietTicks + 1).coerceAtMost(QUIET_TICK_CAP),
        )
        return GpuDecision(
            targetGpuLevel = null,
            targetGpuDevfreqMinHz = null,
            targetGpuDevfreqMaxHz = null,
            nextTickHintMs = nextHint,
            reason = reason,
        ) to newState
    }

    /** Still confirming a direction — hold this tick, carry the advanced confirm count. */
    private fun confirmingHold(
        confirm: GpuControllerState,
        nextHint: Int,
        reason: String,
    ): Pair<GpuDecision, GpuControllerState> =
        GpuDecision(null, null, null, nextHint, reason) to confirm

    // ── FAST thermal regime + cadence ────────────────────────────────────────────

    /**
     * FAST (fast-down) regime: a tighten driven by a GENUINE thermal/throttle signal —
     * GPU die within [FAST_TIGHTEN_NEAR_SOFT_C] of soft, OR dTemp slope ≥
     * [FAST_TIGHTEN_DTEMP_C_PER_S], OR the kernel is already throttling (coolingMaxState
     * > 0). Reads the SHARED signals; never ORIGINATES a tighten (the band edge already
     * decided) — only chooses the confirm cadence (0 vs 2) and the step size.
     */
    private fun isFastRegime(signals: GpuSignals, setpoints: AdaptiveSetpoints): Boolean {
        val die = signals.smoothedDieTempC
        val nearSoft = die != null && die >= setpoints.gpuSoftTempC - FAST_TIGHTEN_NEAR_SOFT_C
        val heatingFast = signals.dTempSlopeCPerS?.let { it >= FAST_TIGHTEN_DTEMP_C_PER_S } ?: false
        val throttlingNow = (signals.coolingMaxState ?: 0) > 0
        return nearSoft || heatingFast || throttlingNow
    }

    /**
     * The re-eval cadence (ms) requested for the NEXT tick: [FAST_TICK_MS] when the GPU
     * die is heating (slope ≥ [WARM_DTEMP_C_PER_S]) or within [NEAR_SOFT_FAST_TICK_C] of
     * soft; [CALM_TICK_MS] otherwise. Floored at [FAST_TICK_MS] (2 Hz battery safety).
     */
    private fun nextTickHint(signals: GpuSignals, setpoints: AdaptiveSetpoints): Int {
        val die = signals.smoothedDieTempC
        val slope = signals.dTempSlopeCPerS
        val heating = slope != null && slope >= WARM_DTEMP_C_PER_S
        val nearSoft = die != null && die >= setpoints.gpuSoftTempC - NEAR_SOFT_FAST_TICK_C
        val hint = if (heating || nearSoft) FAST_TICK_MS else CALM_TICK_MS
        return hint.coerceAtLeast(FAST_TICK_MS)
    }
}

/**
 * The read-only thermal/throttle signals the GPU band controller consumes — the GPU
 * mirror of the engine's private `WindowSignals`, carrying ONLY the fields the brief
 * lists (the shared signal the CPU engine already builds; Unit 5 feeds the SAME values
 * here). PURE value object: no Android, I/O, or time.
 *
 * @property smoothedDieTempC  smoothed GPU die temp (°C), or null when unavailable.
 * @property dTempSlopeCPerS   EWMA dTemp slope (°C/s) once enough samples exist; null
 *                             otherwise.
 * @property coolingMaxState   max cooling_device cur_state seen this window (>0 = kernel
 *                             throttling NOW), or null when no cooling device is read.
 */
data class GpuSignals(
    val smoothedDieTempC: Int? = null,
    val dTempSlopeCPerS: Double? = null,
    val coolingMaxState: Int? = null,
)
