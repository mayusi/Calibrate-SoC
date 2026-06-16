package io.github.mayusi.calibratesoc.data.fancurve

import kotlinx.serialization.Serializable

/**
 * A single point on a fan curve: at SoC temperature [tempC] (°C) the fan
 * should run at [dutyPct] percent of full speed.
 *
 * The Odin stock controller stores each point as `{"a": tempC, "b": dutyPct}`
 * in a JSON array. The serialization into that exact wire shape lives in
 * [FanCurveJson]; this is the clean in-app representation.
 *
 * The final ("catch-all ceiling") point of a stock curve carries the sentinel
 * temperature [FanCurve.SENTINEL_TEMP_C] (INT_MAX) — see [FanCurve].
 */
@Serializable
data class FanCurvePoint(
    /** Threshold temperature in whole °C. The catch-all point uses
     *  [FanCurve.SENTINEL_TEMP_C]. */
    val tempC: Int,
    /** Fan duty as a percentage 0..100. The stock UI enforces a 20% floor; the
     *  backend accepts 0 (full off — risky). See [FanCurve.SAFE_MIN_DUTY_PCT]. */
    val dutyPct: Int,
) {
    /** True when this is the INT_MAX catch-all ceiling point. */
    val isSentinel: Boolean get() = tempC == FanCurve.SENTINEL_TEMP_C
}

/**
 * An ordered fan curve as the Odin stock controller understands it.
 *
 * ## Invariants (enforced by [validate])
 *  - At least [MIN_POINTS] points, at most [MAX_POINTS].
 *  - Temperatures are strictly **monotonic non-decreasing** in list order.
 *  - The LAST point is the INT_MAX sentinel ([SENTINEL_TEMP_C]) — the
 *    catch-all ceiling the stock controller requires.
 *  - Every [FanCurvePoint.dutyPct] is in 0..100.
 *  - No NON-final point may use the sentinel temperature.
 *
 * ## Recommendations (warnings, NOT hard errors)
 *  - Duty should be non-decreasing as temperature rises (a curve that ramps
 *    DOWN as it heats up is almost always a mistake). Surfaced via [warnings].
 *  - Duty below [SAFE_MIN_DUTY_PCT] (20%) is the documented sub-floor the
 *    stock UI hides; the backend accepts it but it is risky. Surfaced via
 *    [warnings] and gated behind an explicit opt-in in the repository.
 *
 * The model is vendor-neutral on purpose: a future non-Odin adapter can reuse
 * [FanCurve] / [FanCurvePoint] and supply its own serializer + write path. Only
 * the JSON shape and the write sequence are Odin-specific (see [FanCurveJson],
 * [FanCurveController]).
 */
