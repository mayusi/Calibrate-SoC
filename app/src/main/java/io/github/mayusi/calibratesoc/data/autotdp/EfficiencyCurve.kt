package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.benchmark.BenchConfig
import io.github.mayusi.calibratesoc.data.benchmark.NativeBench
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Pure data + math (no Android, fully unit-testable)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One measured point on the big-cluster efficiency curve.
 *
 * [capKhz]       — the big-cluster scaling_max_freq cap that was in effect.
 * [perfScore]    — score from the synthetic load kernel (higher = better).
 * [drawMw]       — mean battery draw in milliwatts during the measurement window.
 *                  Positive = discharging (matches [Telemetry.batteryDrawMilliW] convention).
 * [perfPerWatt]  — perfScore / (drawMw / 1000.0). The higher this is, the better
 *                  the perf-per-watt at this cap. The knee is the cap with the
 *                  highest perfPerWatt.
 *
 * Note: perfPerWatt is Infinity when drawMw == 0 (device is charging during the
 * sweep — honesty requires the caller to check [drawMw] > 0 before trusting the
 * knee selection).
 */
data class CurvePoint(
    val capKhz: Int,
    val perfScore: Double,
    val drawMw: Long,
    val perfPerWatt: Double,
) {
    companion object {
        /**
         * Builds a [CurvePoint] and computes [perfPerWatt] from raw measurements.
         *
         * @param capKhz     The big-cluster cap that was active (kHz).
         * @param perfScore  Score from the load kernel at this cap.
         * @param drawMw     Mean battery draw in mW during the measurement window.
         *                   Must be >= 0. Zero → [perfPerWatt] = 0.0 (can't trust).
         */
        fun of(capKhz: Int, perfScore: Double, drawMw: Long): CurvePoint {
            val ppw = if (drawMw > 0) perfScore / (drawMw / 1_000.0) else 0.0
            return CurvePoint(capKhz = capKhz, perfScore = perfScore, drawMw = drawMw, perfPerWatt = ppw)
        }
    }
}

/**
 * The result of the efficiency-knee search.
 *
 * Labelled "approximate, measured on your device" — the sweep uses a light
 * synthetic load, real workloads will differ. The knee is a good starting
 * point; the AutoTDP daemon refines dynamically once the live rung is available.
 */
data class CurveResult(
    /** All measured points, ordered by capKhz ascending. */
    val points: List<CurvePoint>,
    /** The knee: the cap with the best perf-per-watt. Null when < 2 valid points. */
    val knee: CurvePoint?,
    /** Human-readable summary of the finding. */
    val summary: String,
)

/**
 * Pure knee-finding math.
 *
 * The "knee" is defined as the [CurvePoint] with the highest [CurvePoint.perfPerWatt]
 * ratio, i.e. the cap that delivers the most performance per watt. This is the
 * efficiency optimum — running above this cap spends disproportionately more power
 * for marginal performance gains; running below it sacrifices performance without
 * proportional power savings.
 *
 * Edge cases:
 *  - Empty list → null (no data).
 *  - Single point → null (can't identify a knee with one sample; the curve needs
 *    at least two points to establish a slope).
 *  - All points have drawMw == 0 (device was charging during the sweep) → null
 *    (honesty: we can't compute a valid perf-per-watt without real discharge data).
 *  - Tie → the lower-cap point wins (more conservative = more efficient).
 *
 * @param points List of measured [CurvePoint] from the sweep. Order does not matter.
 * @return The knee point, or null when there is insufficient / untrusted data.
 */
