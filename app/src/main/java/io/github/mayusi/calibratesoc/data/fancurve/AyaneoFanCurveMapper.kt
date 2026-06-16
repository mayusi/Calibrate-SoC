package io.github.mayusi.calibratesoc.data.fancurve

import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoCommands

/**
 * PURE bridge from the vendor-neutral [FanCurve] model to the AYANEO
 * `com_set_fan_speed_strategy` binder payload — the AYANEO analog of
 * [FanCurveJson] (which produces the Odin config.xml JSON shape). No Android, no
 * I/O, so every byte of the wire string is unit-testable.
 *
 * ## Why AYANEO differs from Odin
 * Odin stores the curve as `[{"a":temp,"b":duty}…]` JSON inside a SharedPreferences
 * file that the privileged shell rewrites. AYANEO instead takes the curve over the
 * exported `AyaAidlService` binder as a single command string
 * (`…:com_set_fan_speed_strategy:FAN_MODE_CUSTOM-temp,duty|temp,duty|…`); the
 * overlay (uid=system) actuates the PWM. We reuse the SAME [FanCurve] model + the
 * SAME hard hot-zone cooling floor ([FanCurve.validate]); only this serializer +
 * the apply transport differ.
 *
 * ## The catch-all (INT_MAX) sentinel
 * The Odin model carries a final [FanCurve.SENTINEL_TEMP_C] (`Int.MAX_VALUE`)
 * catch-all ceiling point. The AYANEO wire format expects whole-°C thresholds, so
 * we project that sentinel down to [SENTINEL_PROJECTED_TEMP_C] (a high but real
 * temperature ABOVE the critical zone) when emitting the AYANEO string. The duty
 * is preserved exactly, so the cooling the sentinel guarantees in the hot/critical
 * band is carried over faithfully — the projection only swaps the *threshold*
 * `2147483647` for a sane on-device value, never the duty. (A literal 2147483647°C
 * threshold would be meaningless to the AYANEO overlay.)
 *
 * ## Units
 * The AYANEO duty in the command string is a **percentage (0..100)** — the SAME
 * unit [FanCurvePoint.dutyPct] already uses, so NO conversion is needed on the way
 * out. The READBACK node (`pwm1`) is 0..255, which [AyaneoPwm] converts back to a
 * percentage for verification.
 */
object AyaneoFanCurveMapper {

    /**
     * The whole-°C threshold we substitute for the [FanCurve.SENTINEL_TEMP_C]
     * (`Int.MAX_VALUE`) catch-all when emitting the AYANEO string. Chosen ABOVE
     * the critical zone ([FanCurve.CRITICAL_ZONE_TEMP_C] = 105) so the sentinel's
     * (strong) duty still governs the whole hot/critical band on AYANEO exactly as
     * it does on Odin. 120°C is comfortably above any real SoC operating point yet
     * a finite, sane threshold the overlay accepts.
     */
    const val SENTINEL_PROJECTED_TEMP_C = 120

    /**
     * Map [curve] into the ordered `(tempC, dutyPct)` pairs the AYANEO command
     * string carries. The catch-all sentinel is projected to
     * [SENTINEL_PROJECTED_TEMP_C]; every other point passes through unchanged.
     *
     * Pure: does NOT validate. Call [FanCurve.validate] first — the controller does.
     */
    fun toAyaneoPairs(curve: FanCurve): List<Pair<Int, Int>> =
        curve.points.map { p ->
            val temp = if (p.isSentinel) SENTINEL_PROJECTED_TEMP_C else p.tempC
            temp to p.dutyPct
        }

    /**
     * Build the EXACT `com_set_fan_speed_strategy` payload for [curve], e.g.
     * `calibrate:msg_type_performance:com_set_fan_speed_strategy:FAN_MODE_CUSTOM-45,20|65,30|85,45|105,60|120,60`.
     *
     * Delegates the `temp,duty|…` serialization to the already-tested
     * [AyaneoCommands.setFanCurve] so the wire format has a single source of truth.
     */
    fun buildCurveCommand(curve: FanCurve): String =
        AyaneoCommands.setFanCurve(toAyaneoPairs(curve))

    /**
     * Build the linearity command. AYANEO interpolates BETWEEN curve points when
     * LINEAR; STEP holds each point's duty until the next threshold. We emit STEP
     * by default to match the Odin stock controller's step semantics (the Odin
     * curve is a step function — each point governs up to the next threshold), so
     * the same curve behaves consistently across both vendors and the hot-zone
     * floor analysis ([FanCurve.bandMinDuty], which is step-based) stays accurate.
     */
    fun buildLinearityCommand(linear: Boolean = false): String =
        AyaneoCommands.setFanCurveLinear(linear)
}

/**
 * PURE conversions between the AYANEO fan duty PERCENT (the unit the command
 * string uses, 0..100) and the raw `pwm1` hwmon node value (0..255, the unit we
 * read back to verify). Kept separate + tiny so the rounding is unit-testable and
 * the verifier and the live readout share one definition.
 */
object AyaneoPwm {

    /** Full-scale raw PWM value the hwmon `pwm1` node uses (8-bit). */
    const val PWM_MAX = 255

    /**
     * Convert a raw `pwm1` reading (0..255) to a 0..100 percentage, rounded to the
     * nearest whole percent. Returns null for an out-of-range / unparsable raw
     * value so the caller can degrade honestly rather than fabricate a number.
     *
     * `dutyPct ≈ pwm / 255 * 100` (the conversion the feature brief specifies).
     */
    fun rawToPercent(raw: Int): Int? {
        if (raw < 0 || raw > PWM_MAX) return null
        // Round-half-up: (raw*100 + 127) / 255.
        return ((raw * 100) + (PWM_MAX / 2)) / PWM_MAX
    }

    /** Parse a raw `pwm1` stdout string then convert to percent; null on failure. */
    fun rawStringToPercent(raw: String?): Int? {
        val v = raw?.trim()?.toIntOrNull() ?: return null
        return rawToPercent(v)
    }

    /**
     * A raw `pwm1` reading is "plausibly active" — i.e. the node exists, parsed,
     * and sits in the valid 0..255 PWM range. At idle the fan legitimately reports
     * a LOW value (the bottom of the curve), and 0 is a valid "fan off" state, so
     * we do NOT require a non-zero reading; we only require a READABLE, in-range
     * one. This mirrors [FanCurveVerifier.isPlausibleLiveFan]'s honesty: a readable
     * node proves the fan path is alive, not that any particular duty is set.
     */
    fun isPlausibleRawPwm(raw: String?): Boolean {
        val v = raw?.trim()?.toIntOrNull() ?: return false
        return v in 0..PWM_MAX
    }
}
