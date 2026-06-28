package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.thermal.CapFloor

/**
 * UNIT 4 (RICHER GOAL MODES) — the OUTER-SETPOINT controller for the three new
 * objective goal modes. PURE: no Android, no I/O, no time. Every function is a
 * deterministic value computation, unit-testable without a device.
 *
 * ## What "outer setpoint" means
 * The committed band controller ([AutoTdpEngine.decide]) is the INNER loop: it hunts
 * the lowest-power operating point that keeps the GPU busy% inside the active goal's
 * band, with thermal pre-empt, the 40% hard cap floor, cool-downs and the thermal
 * kill all enforced underneath. The three objective modes do NOT replace it — they
 * sit ON TOP, producing a cap CEILING (and, for FPS, an anti-tighten block) that the
 * service applies to the band controller's chosen target each tick.
 *
 * ## The one safety law of every outer setpoint here
 * An outer setpoint can only ever **TIGHTEN** the cap (lower the ceiling) — it is a
 * strictly-safer ADDITIONAL upper bound. It can NEVER raise the cap above what the
 * band controller chose, NEVER push below the 40% hard floor (every ceiling is snapped
 * through [CapFloor.snapCapToOpp], which raises sub-floor values up to the floor), and
 * NEVER disables the thermal kill / revert. The band controller's own invariants run
 * after — the cap the user actually gets is `min(bandControllerCap, outerCeiling)`,
 * then floored.
 *
 * ## Honesty
 * The TARGET_RUNTIME projection is MODELLED, not measured. [RuntimeBudget] carries the
 * [PowerModel.Confidence] of the fit it used (MEASURED with ≥3 real points, otherwise
 * ESTIMATED) and a human-readable note. We never claim a runtime the model can't back.
 */
object RuntimeBudgetController {

    /** Recompute cadence for the TARGET_RUNTIME outer loop. The service delays this long
     *  between recomputes; the controller itself is stateless/pure. */
    const val RECOMPUTE_INTERVAL_MS = 60_000L

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_RUNTIME — the 60s battery-budget outer loop
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The output of [computeRuntimeBudget].
     *
     * @param capCeilingKhz   The HARD cap ceiling (kHz) the band controller may tighten
     *                        below but never loosen above. Null when no ceiling could be
     *                        computed (no battery data / empty OPP table) — the service
     *                        then applies no runtime clamp this cycle (fails OPEN to the
     *                        band controller, which is itself always safe).
     * @param projectedHours  The MODELLED hours the device will last at [capCeilingKhz]'s
     *                        estimated draw given the remaining energy. Null when it can't
     *                        be modelled. This is a projection, never a guarantee.
     * @param achievable      True when an OPP exists whose modelled draw meets the budget
     *                        for the requested hours. False ⇒ even the lowest cap can't
     *                        reach the target; we still return the lowest safe ceiling and
     *                        say so honestly in [note].
     * @param confidence      Inherited from the [PowerModel] fit: MEASURED (≥3 real points)
     *                        or ESTIMATED (linear fallback / too few points).
     * @param note            Human-readable, honesty-labelled provenance for the HUD.
     */
    data class RuntimeBudget(
        val capCeilingKhz: Int?,
        val projectedHours: Double?,
        val achievable: Boolean,
        val confidence: PowerModel.Confidence,
        val note: String,
    )