fun findKnee(points: List<CurvePoint>): CurvePoint? {
    if (points.size < 2) return null

    // Filter to points where we have a real discharge reading.
    val valid = points.filter { it.drawMw > 0 && it.perfPerWatt > 0.0 }
    if (valid.isEmpty()) return null
    // Still require at least 2 valid points to call it a curve.
    if (valid.size < 2) return null

    // Best perf-per-watt wins. On a tie, take the lower-cap (more conservative)
    // point. maxWithOrNull returns the comparator-maximum, so the tie-break key
    // must rank the LOWER cap as "greater": thenByDescending { capKhz } makes a
    // smaller capKhz compare greater, so the max of a tie is the lowest cap.
    return valid.maxWithOrNull(
        compareBy<CurvePoint> { it.perfPerWatt }.thenByDescending { it.capKhz }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sweep coordinator (Android coroutine — thin layer, keeps knee math pure)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Progress event emitted during an efficiency curve sweep.
 */
sealed interface SweepProgress {
    /** Currently measuring at this OPP cap. [stepIndex] is 1-based. */
    data class Measuring(val capKhz: Int, val stepIndex: Int, val totalSteps: Int) : SweepProgress
    /** A point has been collected at [point]. */
    data class PointCollected(val point: CurvePoint) : SweepProgress
    /** Sweep finished. [result] carries the curve + knee. */
    data class Finished(val result: CurveResult) : SweepProgress
    /** Sweep aborted (charging, insufficient telemetry, etc.). */
    data class Aborted(val reason: String) : SweepProgress
}

/**
 * Efficiency Curve sweep coordinator.
 *
 * Steps the big-cluster cap DOWN through the OPP table (from top to bottom)
 * under a fixed synthetic load, measures performance + battery draw at each
 * step, then finds the knee.
 *
 * ARCHITECTURE NOTE: This class coordinates the sweep but does NOT write any
 * sysfs nodes itself. It returns a [Flow] of [SweepProgress] so the caller
 * (the future AutoTDP UI ViewModel) can:
 *   1. Apply the cap via TunableWriter / WriterRegistry before each step.
 *   2. Feed [Telemetry] samples via the [telemetrySource] parameter.
 *
 * The sweep is deliberately NOT wired to TunableWriter/MonitorService here
 * because those are Android-bound singletons that other agents own. The seam
 * is clean: the caller applies the cap, then calls [sweep] which handles
 * timing and measurement.
 *
 * TELEMETRY SOURCE: The caller must supply a function that returns the latest
 * [Telemetry] (or null when unavailable). This keeps the sweep coordinator
 * testable and decoupled from MonitorService.
 *
 * HONESTY: The sweep label in [CurveResult.summary] says "approximate, measured
 * on your device" — it is NOT a definitive measurement, just a starting calibration.
 */
@Singleton
class EfficiencyCurveSweep @Inject constructor() {

    /**
     * Default measurement window per OPP step (wall-clock ms).
     * 4 seconds is enough for battery draw to stabilise at a given load
     * without dominating the total sweep time.
     */
    private val defaultStepMs = 4_000L

    /**
     * Default synthetic load duration per measurement (ms).
     * We run the NativeBench CPU kernel for this long to create a
     * reproducible load on the cores. The score accumulated over
     * [defaultStepMs] is the perfScore for that cap.
     */
    private val loadBudgetMs = 3_000L

    /**
     * Run a full big-cluster efficiency sweep, stepping from the top OPP
     * down to the bottom. Emits [SweepProgress] events; collect with
     * [kotlinx.coroutines.flow.collect].
     *
     * @param caps             Device envelope — determines the OPP steps to sweep.
     * @param telemetrySource  Callback returning the latest [Telemetry] sample,
     *                         or null when telemetry is temporarily unavailable.
     *                         Called once per OPP step after the load run to
     *                         measure battery draw.
     * @param stepMs           Duration of each OPP-step measurement window (ms).
     *                         Defaults to [defaultStepMs].
     * @param cpuIterations    Iteration count per NativeBench.runCpu call.
     *                         Defaults to [BenchConfig.cpuIterations].
     */
    fun sweep(
        caps: TdpCaps,
        telemetrySource: () -> Telemetry?,
        stepMs: Long = defaultStepMs,
        cpuIterations: Int = BenchConfig().cpuIterations,
    ): Flow<SweepProgress> = flow {
        val steps = caps.bigClusterOppStepsKhz.sortedDescending() // top → bottom
        if (steps.isEmpty()) {
            emit(SweepProgress.Aborted("No OPP steps available for big cluster (policy ${caps.bigPolicyId})."))
            return@flow
        }

        val points = mutableListOf<CurvePoint>()

        steps.forEachIndexed { idx, capKhz ->
            if (!currentCoroutineContext().isActive) return@flow // cooperative cancellation

            emit(SweepProgress.Measuring(capKhz = capKhz, stepIndex = idx + 1, totalSteps = steps.size))

            // ── Run synthetic load ────────────────────────────────────────────
            // NOTE: The cap write must be done by the CALLER before consuming
            // the next event — the sweep trusts that the caller has applied the
            // cap via TunableWriter before the measurement window starts.
            // We add a short settle delay so the kernel governor has time to
            // react to the cap write before we start measuring.
            delay(500L) // governor settle time

            val perfScore = withContext(Dispatchers.Default) {
                runForBudget(loadBudgetMs, cpuIterations)
            }

            // ── Sample battery draw ───────────────────────────────────────────
            // Take multiple samples over the remaining window and average.
            val drawSamples = mutableListOf<Long>()
            val deadline = System.currentTimeMillis() + (stepMs - loadBudgetMs - 500L).coerceAtLeast(200L)
            while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
                val t = telemetrySource()
                val draw = t?.batteryDrawMilliW
                if (draw != null && draw > 0) drawSamples += draw
                delay(250L)
            }

            val meanDraw = if (drawSamples.isNotEmpty()) {
                drawSamples.sum() / drawSamples.size
            } else {
                0L // no valid draw data — perfPerWatt will be 0.0 (excluded by findKnee)
            }

            val point = CurvePoint.of(capKhz = capKhz, perfScore = perfScore.toDouble(), drawMw = meanDraw)
            points += point
            emit(SweepProgress.PointCollected(point))
        }

        // ── Find knee ────────────────────────────────────────────────────────
        val knee = findKnee(points)
        val summary = buildKneeSummary(knee, points)
        emit(SweepProgress.Finished(CurveResult(points = points.sortedBy { it.capKhz }, knee = knee, summary = summary)))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Run [NativeBench.runCpu] for [budgetMs] of wall-clock, accumulating score. */
    private fun runForBudget(budgetMs: Long, iterations: Int): Long {
        val deadline = System.currentTimeMillis() + budgetMs
        var total = 0L
        do {
            total += runCatching { NativeBench.runCpu(iterations) }.getOrDefault(0L)
        } while (System.currentTimeMillis() < deadline)
        return total
    }

    private fun buildKneeSummary(knee: CurvePoint?, points: List<CurvePoint>): String {
        if (knee == null) {
            return "Could not identify an efficiency knee — fewer than 2 valid points " +
                "with real battery draw data. Run the sweep while discharging."
        }
        val kneeMhz = knee.capKhz / 1000
        val ppw = "%.1f".format(knee.perfPerWatt)
        return "Approximate efficiency knee (measured on your device): ${kneeMhz} MHz " +
            "(perf/W = $ppw). ${points.size} OPP steps measured. " +
            "Cap this cluster at ${kneeMhz} MHz for best sustained efficiency. " +
            "Note: this is a synthetic measurement — real workloads may differ."
    }
}
