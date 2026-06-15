package io.github.mayusi.calibratesoc.data.efficiency

import io.github.mayusi.calibratesoc.data.autotdp.CurveResult
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Plan output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * An efficiency plan produced by [EfficiencyAdvisor.buildPlan].
 *
 * This is always available regardless of voltage-table capability. On stock
 * firmware it represents the "knee-equivalent undervolting" strategy: cap each
 * CPU cluster at its measured perf-per-watt knee and let the DVFS governor
 * manage voltages on the efficient part of the V/F curve. The power savings
 * are real even though no voltage register is touched.
 *
 * [bigClusterKneeCap]     — Recommended scaling_max_freq cap (kHz) for the
 *   big/gold cluster. Derived from [CurveResult.knee] when a sweep has been
 *   run; null when no sweep data is available.
 *
 * [primeClusterKneeCap]   — Same for the prime cluster. On a 2-cluster device
 *   this may equal [bigClusterKneeCap] (single high-OPP policy). Null when no
 *   measured data.
 *
 * [gpuPowerLevelFloor]    — Adreno max_pwrlevel recommendation. The advisor
 *   sets this to one level ABOVE the hardware minimum (one step below peak GPU
 *   performance) to reduce GPU voltage without losing most GPU throughput. Null
 *   when no Adreno GPU or no power-level control is available.
 *
 * [estimatedDrawReductionPct] — Approximate battery-draw reduction percentage
 *   vs. uncapped operation. Non-null and MEASURED when derived from a completed
 *   [CurveResult]; non-null but ESTIMATED (a conservative floor) when not.
 *
 * [drawEstimateSource]    — [EstimateSource.MEASURED] when derived from a real
 *   sweep; [EstimateSource.ESTIMATED] when a static heuristic was used because
 *   no sweep data is available. The UI must label these differently — never
 *   present an ESTIMATED figure as if it were a measurement.
 *
 * [summaryText]           — Human-readable plan description for display.
 *
 * [requiresSweep]         — True when the plan could be meaningfully refined by
 *   running an [EfficiencyCurve] sweep. Always true when [drawEstimateSource]
 *   is [EstimateSource.ESTIMATED].
 */
data class EfficiencyPlan(
    val bigClusterKneeCap: Int?,
    val primeClusterKneeCap: Int?,
    val gpuPowerLevelFloor: Int?,
    val estimatedDrawReductionPct: Int?,
    val drawEstimateSource: EstimateSource,
    val summaryText: String,
    val requiresSweep: Boolean,
)

/**
 * Whether the draw-reduction estimate is based on a real device measurement
 * or a static heuristic.
 */
enum class EstimateSource {
    /** Derived from a completed [CurveResult] with valid battery draw data. */
    MEASURED,
    /** No sweep data available; based on a conservative static heuristic. */
    ESTIMATED,
}

// ─────────────────────────────────────────────────────────────────────────────
//  Advisor
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Produces an [EfficiencyPlan] from device capability and (optionally) a
 * completed efficiency curve sweep. This is the always-available honest path
 * for "undervolt-adjacent" efficiency on locked Snapdragon handhelds.
 *
 * ARCHITECTURE: Pure function — no I/O, no Android, no coroutines. Reuses the
 * knee math from [io.github.mayusi.calibratesoc.data.autotdp.findKnee] via
 * [CurveResult]; does NOT duplicate the slope calculation.
 *
 * HONESTY CONTRACT:
 *  - [drawEstimateSource] is MEASURED only when [curveResult] is non-null AND
 *    its knee has a valid discharge reading (drawMw > 0).
 *  - "Run the sweep to measure" advice is emitted whenever no curve is present.
 *  - Knee-cap values are null when no sweep data exists; the UI must gate any
 *    "apply" button on a non-null knee cap.
 *
 * @param report   The device [CapabilityReport] from the capability layer.
 * @param caps     [TdpCaps] derived from [report] — provides cluster OPP steps
 *                 and GPU level range. Pre-computed to keep this advisor pure.
 * @param curveResult Optional completed sweep result from [EfficiencyCurveSweep].
 *                 Pass null when the user has not run a sweep yet.
 */