    /**
     * Compute the runtime-budget cap ceiling for TARGET_RUNTIME.
     *
     * budgetW = remainingWh / targetHours; pick the LARGEST OPP whose modelled draw ≤
     * budgetW (most performance that still fits the budget). The draw model is, in
     * priority order:
     *   1. the fitted non-linear [PowerModel] (draw ∝ f^n) when ≥2 measured points exist
     *      (flagged MEASURED at ≥3, else ESTIMATED), else
     *   2. the linear proxy draw ≈ (cap/refCap)·refDraw using the live reference draw,
     *      flagged ESTIMATED.
     *
     * The chosen ceiling is snapped to a real OPP and raised to the 40% hard floor via
     * [CapFloor.snapCapToOpp] — so the ceiling can NEVER ask the band controller to floor
     * the cluster, even for an absurd target.
     *
     * @param remainingWh    Remaining battery energy in watt-hours (> 0 to compute).
     * @param targetHours    The user's "make it last H hours" setpoint (> 0).
     * @param oppStepsKhz    The big-cluster OPP table (ascending; from TdpCaps).
     * @param powerModelFit  Optional fitted model from this session's measured (cap,draw)
     *                       pairs. Null ⇒ linear fallback.
     * @param referenceCapKhz   A known cap for the linear fallback (e.g. the top OPP).
     * @param referenceDrawMilliW A known draw at [referenceCapKhz] (the live battery draw).
     */
    fun computeRuntimeBudget(
        remainingWh: Double,
        targetHours: Double,
        oppStepsKhz: List<Int>,
        powerModelFit: PowerModel.FitResult? = null,
        referenceCapKhz: Int? = null,
        referenceDrawMilliW: Long? = null,
    ): RuntimeBudget {
        val confidence = powerModelFit?.confidence ?: PowerModel.Confidence.ESTIMATED
        val steps = oppStepsKhz.sorted()

        if (steps.isEmpty() || remainingWh <= 0.0 || targetHours <= 0.0) {
            return RuntimeBudget(
                capCeilingKhz = null,
                projectedHours = null,
                achievable = false,
                confidence = confidence,
                note = "Runtime projection unavailable (no battery/OPP data) — modelled.",
            )
        }

        val budgetW = remainingWh / targetHours
        val budgetMw = (budgetW * 1_000.0).toLong()

        // Estimate draw at each OPP; keep those that fit the budget; pick the largest.
        // We compute draw for every step once (cheap; runs at most once per minute).
        val drawByStep: Map<Int, Long> = steps.associateWith { capKhz ->
            estimateDrawMw(capKhz, powerModelFit, referenceCapKhz, referenceDrawMilliW, steps)
        }

        val fitting = steps.filter { (drawByStep[it] ?: Long.MAX_VALUE) <= budgetMw }
        val achievable = fitting.isNotEmpty()

        // Desired ceiling: the largest fitting OPP, or — when NONE fit — the lowest OPP
        // (do the best we can while staying honest that the target isn't reachable).
        val desiredKhz = fitting.maxOrNull() ?: steps.first()

        // SAFETY: snap to a real OPP AND raise to the 40% hard floor. A ceiling at/above
        // the top OPP means "no ceiling needed" — but we still return the snapped value;
        // the clamp ([clampCapToCeiling]) treats a ceiling ≥ top as a no-op naturally.
        val ceilingKhz = CapFloor.snapCapToOpp(desiredKhz, steps)

        // Projected hours at the ceiling's modelled draw (honest readout for the HUD).
        val ceilingDrawMw = drawByStep[ceilingKhz] ?: estimateDrawMw(
            ceilingKhz, powerModelFit, referenceCapKhz, referenceDrawMilliW, steps,
        )
        val projectedHours: Double? =
            if (ceilingDrawMw > 0L) remainingWh / (ceilingDrawMw / 1_000.0) else null

        val confTag = if (confidence == PowerModel.Confidence.MEASURED) "measured" else "estimated"
        val note = if (achievable) {
            "Runtime budget: cap ≤ ${ceilingKhz / 1000} MHz for ~${"%.1f".format(targetHours)} h " +
                "(modelled, $confTag)."
        } else {
            "Target ~${"%.1f".format(targetHours)} h not reachable even at the lowest cap " +
                "(${ceilingKhz / 1000} MHz) — holding lowest safe cap (modelled, $confTag)."
        }

        return RuntimeBudget(
            capCeilingKhz = ceilingKhz,
            projectedHours = projectedHours,
            achievable = achievable,
            confidence = confidence,
            note = note,
        )
    }

