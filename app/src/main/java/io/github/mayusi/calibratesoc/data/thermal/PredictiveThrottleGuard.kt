package io.github.mayusi.calibratesoc.data.thermal

import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp

/**
 * Predictive throttle guard — pure function engine.
 *
 * Given a short telemetry window (most-recent-first or oldest-first list of
 * [TelemetryPoint]s capturing per-core clock headroom and the hottest CPU
 * thermal zone), this engine answers two questions:
 *
 *  1. Will the device hit a kernel trip/kill point within [horizonSeconds]?
 *  2. If yes, what big-cluster cap should we pre-emptively apply to keep the
 *     temperature rising slowly enough that FPS stays flat?
 *
 * The goal is to avoid the kernel's own force-throttle cliff: if we catch the
 * trend early and apply a gentler cap ourselves, the FPS degradation is smooth
 * and barely visible instead of a sudden 30% drop when the DVFS governor is
 * overridden by thermal management.
 *
 * ## Algorithm
 *
 *  1. Extract the hottest CPU zone temperature from each window point.
 *  2. Compute a linear rate of temperature rise (°C/s) via least-squares over
 *     the window. Using LS instead of a simple delta makes it resistant to
 *     single-sample noise (common in SoC thermal zones which update at ~1 Hz).
 *  3. Project: tempNow + ratePerSec * horizonSeconds → predicted temp.
 *  4. If predicted temp >= [tripPointC] → forecast imminent throttle.
 *  5. Recommended cap: step the big-cluster (highest policyId) max frequency
 *     down by [capStepFraction] relative to the current cap. Returns the
 *     next-lower available frequency from the window if available, else a
 *     fractional estimate.
 *
 * ## Honesty / purity contract
 *
 *  - NO Android framework imports. The function takes plain data; the caller
 *    is responsible for feeding live [Telemetry] samples.
 *  - Returns [ThrottleForecast.noAction] when the window is too short, the
 *    temperature is stable/falling, or headroom is large.
 *  - The recommended cap is advisory: it is never applied by this class.
 *    Only the [ForegroundAppWatcher]/service integration layer writes tunables.
 *
 * ## Units
 *
 *  - Temperatures: milliCelsius (raw from [ZoneTemp.tempMilliC]), converted
 *    internally to Celsius for human-readable reasons fields.
 *  - Clocks: kHz, matching [Telemetry.perCoreCpuFreqKhz].
 *  - Time: milliseconds for timestamps, seconds for rates/projections.
 */
object PredictiveThrottleGuard {

    /**
     * Default thermal trip point in °C.
     *
     * Most Qualcomm / MediaTek SoCs start DVFS throttling at 90–95 °C and
     * issue a kernel shutdown around 105–110 °C.  We use 90 °C as the
     * conservative trip threshold so we intervene before the kernel's own
     * throttle governor fires.  Callers may override this via [tripPointC].
     */
    const val DEFAULT_TRIP_POINT_C: Float = 90f

    /**
     * How far ahead (seconds) to project the temperature trend.
     * 10 s gives the service time to cap + for the SoC to respond.
     */
    const val DEFAULT_HORIZON_SECONDS: Int = 10

    /**
     * Minimum window size required to compute a meaningful trend.
     * With a 1 Hz sample rate, 3 points = 3 seconds of history.
     */
    const val MIN_WINDOW_POINTS: Int = 3

    /**
     * Default fractional step down for the recommended cap.
     * 0.10 = reduce max clock by 10 % relative to current cap.
     */
    const val DEFAULT_CAP_STEP_FRACTION: Float = 0.10f

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One point in the telemetry window supplied to [predict].
     *
     * Extracted from [Telemetry] by the caller so this engine stays pure.
     *
     * @param timestampMs   Epoch ms (System.currentTimeMillis() / elapsedMs).
     *                      Points must be in ascending temporal order.
     * @param cpuZoneTempsMilliC  All CPU thermal zone readings at this instant
     *                            (milliCelsius). The engine takes the maximum.
     * @param bigClusterMaxKhz    Current max clock of the big/prime cluster
     *                            (highest policy id). 0 when unavailable.
     */
    data class TelemetryPoint(
        val timestampMs: Long,
        val cpuZoneTempsMilliC: List<Int>,
        val bigClusterMaxKhz: Int,
    ) {
        /** Hottest CPU zone in °C, or null when no zones reported. */
        val hottestTempC: Float?
            get() = cpuZoneTempsMilliC.maxOrNull()?.let { it / 1000f }

        companion object {
            /**
             * Convenience constructor from a live [Telemetry] sample.
             * Filters [ZoneTemp] entries to CPU-like zones by label heuristic.
             */
            fun from(t: Telemetry): TelemetryPoint {
                val cpuMilliC = t.zoneTempsMilliC
                    .filter { isCpuZone(it.label) }
                    .map { it.tempMilliC }
                    .ifEmpty { t.zoneTempsMilliC.map { it.tempMilliC } } // fallback: all zones
                val bigMax = t.perCoreCpuFreqKhz.maxOrNull() ?: 0
                return TelemetryPoint(
                    timestampMs = t.timestampMs,
                    cpuZoneTempsMilliC = cpuMilliC,
                    bigClusterMaxKhz = bigMax,
                )
            }

            private fun isCpuZone(label: String): Boolean {
                val l = label.lowercase()
                return "cpu" in l || "soc" in l || "core" in l
            }
        }
    }