@Serializable
data class FanCurve(
    val points: List<FanCurvePoint>,
) {
    /**
     * Validates the structural invariants. Returns [FanCurveValidation.Valid]
     * when the curve is safe to serialize and apply, otherwise
     * [FanCurveValidation.Invalid] with a human-readable reason for the FIRST
     * violation found (deterministic order so tests are stable).
     *
     * Pure — no I/O — so it is fully unit-testable on the JVM.
     */
    fun validate(): FanCurveValidation {
        if (points.size < MIN_POINTS) {
            return FanCurveValidation.Invalid(
                "A fan curve needs at least $MIN_POINTS points (has ${points.size}).",
            )
        }
        if (points.size > MAX_POINTS) {
            return FanCurveValidation.Invalid(
                "A fan curve may have at most $MAX_POINTS points (has ${points.size}).",
            )
        }

        // Duty range check — every point.
        points.forEachIndexed { i, p ->
            if (p.dutyPct < MIN_DUTY_PCT || p.dutyPct > MAX_DUTY_PCT) {
                return FanCurveValidation.Invalid(
                    "Point $i duty ${p.dutyPct}% is out of range " +
                        "($MIN_DUTY_PCT..$MAX_DUTY_PCT).",
                )
            }
        }

        // Temperature monotonic non-decreasing in list order.
        for (i in 1 until points.size) {
            if (points[i].tempC < points[i - 1].tempC) {
                return FanCurveValidation.Invalid(
                    "Temperatures must not decrease: point $i (${points[i].tempC}°C) " +
                        "is below point ${i - 1} (${points[i - 1].tempC}°C).",
                )
            }
        }

        // The sentinel must be the LAST point and ONLY the last point.
        val last = points.last()
        if (!last.isSentinel) {
            return FanCurveValidation.Invalid(
                "The final point must be the catch-all ceiling " +
                    "(temp = $SENTINEL_TEMP_C). Last point is ${last.tempC}°C.",
            )
        }
        for (i in 0 until points.size - 1) {
            if (points[i].isSentinel) {
                return FanCurveValidation.Invalid(
                    "Only the FINAL point may use the catch-all ceiling temperature " +
                        "($SENTINEL_TEMP_C); point $i also uses it.",
                )
            }
        }

        return FanCurveValidation.Valid
    }

    /** Convenience: true when [validate] returns Valid. */
    val isValid: Boolean get() = validate() is FanCurveValidation.Valid

    /**
     * Non-blocking advisories that do NOT make the curve invalid but should be
     * surfaced to the user. Empty when the curve is clean.
     *
     *  - A duty drop as temperature rises (non-monotonic duty).
     *  - Any non-sentinel point below the documented [SAFE_MIN_DUTY_PCT] floor.
     *  - The curve never reaching a strong duty by the hot zone (runaway risk):
     *    the highest real (non-sentinel) point's duty is below
     *    [MIN_HOT_DUTY_PCT] at-or-above [HOT_ZONE_TEMP_C].
     */
    fun warnings(): List<FanCurveWarning> {
        val out = mutableListOf<FanCurveWarning>()

        // Duty must not fall as temperature climbs (recommendation only).
        for (i in 1 until points.size) {
            if (points[i].dutyPct < points[i - 1].dutyPct) {
                out += FanCurveWarning.DutyDecreases(
                    fromIndex = i - 1,
                    toIndex = i,
                    fromDuty = points[i - 1].dutyPct,
                    toDuty = points[i].dutyPct,
                )
                break // one is enough to flag the issue
            }
        }

        // Sub-floor duty (the stock UI hides values < 20%).
        val subFloor = points.filter { !it.isSentinel && it.dutyPct < SAFE_MIN_DUTY_PCT }
        if (subFloor.isNotEmpty()) {
            out += FanCurveWarning.SubFloorDuty(
                minDuty = subFloor.minOf { it.dutyPct },
                count = subFloor.size,
            )
        }

        // Runaway-cooling guard: by the hot zone the fan must be working hard.
        // Look at the hottest real (non-sentinel) point AND the sentinel duty —
        // whichever covers the >= HOT_ZONE_TEMP_C band.
        val hotDuty = hotZoneDuty()
        if (hotDuty != null && hotDuty < MIN_HOT_DUTY_PCT) {
            out += FanCurveWarning.WeakHotZone(
                hotZoneTempC = HOT_ZONE_TEMP_C,
                dutyAtHotZone = hotDuty,
            )
        }

        return out
    }

    /**
     * The duty the curve delivers in the hot zone (>= [HOT_ZONE_TEMP_C]).
     *
     * Because the controller interpolates/steps between points, the duty that
     * actually applies at [HOT_ZONE_TEMP_C] is the duty of the LAST point whose
     * temperature is <= [HOT_ZONE_TEMP_C] — or, if all real points are below it,
     * the sentinel/ceiling duty. Returns null only for a degenerate empty curve.
     */
    fun hotZoneDuty(): Int? {
        if (points.isEmpty()) return null
        // Highest real point at-or-below the hot-zone temp governs that band;
        // if none, the catch-all ceiling does.
        val governing = points.lastOrNull { it.tempC <= HOT_ZONE_TEMP_C }
            ?: points.first()
        return governing.dutyPct
    }

    companion object {
        /** INT_MAX — the catch-all ceiling temperature the stock controller
         *  uses for the final curve point. */
        const val SENTINEL_TEMP_C = Int.MAX_VALUE // 2147483647

        const val MIN_POINTS = 2
        const val MAX_POINTS = 16

        const val MIN_DUTY_PCT = 0
        const val MAX_DUTY_PCT = 100

        /** The documented safe minimum the stock UI enforces. Values below this
         *  are accepted by the backend but flagged + gated behind opt-in. */
        const val SAFE_MIN_DUTY_PCT = 20

        /** Temperature at which we consider the device to be in its hot zone.
         *  By here the fan should be ramping hard or the device can run away. */
        const val HOT_ZONE_TEMP_C = 95

        /** Minimum duty we want to see in the hot zone before warning about a
         *  runaway-cooling risk. */
        const val MIN_HOT_DUTY_PCT = 45

        /**
         * The Odin stock "Smart" curve, used as the Balanced reference preset
         * and as a known-good fallback. Verified from a live config.xml dump.
         */
        val STOCK: FanCurve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(25, 20),
                FanCurvePoint(45, 20),
                FanCurvePoint(65, 30),
                FanCurvePoint(85, 45),
                FanCurvePoint(105, 60),
                FanCurvePoint(SENTINEL_TEMP_C, 60),
            ),
        )
    }
}

/** Result of [FanCurve.validate]. */
sealed interface FanCurveValidation {
    data object Valid : FanCurveValidation
    data class Invalid(val reason: String) : FanCurveValidation
}

/** A non-fatal advisory about a curve. Rendered as a caution in the UI. */
sealed interface FanCurveWarning {
    /** Human-readable one-line message for display. */
    val message: String

    /** Duty falls as temperature rises — almost always a mistake. */
    data class DutyDecreases(
        val fromIndex: Int,
        val toIndex: Int,
        val fromDuty: Int,
        val toDuty: Int,
    ) : FanCurveWarning {
        override val message: String
            get() = "Fan slows down as it gets hotter ($fromDuty% → $toDuty%). " +
                "This is usually a mistake."
    }

    /** One or more points dip below the documented 20% safe floor. */
    data class SubFloorDuty(val minDuty: Int, val count: Int) : FanCurveWarning {
        override val message: String
            get() = "Curve dips to $minDuty% (below the 20% safe minimum). " +
                "The fan may stop entirely — only use this if you know your thermals."
    }

    /** The curve never ramps hard in the hot zone — runaway risk. */
    data class WeakHotZone(val hotZoneTempC: Int, val dutyAtHotZone: Int) : FanCurveWarning {
        override val message: String
            get() = "Fan is only $dutyAtHotZone% at $hotZoneTempC°C. " +
                "Consider a higher duty in the hot zone to avoid thermal throttling."
    }
}