    /**
     * Model the draw (mW) at [capKhz]: prefer the fitted [PowerModel], else the linear
     * proxy, else a final conservative top-OPP-scaled proxy. Returns [Long.MAX_VALUE]
     * only when truly nothing is known (so such a step never fits any finite budget).
     */
    private fun estimateDrawMw(
        capKhz: Int,
        fit: PowerModel.FitResult?,
        referenceCapKhz: Int?,
        referenceDrawMilliW: Long?,
        steps: List<Int>,
    ): Long {
        // Use the engine-grade estimator first (fit or explicit linear fallback).
        PowerModel.estimateDrawMilliW(
            capKhz = capKhz,
            fitResult = fit,
            referenceCapKhz = referenceCapKhz ?: steps.lastOrNull(),
            referenceDrawMilliW = referenceDrawMilliW,
        )?.let { return it.drawMilliW }

        // No fit and no reference draw: we genuinely cannot model this OPP's draw.
        return Long.MAX_VALUE
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_TEMP_CEILING — the outer temperature-ceiling guard
    // ════════════════════════════════════════════════════════════════════════════

    /** How far below the ceiling (°C) the guard starts tightening (the urgency margin). */
    const val TEMP_CEILING_TIGHTEN_MARGIN_C = 2

    /** How far below the ceiling (°C) the guard begins relaxing the cap-ceiling again. */
    const val TEMP_CEILING_RELAX_MARGIN_C = 6

    /**
     * The next cap-ceiling for TARGET_TEMP_CEILING, given the smoothed die temp and the
     * user's ceiling. This rides ON TOP of the inner COOL_QUIET pre-empt (which fires on
     * the goal's own soft temp): it makes an ARBITRARY user ceiling real by walking a
     * cap-ceiling down as the die approaches the ceiling and back up as it cools.
     *
     *   - die ≥ ceiling − [TEMP_CEILING_TIGHTEN_MARGIN_C]  → tighten the ceiling one OPP
     *     (cooler, lower power) — the urgency the user asked for.
     *   - die ≤ ceiling − [TEMP_CEILING_RELAX_MARGIN_C]    → relax the ceiling one OPP
     *     (give performance back) — never above the top OPP (= "no ceiling").
     *   - in between (hysteresis dead-band) → HOLD the current ceiling.
     *
     * SAFETY: the returned ceiling is always snapped to a real OPP and floored at 40%
     * via [CapFloor.snapCapToOpp]; it can only ever be applied as an upper bound by
     * [clampCapToCeiling], which never raises the band controller's cap.
     *
     * @param currentCeilingKhz the ceiling in force (null = none yet → start at top OPP).
     * @param smoothedDieC      the engine's smoothed die temp (null = unknown → HOLD).
     * @param ceilingC          the user's temperature ceiling (°C).
     * @param oppStepsKhz       big-cluster OPP table (ascending).
     * @return the next cap-ceiling (kHz), or null to apply no ceiling (no OPP table / no
     *         temp signal and no prior ceiling).
     */
    fun computeTempCeiling(
        currentCeilingKhz: Int?,
        smoothedDieC: Int?,
        ceilingC: Int,
        oppStepsKhz: List<Int>,
    ): Int? {
        val steps = oppStepsKhz.sorted()
        if (steps.isEmpty()) return currentCeilingKhz
        val top = steps.last()
        // No temperature signal: never invent a tighten; hold whatever ceiling exists.
        val die = smoothedDieC ?: return currentCeilingKhz

        val curIdx = currentCeilingKhz?.let { idxOfCeiling(it, steps) } ?: steps.lastIndex

        val next = when {
            die >= ceilingC - TEMP_CEILING_TIGHTEN_MARGIN_C -> {
                // Approaching/over the ceiling → tighten one OPP (never below 40% floor).
                val floorIdx = hardFloorIndex(steps)
                (curIdx - 1).coerceAtLeast(floorIdx)
            }
            die <= ceilingC - TEMP_CEILING_RELAX_MARGIN_C -> {
                // Comfortably below → relax one OPP back toward the top (= less restriction).
                (curIdx + 1).coerceAtMost(steps.lastIndex)
            }
            else -> curIdx // dead-band: hold
        }

        val ceilingKhz = steps[next]
        // A ceiling at/above the top OPP means "no restriction" — return it as the top
        // (clampCapToCeiling treats top as a no-op). Snap through the floor for safety.
        return if (ceilingKhz >= top) top else CapFloor.snapCapToOpp(ceilingKhz, steps)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_FPS_FLOOR — the outer anti-tighten block
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The fps-floor BLOCK: when a REAL frame source reports FPS below the floor, the
     * controller must NOT tighten further — it holds the knee (the last cap that still
     * met the floor). This returns the cap the service should APPLY this tick:
     *
     *   - real FPS available AND realFps < floor AND the proposed target would TIGHTEN
     *     (lower the cap) below the current cap → BLOCK: keep the current cap (the knee).
     *   - otherwise → allow the band controller's proposed cap unchanged.
     *
     * "real FPS" = [isRealFps] true (a measured frame source, not a refresh-rate guess).
     * When FPS is not real this block does nothing — the goal-resolution region has
     * already DEGRADED TARGET_FPS_FLOOR to BALANCED_SMART, so this guard never runs on a
     * fabricated number. Honest by construction.
     *
     * SAFETY: this only ever RAISES the proposed cap back to the current cap (or leaves
     * it) — strictly safer, never lowers a cap, never bypasses any invariant.
     *
     * @param proposedCapKhz the band controller's chosen cap (null = stock/top).
     * @param currentCapKhz  the cap currently applied (the knee candidate; null = stock).
     * @param realFpsX10     measured FPS ×10 (e.g. 595 = 59.5), or null.
     * @param isRealFps      true when [realFpsX10] is a measured frame rate.
     * @param fpsFloor       the user's minimum FPS.
     * @param oppStepsKhz    big-cluster OPP table (ascending).
     * @return the cap to apply (kHz), null = stock/top.
     */
    fun applyFpsFloorBlock(
        proposedCapKhz: Int?,
        currentCapKhz: Int?,
        realFpsX10: Int?,
        isRealFps: Boolean,
        fpsFloor: Int,
        oppStepsKhz: List<Int>,
    ): Int? {
        if (!isRealFps || realFpsX10 == null) return proposedCapKhz // no real signal → no block
        val belowFloor = realFpsX10 < fpsFloor * 10
        if (!belowFloor) return proposedCapKhz // FPS is at/above the floor → allow the tighten
        val steps = oppStepsKhz.sorted()
        if (steps.isEmpty()) return proposedCapKhz
        // Below the floor: block any tighten that would drop the cap below the current cap.
        // = the cap must not be lower than the current cap. min-OPP-index guard via clamp.
        val propIdx = proposedCapKhz?.let { idxOfCeiling(it, steps) } ?: steps.lastIndex
        val curIdx = currentCapKhz?.let { idxOfCeiling(it, steps) } ?: steps.lastIndex
        return if (propIdx < curIdx) {
            // Proposed is lower (tighter) than current → BLOCK: hold the current cap (knee).
            currentCapKhz
        } else {
            proposedCapKhz
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SHARED — the never-loosen-above cap-ceiling clamp
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Clamp a band-controller cap so it never exceeds [ceilingKhz] — the universal
     * "never loosen above the ceiling" rule shared by TARGET_RUNTIME and
     * TARGET_TEMP_CEILING.
     *
     *   appliedCap = min(bandControllerCap, ceiling)
     *
     * with the convention that a null cap means "stock = top OPP", so a ceiling below
     * the top OPP forces a cap even when the band controller proposed stock.
     *
     * The result is snapped to a real OPP (the ceiling already is; the band cap already
     * is). This only ever LOWERS the cap toward the ceiling (strictly safer); it never
     * raises a cap, and the band controller's 40% floor / thermal kill still run.
     *
     * @return the clamped cap (kHz), or null when no ceiling applies AND the proposed
     *         cap was null (stock).
     */
    fun clampCapToCeiling(
        proposedCapKhz: Int?,
        ceilingKhz: Int?,
        oppStepsKhz: List<Int>,
    ): Int? {
        if (ceilingKhz == null) return proposedCapKhz
        val steps = oppStepsKhz.sorted()
        if (steps.isEmpty()) return proposedCapKhz
        val top = steps.last()
        // A ceiling at/above the top OPP is "no restriction" → leave the proposed cap.
        if (ceilingKhz >= top) return proposedCapKhz
        // null proposed = stock/top → the ceiling becomes the cap.
        val propIdx = proposedCapKhz?.let { idxOfCeiling(it, steps) } ?: steps.lastIndex
        val ceilIdx = idxOfCeiling(ceilingKhz, steps)
        return if (ceilIdx < propIdx) steps[ceilIdx] else proposedCapKhz
    }

    // ── OPP-index helpers ────────────────────────────────────────────────────────

    /** The OPP index of [capKhz] (first step ≥ cap; the top when off-table-high). */
    private fun idxOfCeiling(capKhz: Int, steps: List<Int>): Int =
        steps.indexOfFirst { it >= capKhz }.let { if (it < 0) steps.lastIndex else it }

    /**
     * The lowest OPP index the cap may reach: the first OPP at/above 40% of the top.
     * Mirrors [CapFloor.hardFloorKhz]'s contract so the temp guard never tightens the
     * cap-ceiling below the engine's 40% hard floor.
     */
    private fun hardFloorIndex(steps: List<Int>): Int {
        val floorKhz = CapFloor.hardFloorKhz(steps) ?: return 0
        return idxOfCeiling(floorKhz, steps)
    }
}