    /**
     * Predict whether a thermal throttle is imminent given the supplied window.
     *
     * @param window         Telemetry points in ascending time order (oldest first).
     *                       Must have at least [MIN_WINDOW_POINTS] entries for a
     *                       meaningful forecast; fewer returns [ThrottleForecast.noAction].
     * @param tripPointC     Kernel trip/throttle temperature in °C.
     *                       Defaults to [DEFAULT_TRIP_POINT_C].
     * @param horizonSeconds Look-ahead in seconds. Defaults to [DEFAULT_HORIZON_SECONDS].
     * @param capStepFraction Fraction by which to reduce big-cluster max freq.
     *                       0.10 = 10% reduction.
     *
     * @return [ThrottleForecast] carrying the prediction and the recommended cap
     *         (null cap when no action is needed).
     */
    fun predict(
        window: List<TelemetryPoint>,
        tripPointC: Float = DEFAULT_TRIP_POINT_C,
        horizonSeconds: Int = DEFAULT_HORIZON_SECONDS,
        capStepFraction: Float = DEFAULT_CAP_STEP_FRACTION,
    ): ThrottleForecast {
        if (window.size < MIN_WINDOW_POINTS) {
            return ThrottleForecast.noAction("window too small (${window.size} < $MIN_WINDOW_POINTS)")
        }

        // Extract hot temp series — skip points with no thermal data.
        val temps: List<Pair<Long, Float>> = window
            .mapNotNull { pt -> pt.hottestTempC?.let { pt.timestampMs to it } }

        if (temps.size < MIN_WINDOW_POINTS) {
            return ThrottleForecast.noAction("insufficient thermal data (${temps.size} valid points)")
        }

        // Linear regression: temp = slope * time + intercept, time in seconds.
        val t0 = temps.first().first
        val xs = temps.map { (ts, _) -> (ts - t0) / 1000.0 }
        val ys = temps.map { (_, tc) -> tc.toDouble() }
        val slope = linearSlopePerSec(xs, ys)

        val currentTempC = temps.last().second
        val headroomC = tripPointC - currentTempC

        if (slope <= 0f || headroomC <= 0f) {
            // Temperature is stable or cooling — no action needed.
            return ThrottleForecast.noAction(
                if (headroomC <= 0f) "already at/above trip point (${currentTempC}°C >= ${tripPointC}°C)"
                else "temperature stable/cooling (slope=${slope.fmt(2)}°C/s)"
            )
        }

        // Project: how many seconds until temp hits tripPointC?
        val secondsToTrip = headroomC / slope
        val willThrottleInSec = secondsToTrip.toInt().coerceAtLeast(0)

        if (secondsToTrip > horizonSeconds) {
            // Outside the look-ahead horizon — log the slope but don't act yet.
            return ThrottleForecast.noAction(
                "throttle in ~${willThrottleInSec}s, outside ${horizonSeconds}s horizon " +
                    "(slope=${slope.fmt(2)}°C/s, headroom=${headroomC.fmt(1)}°C)"
            )
        }

        // Imminent throttle — recommend a pre-emptive cap.
        val currentCapKhz = window.last().bigClusterMaxKhz
        val recommendedCapKhz = computeRecommendedCap(currentCapKhz, capStepFraction)

        return ThrottleForecast(
            willThrottleInSec = willThrottleInSec,
            recommendedCapKhz = recommendedCapKhz,
            reason = "rising at ${slope.fmt(2)}°C/s, will hit ${tripPointC}°C in ~${willThrottleInSec}s; " +
                "pre-emptive cap: ${recommendedCapKhz / 1000} MHz " +
                "(was ${currentCapKhz / 1000} MHz)",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute the recommended big-cluster cap.
     *
     * Strategy: reduce by [capStepFraction] of the current cap, rounding
     * down to the nearest 100 MHz boundary (common OPP table granularity)
     * to avoid writing a kHz value the kernel will silently clamp anyway.
     * Minimum floor is 300 000 kHz (300 MHz) — anything lower would make
     * the device unusable.
     */
    private fun computeRecommendedCap(currentCapKhz: Int, fraction: Float): Int {
        if (currentCapKhz <= 0) return 0
        val reduced = (currentCapKhz * (1f - fraction)).toInt()
        // Round down to nearest 100 MHz boundary.
        val rounded = (reduced / 100_000) * 100_000
        return rounded.coerceAtLeast(300_000)
    }

    /**
     * Ordinary least-squares slope (dy/dx) for paired (x, y) lists.
     * Returns 0.0 when degenerate (< 2 points, or zero variance in x).
     */
    internal fun linearSlopePerSec(xs: List<Double>, ys: List<Double>): Double {
        val n = xs.size.coerceAtMost(ys.size)
        if (n < 2) return 0.0
        val xMean = xs.take(n).average()
        val yMean = ys.take(n).average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - xMean
            num += dx * (ys[i] - yMean)
            den += dx * dx
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
    private fun Float.fmt(decimals: Int): String = toDouble().fmt(decimals)
}

/**
 * Output of [PredictiveThrottleGuard.predict].
 *
 * @param willThrottleInSec  Estimated seconds until the kernel's trip point
 *                           is reached. Null when no throttle is predicted.
 * @param recommendedCapKhz  Suggested big-cluster max frequency cap in kHz.
 *                           Null when no action is recommended.
 * @param reason             Human-readable explanation of the decision.
 *                           Always non-null; suitable for HUD event log.
 */
data class ThrottleForecast(
    val willThrottleInSec: Int?,
    val recommendedCapKhz: Int?,
    val reason: String,
) {
    /** True when the engine recommends taking pre-emptive action. */
    val actionRequired: Boolean get() = willThrottleInSec != null && recommendedCapKhz != null

    companion object {
        /** Convenience factory for the "no action needed" case. */
        fun noAction(reason: String) = ThrottleForecast(
            willThrottleInSec = null,
            recommendedCapKhz = null,
            reason = reason,
        )
    }
}