@Singleton
class EfficiencyAdvisor @Inject constructor() {

    /**
     * Build the best available [EfficiencyPlan] for this device.
     *
     * When [curveResult] is provided and has a valid knee, the plan caps the
     * big cluster at the knee frequency and computes a measured draw reduction.
     * When no curve is available, the plan recommends the sweep and uses a
     * conservative static estimate.
     */
    fun buildPlan(
        report: CapabilityReport,
        caps: TdpCaps,
        curveResult: CurveResult? = null,
    ): EfficiencyPlan {
        // ── GPU floor ──────────────────────────────────────────────────────────
        // Adreno: level 0 = fastest. Recommend capping at one level above min
        // (i.e. min+1) to stay off the highest-voltage peak while retaining GPU
        // throughput. If the range is only one level (gpuMinLevel == gpuMaxLevel)
        // keep it as-is.
        val gpuFloor = computeGpuFloor(caps)

        // ── Big cluster knee cap ───────────────────────────────────────────────
        val knee = curveResult?.knee

        return if (knee != null && knee.drawMw > 0) {
            buildMeasuredPlan(caps = caps, knee = knee, curveResult = curveResult, gpuFloor = gpuFloor)
        } else {
            buildEstimatedPlan(caps = caps, curveResult = curveResult, gpuFloor = gpuFloor)
        }
    }

    // ─── Measured plan (sweep ran, knee identified) ───────────────────────────

    private fun buildMeasuredPlan(
        caps: TdpCaps,
        knee: io.github.mayusi.calibratesoc.data.autotdp.CurvePoint,
        curveResult: CurveResult,
        gpuFloor: Int?,
    ): EfficiencyPlan {
        val kneeMhz = knee.capKhz / 1_000
        val topOpp  = caps.bigClusterOppStepsKhz.maxOrNull() ?: knee.capKhz
        val topMhz  = topOpp / 1_000

        // Compute expected draw reduction: compare knee draw vs the highest-OPP
        // point in the curve (the uncapped operating point).
        val topPoint = curveResult.points
            .filter { it.drawMw > 0 }
            .maxByOrNull { it.capKhz }

        val drawReductionPct: Int? = if (topPoint != null && topPoint.drawMw > 0 &&
            topPoint.capKhz > knee.capKhz) {
            val reduction = (topPoint.drawMw - knee.drawMw) * 100.0 / topPoint.drawMw
            reduction.toInt().coerceIn(0, 99)
        } else {
            null
        }

        // Prime cluster: same knee cap if it is on the big policy (2-cluster
        // device); otherwise null (we haven't swept the prime cluster separately —
        // the EfficiencyCurve sweep only sweeps one policy at a time).
        val primeCap = if (caps.primeCoreIndices.isEmpty() ||
            caps.bigPolicyId == (caps.bigClusterOppStepsKhz.firstOrNull()?.let { caps.bigPolicyId })) {
            // On a 3-cluster device, prime gets the same advisory until a separate
            // prime sweep is implemented. Conservative: don't cap tighter than knee.
            null
        } else {
            null // Separate prime sweep not yet implemented — do not guess.
        }

        val sb = StringBuilder()
        sb.append("Efficiency knee measured at $kneeMhz MHz (vs uncapped $topMhz MHz). ")
        if (drawReductionPct != null) {
            sb.append("Estimated draw reduction: ~$drawReductionPct% vs uncapped. ")
        }
        if (gpuFloor != null) {
            sb.append("GPU: capped at power level $gpuFloor (one step below peak). ")
        }
        sb.append("Measurement is approximate (synthetic load) — real workloads may differ.")

        return EfficiencyPlan(
            bigClusterKneeCap       = knee.capKhz,
            primeClusterKneeCap     = primeCap,
            gpuPowerLevelFloor      = gpuFloor,
            estimatedDrawReductionPct = drawReductionPct,
            drawEstimateSource      = EstimateSource.MEASURED,
            summaryText             = sb.toString(),
            requiresSweep           = false,
        )
    }

