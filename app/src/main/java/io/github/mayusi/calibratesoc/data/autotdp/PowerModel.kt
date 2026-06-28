package io.github.mayusi.calibratesoc.data.autotdp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Non-linear power model for handheld DVFS draw estimation.
 *
 * Real-device DVFS power follows roughly draw ∝ f^n where n ∈ [2, 3] (voltage
 * scales with frequency; P = C·V²·f ≈ C·f^2.4 empirically on Arm big cores).
 * This replaces the linear draw∝freq proxy in [BatteryTarget] once ≥2 measured
 * (cap, draw) pairs are available in a session.
 *
 * ## Fit algorithm
 * Given a set of (capKhz, drawMilliW) pairs, we fit:
 *
 *   ln(draw) = ln(a) + n·ln(cap)
 *
 * by ordinary least squares in log-log space (a straight line). This gives us:
 *   n (the exponent) and a (the scale coefficient).
 *
 * The fitted exponent is clamped to [EXPONENT_MIN, EXPONENT_MAX] = [1.5, 3.0]
 * to prevent physically implausible results from noisy data.
 *
 * ## Honesty contract
 *  - < 2 points → cannot fit → returns null from [estimateDrawMilliW]; call
 *    site falls back to linear model, flagged ESTIMATED.
 *  - ≥ 2 points → fit attempted; if the fit is degenerate (zero variance in
 *    log-cap) → ESTIMATED fallback.
 *  - ≥ [MIN_POINTS_FOR_MEASURED] (3) points → result flagged MEASURED.
 *  - < [MIN_POINTS_FOR_MEASURED] but ≥ 2 → result flagged ESTIMATED even if
 *    the fit succeeded (too few points to trust for a runtime guarantee).
 *  - NEVER fabricates a draw value when data is absent. Returns null instead.
 *
 * ## Allocation contract
 * The [fit] step runs at most once per minute (called by Unit 4's TARGET_RUNTIME
 * path, not per-tick). [estimateDrawMilliW] is a pure scalar computation with
 * no allocations.
 *
 * PURE: no Android, no I/O, no time calls. All inputs are scalar values.
 */
object PowerModel {

    /** Minimum physically plausible DVFS exponent (sub-quadratic still possible at low V). */
    const val EXPONENT_MIN = 1.5

    /** Maximum physically plausible DVFS exponent (cubic max for standard CMOS). */
    const val EXPONENT_MAX = 3.0

    /** Default exponent used when no fit is possible (empirically typical arm64 big core). */
    const val EXPONENT_DEFAULT = 2.4

    /**
     * Minimum number of distinct (cap, draw) pairs required for the fit to be flagged
     * MEASURED rather than ESTIMATED. With < 3 points the least-squares line is
     * underdetermined (2 points always give a perfect fit regardless of noise) and
     * cannot be trusted for a runtime guarantee.
     */
    const val MIN_POINTS_FOR_MEASURED = 3

    // ── Honesty tier ──────────────────────────────────────────────────────────────

    /** Honesty label on [FitResult]. */
    enum class Confidence {
        /**
         * Fit from ≥ [MIN_POINTS_FOR_MEASURED] measured (cap, draw) pairs.
         * The estimate may be used for a runtime guarantee.
         */
        MEASURED,

        /**
         * Either < [MIN_POINTS_FOR_MEASURED] points (2-point fit is exact by
         * construction, not validated) or the fit fell back to the default exponent
         * due to degenerate data. Do NOT use for a hard runtime guarantee; treat as
         * a best-effort estimate.
         */
        ESTIMATED,
    }

    // ── Result types ──────────────────────────────────────────────────────────────

    /**
     * Output of [fit]: the fitted power-model parameters plus honesty metadata.
     *
     * @param scale      Scale coefficient `a` in draw = a · cap^n (mW when cap is in kHz).
     * @param exponent   Fitted exponent `n`, clamped to [EXPONENT_MIN, EXPONENT_MAX].
     * @param confidence [Confidence.MEASURED] when ≥ [MIN_POINTS_FOR_MEASURED] points
     *                   were used; [Confidence.ESTIMATED] otherwise.
     * @param pointCount Number of (cap, draw) pairs the fit was computed from.
     */
    data class FitResult(
        val scale: Double,
        val exponent: Double,
        val confidence: Confidence,
        val pointCount: Int,
    )

    /**
     * Result of [estimateDrawMilliW]: the draw estimate and honesty metadata.
     *
     * @param drawMilliW Estimated battery draw in milliwatts at [capKhz].
     * @param capKhz     The cap frequency this estimate is for.
     * @param confidence Inherited from the [FitResult] used, or ESTIMATED when the
     *                   linear fallback was used.
     * @param note       Human-readable provenance ("MEASURED-fit n=2.31" / "ESTIMATED-fallback").
     */
    data class DrawEstimate(
        val drawMilliW: Long,
        val capKhz: Int,
        val confidence: Confidence,
        val note: String,
    )

    // ── Core API ──────────────────────────────────────────────────────────────────

