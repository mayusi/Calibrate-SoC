package io.github.mayusi.calibratesoc.data.fancurve

import io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanConfig

/**
 * PURE bridge from the vendor-neutral [FanCurve] model to the SINGLE Retroid custom
 * fan SPEED the `FanProvider` binder takes — the Retroid analog of
 * [AyaneoFanCurveMapper] (which builds AYANEO's curve-array command) and
 * [FanCurveJson] (the Odin config.xml shape). No Android, no I/O, fully unit-testable.
 *
 * ## HONESTY: Retroid is a custom fan SPEED, not a temp-reactive curve
 * Odin (config.xml) and AYANEO (`com_set_fan_speed_strategy`) both hand the vendor a
 * temp→duty CURVE and the vendor's governor reacts to temperature. The Retroid
 * decompile showed NO curve-array transaction — `FanProvider` exposes a single
 * `r(int speed)` scalar (per-app `custom_fan_speed`). In CUSTOM mode the governor
 * HOLDS that fixed speed; it is NOT temperature-aware. So on Retroid we cannot honor
 * a real curve — we map the curve to ONE representative target speed and hold it.
 *
 * The UI must say so plainly (Retroid = "custom fan speed", a fixed duty the app
 * holds, not a curve like Odin/AYANEO). [vendorHonestyNote] carries that sentence.
 *
 * ## How the curve collapses to one speed
 * We take the curve's HOT-ZONE representative duty — specifically the worst-case
 * (minimum) effective duty the curve delivers across the hot band
 * ([FanCurve.bandMinDuty] at [FanCurve.HOT_ZONE_TEMP_C]). Using the hot-zone floor
 * (rather than, say, the peak) is the SAFE choice: a single held speed must be at
 * least as strong as what the curve promised for when the device is hot, so the held
 * speed errs toward MORE cooling. We then map that duty% (0..100) onto the device's
 * ~25000 speed scale ([RetroidFanConfig.SPEED_MIN]..[RetroidFanConfig.SPEED_MAX]) and
 * clamp UP to the hard safety floor [RetroidFanConfig.SPEED_SAFE_MIN].
 *
 * ## Units
 * The Retroid `r(int)` value is NOT 0-255 PWM and NOT 0-100% — the stock `fan_speed`
 * pref default is 25000, so it is a duty/RPM-ish value on a ~25000 scale. The exact
 * range is CALIBRATE-LIVE (see [RetroidFanConfig]); this mapper produces the right
 * speed GIVEN those constants, so once they're pinned it is correct with no logic
 * change.
 */
object RetroidFanSpeedMapper {

    /** The honesty sentence the UI shows for the Retroid vendor. */
    const val vendorHonestyNote: String =
        "On Retroid, Calibrate sets a single custom fan SPEED that the fan holds — " +
            "it is not a temperature-reactive curve like on Odin or AYANEO. Calibrate " +
            "maps your curve's hot-zone strength to that held speed and never lets it " +
            "drop below a safe minimum."

    /**
     * The representative duty PERCENT (0..100) the curve collapses to on Retroid: the
     * worst-case (minimum) effective duty across the hot zone, so the single held
     * speed is at least as strong as the curve promised when hot. Defaults to
     * [FanCurve.SAFE_MIN_DUTY_PCT] for a degenerate empty curve (never below the floor).
     *
     * Pure: does NOT validate. The controller validates the curve first
     * ([FanCurve.validate], incl. the hard hot-zone cooling floor) before mapping.
     */
    fun representativeDutyPct(curve: FanCurve): Int =
        (curve.bandMinDuty(FanCurve.HOT_ZONE_TEMP_C) ?: FanCurve.SAFE_MIN_DUTY_PCT)
            .coerceIn(0, 100)

    /**
     * Map a duty PERCENT (0..100) onto the Retroid speed scale, then clamp UP to the
     * hard safety floor. Linear: `SPEED_MIN + pct/100 * (SPEED_MAX - SPEED_MIN)`,
     * then [RetroidFanConfig.clampSpeed].
     *
     * SAFETY: the clamp guarantees the actuated speed is never below
     * [RetroidFanConfig.SPEED_SAFE_MIN] regardless of how low [dutyPct] is — the user
     * can never set the Retroid fan dangerously slow through this path.
     */
    fun dutyPctToSpeed(dutyPct: Int): Int {
        val pct = dutyPct.coerceIn(0, 100)
        val span = (RetroidFanConfig.SPEED_MAX - RetroidFanConfig.SPEED_MIN).toLong()
        val raw = RetroidFanConfig.SPEED_MIN + ((span * pct) / 100L).toInt()
        return RetroidFanConfig.clampSpeed(raw)
    }

    /**
     * The full curve → safe, clamped target SPEED the binder's `r(int)` receives.
     * Combines [representativeDutyPct] + [dutyPctToSpeed].
     */
    fun curveToSpeed(curve: FanCurve): Int =
        dutyPctToSpeed(representativeDutyPct(curve))
}
