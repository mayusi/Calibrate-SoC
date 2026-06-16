package io.github.mayusi.calibratesoc.data.fancurve

import io.github.mayusi.calibratesoc.data.fancurve.FanCurve.Companion.SENTINEL_TEMP_C

/**
 * Built-in fan-curve presets for the AYN Odin 3.
 *
 * Design rules (enforced by the [check] test and the validate() invariants):
 *  - Every preset keeps its duty floor at-or-above the documented 20% safe
 *    minimum ([FanCurve.SAFE_MIN_DUTY_PCT]). No built-in ships a sub-20% point.
 *  - Every preset reaches a STRONG duty (>= 60%) by the ~95-105°C hot zone so
 *    none can let temperatures run away.
 *  - Temperatures are monotonic non-decreasing and the final point is the
 *    INT_MAX catch-all ceiling.
 *  - [BALANCED] mirrors the stock Odin "Smart" curve exactly so users have a
 *    safe, familiar reference / reset target.
 *
 * The curves trade noise for cooling headroom:
 *  - QUIET      — stays at the 20% floor longest, ramps late. Quietest.
 *  - BALANCED   — the stock curve. Sensible default.
 *  - COOL       — ramps earlier and harder than stock. Cooler, louder.
 *  - MAX_COOLING — aggressive from the start; pins high in the hot zone.
 */
enum class FanCurvePreset(
    val id: String,
    val displayName: String,
    val tagline: String,
    val curve: FanCurve,
) {
    QUIET(
        id = "quiet",
        displayName = "Quiet",
        tagline = "Fan stays low, ramps late — quietest, runs warmer.",
        curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(55, 20),
                FanCurvePoint(70, 25),
                FanCurvePoint(85, 35),
                FanCurvePoint(95, 50),
                FanCurvePoint(105, 70),
                FanCurvePoint(SENTINEL_TEMP_C, 80),
            ),
        ),
    ),

    BALANCED(
        id = "balanced",
        displayName = "Balanced",
        tagline = "The stock Odin curve. Sensible default.",
        // Identical to the verified stock "Smart" curve.
        curve = FanCurve.STOCK,
    ),

    COOL(
        id = "cool",
        displayName = "Cool",
        tagline = "Ramps earlier and harder than stock. Cooler, a bit louder.",
        curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(40, 25),
                FanCurvePoint(55, 35),
                FanCurvePoint(70, 50),
                FanCurvePoint(85, 70),
                FanCurvePoint(95, 85),
                FanCurvePoint(105, 100),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        ),
    ),

    MAX_COOLING(
        id = "max_cooling",
        displayName = "Max Cooling",
        tagline = "Aggressive from the start, pins high when hot. Loudest, coolest.",
        curve = FanCurve(
            listOf(
                FanCurvePoint(0, 40),
                FanCurvePoint(35, 50),
                FanCurvePoint(50, 65),
                FanCurvePoint(65, 80),
                FanCurvePoint(80, 95),
                FanCurvePoint(90, 100),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        ),
    );

    companion object {
        /** The default preset offered on first open. */
        val DEFAULT: FanCurvePreset = BALANCED

        fun byId(id: String?): FanCurvePreset? = entries.firstOrNull { it.id == id }
    }
}