    /**
     * Fit the power model from a set of measured (capKhz, drawMilliW) pairs.
     *
     * Returns null when:
     *   - fewer than 2 pairs are provided (cannot fit a line), or
     *   - all cap values are identical (zero variance → degenerate fit).
     *
     * The exponent is clamped to [EXPONENT_MIN]..[EXPONENT_MAX] after fitting.
     * When the fit is degenerate (zero variance in log-cap) this returns null
     * rather than fabricating an exponent.
     *
     * @param points Map of capKhz → drawMilliW measured this session.
     *               Must contain ≥ 2 entries with positive cap and positive draw.
     * @return [FitResult] or null if the fit cannot be performed.
     */
    fun fit(points: Map<Int, Long>): FitResult? {
        // Filter to physically valid points only (positive cap AND positive draw).
        val valid = points.entries
            .filter { (cap, draw) -> cap > 0 && draw > 0 }
            .map { (cap, draw) -> cap.toDouble() to draw.toDouble() }

        if (valid.size < 2) return null

        // OLS in log-log space: ln(draw) = ln(a) + n·ln(cap)
        // Let x = ln(cap), y = ln(draw). Fit y = β0 + β1·x.
        val xs = valid.map { (cap, _) -> ln(cap) }
        val ys = valid.map { (_, draw) -> ln(draw) }
        val n = xs.size.toDouble()

        val xMean = xs.sum() / n
        val yMean = ys.sum() / n

        val ssXX = xs.sumOf { (it - xMean).pow(2) }
        val ssXY = xs.indices.sumOf { i -> (xs[i] - xMean) * (ys[i] - yMean) }

        // Degenerate: all log-cap values equal (e.g., all measurements at same OPP).
        if (abs(ssXX) < 1e-12) return null

        val slope = ssXY / ssXX          // = fitted exponent n
        val intercept = yMean - slope * xMean  // = ln(a)

        val clampedExponent = slope.coerceIn(EXPONENT_MIN, EXPONENT_MAX)
        val scale = Math.E.pow(intercept)

        val confidence = if (valid.size >= MIN_POINTS_FOR_MEASURED) {
            Confidence.MEASURED
        } else {
            Confidence.ESTIMATED
        }

        return FitResult(
            scale = scale,
            exponent = clampedExponent,
            confidence = confidence,
            pointCount = valid.size,
        )
    }

    /**
     * Estimate draw at [capKhz] using a [FitResult] from [fit].
     *
     * When [fitResult] is null (< 2 measured points), falls back to the linear
     * proxy: draw ≈ (capKhz / referenceCapKhz) · referenceDrawMilliW, flagged
     * ESTIMATED. Returns null when both the fit and the linear fallback inputs
     * are absent (honesty: never fabricate).
     *
     * @param capKhz             The cap frequency to estimate draw at (kHz).
     * @param fitResult          Output of [fit], or null when not yet available.
     * @param referenceCapKhz    Fallback: a known cap (e.g., current cap). Used only
     *                           when [fitResult] is null.
     * @param referenceDrawMilliW Fallback: measured draw at [referenceCapKhz]. Used
     *                            only when [fitResult] is null.
     * @return [DrawEstimate] or null when neither fit nor fallback inputs are available.
     */
    fun estimateDrawMilliW(
        capKhz: Int,
        fitResult: FitResult?,
        referenceCapKhz: Int? = null,
        referenceDrawMilliW: Long? = null,
    ): DrawEstimate? {
        if (capKhz <= 0) return null

        if (fitResult != null) {
            // Use the fitted model: draw = scale · cap^exponent
            val estimate = fitResult.scale * capKhz.toDouble().pow(fitResult.exponent)
            val drawMw = estimate.toLong().coerceAtLeast(1L)
            val tag = if (fitResult.confidence == Confidence.MEASURED) {
                "MEASURED-fit n=${"%.2f".format(fitResult.exponent)} (${fitResult.pointCount}pts)"
            } else {
                "ESTIMATED-fit n=${"%.2f".format(fitResult.exponent)} (${fitResult.pointCount}pts, <${MIN_POINTS_FOR_MEASURED} needed)"
            }
            return DrawEstimate(
                drawMilliW = drawMw,
                capKhz = capKhz,
                confidence = fitResult.confidence,
                note = tag,
            )
        }

        // Linear fallback: draw ∝ freq (same proxy as BatteryTarget's heuristic).
        // Honest: flag ESTIMATED always, since this is an approximation not a fit.
        if (referenceCapKhz != null && referenceCapKhz > 0 &&
            referenceDrawMilliW != null && referenceDrawMilliW > 0
        ) {
            val fraction = capKhz.toDouble() / referenceCapKhz.toDouble()
            val estimate = (fraction * referenceDrawMilliW).toLong().coerceAtLeast(1L)
            return DrawEstimate(
                drawMilliW = estimate,
                capKhz = capKhz,
                confidence = Confidence.ESTIMATED,
                note = "ESTIMATED-fallback linear (no fit: <2 measured points)",
            )
        }

        // No data at all — honest null, never fabricate.
        return null
    }
}