    // ─── Estimated plan (no sweep yet) ───────────────────────────────────────

    private fun buildEstimatedPlan(
        caps: TdpCaps,
        curveResult: CurveResult?,
        gpuFloor: Int?,
    ): EfficiencyPlan {
        val noKneeReason = when {
            curveResult == null -> "No sweep has been run yet."
            curveResult.points.isEmpty() -> "Sweep produced no data points."
            curveResult.knee == null -> "Could not identify a knee — fewer than 2 valid " +
                "points with real discharge data."
            curveResult.knee.drawMw <= 0 -> "Knee point has no valid battery draw reading " +
                "(device was charging during sweep?)."
            else -> "No knee available."
        }

        // Conservative static hint: capping at ~80% of the top OPP is a common
        // rule-of-thumb for Snapdragon devices. Expressed as a note, not as an
        // "apply" target, because it is not measured.
        val topOpp = caps.bigClusterOppStepsKhz.maxOrNull()
        val hintMhz = topOpp?.let { (it * 0.80).toInt() / 1_000 }

        val sb = StringBuilder()
        sb.append("No measured efficiency knee available. $noKneeReason ")
        sb.append("Run the Efficiency Sweep to measure your device's perf-per-watt curve. ")
        if (hintMhz != null) {
            sb.append("Rough heuristic: ~$hintMhz MHz (~80% of peak) is a common starting " +
                "point for Snapdragon, but this is NOT a measured value. ")
        }
        if (gpuFloor != null) {
            sb.append("GPU: power level $gpuFloor recommended (one step below peak, no sweep needed). ")
        }

        return EfficiencyPlan(
            bigClusterKneeCap         = null,
            primeClusterKneeCap       = null,
            gpuPowerLevelFloor        = gpuFloor,
            estimatedDrawReductionPct = STATIC_DRAW_REDUCTION_ESTIMATE_PCT,
            drawEstimateSource        = EstimateSource.ESTIMATED,
            summaryText               = sb.toString(),
            requiresSweep             = true,
        )
    }

    // ─── GPU floor helper ─────────────────────────────────────────────────────

    /**
     * Returns the recommended Adreno max_pwrlevel cap.
     *
     * Adreno power levels: 0 = fastest (lowest index), N = slowest (highest index).
     * We recommend capping at [gpuMinLevel + 1] — one step above the hardware
     * minimum level — to keep most GPU performance while staying off the
     * highest-voltage OPP.
     *
     * If gpuMinLevel == gpuMaxLevel (only one level) or control is unavailable,
     * returns null (no cap to apply).
     */
    private fun computeGpuFloor(caps: TdpCaps): Int? {
        val minLevel = caps.gpuMinLevel ?: return null
        val maxLevel = caps.gpuMaxLevel ?: return null
        if (minLevel >= maxLevel) return null // single level or inverted range — don't cap
        // Cap one level above minimum (i.e. still allow the second-fastest level).
        // This means: max_pwrlevel = minLevel + 1 (Adreno will not go faster than this).
        return (minLevel + 1).coerceAtMost(maxLevel)
    }

    companion object {
        /**
         * Conservative static draw-reduction estimate used when no sweep data
         * is available. Chosen as a pessimistic floor (not a promise): real
         * measurements on Snapdragon 865/888/8 Gen 2 show 10–25% draw reduction
         * from knee-capping at ~80% of max OPP under typical game load.
         */
        const val STATIC_DRAW_REDUCTION_ESTIMATE_PCT: Int = 10
    }
}
