package io.github.mayusi.calibratesoc.data.fancurve

/**
 * Pure, testable verification of whether a fan-curve apply actually landed.
 *
 * After the apply sequence runs we re-read two things through the privileged
 * shell:
 *   1. config.xml — does the curve JSON now equal what we wrote? (proves the
 *      file edit + atomic replace stuck), and
 *   2. the live PWM nodes /sys/class/gpio5_pwm2/{duty,period} — did the fan
 *      controller actually re-read the curve and is it driving the fan? (proves
 *      the kill + fan_mode bounce reloaded it).
 *
 * HONESTY is the whole point: if we cannot read enough to PROVE the apply took,
 * we return [FanCurveVerification.Unverified] — never a fake success. A genuine
 * mismatch is [FanCurveVerification.NotApplied].
 *
 * No Android, no I/O — the controller gathers the raw readbacks and passes them
 * in.
 */
object FanCurveVerifier {

    /**
     * Decide the verification outcome.
     *
     * @param intendedCurve      the curve we tried to write.
     * @param configReadback     re-read raw value of the curve pref from
     *                           config.xml (the JSON string), or null if the
     *                           file/pref could not be read back.
     * @param fanDutyReadback    raw `/sys/class/gpio5_pwm2/duty` value, or null.
     * @param fanPeriodReadback  raw `/sys/class/gpio5_pwm2/period` value, or null.
     *
     * Decision:
     *  - CONFIG MATCH + a PLAUSIBLE live fan reading (period > 0, duty >= 0) →
     *    [Applied] (strongest: file stuck AND the controller is driving the fan).
     *  - CONFIG MATCH but fan nodes unreadable/implausible →
     *    [Applied] with `liveConfirmed = false` (file stuck; live effect couldn't
     *    be observed — still honest, the curve IS in place and Smart mode is set).
     *  - CONFIG READ OK but does NOT match what we wrote → [NotApplied] (the
     *    write or atomic replace did not stick).
     *  - CONFIG unreadable → [Unverified] (we cannot prove either way).
     */
    fun verify(
        intendedCurve: FanCurve,
        configReadback: String?,
        fanDutyReadback: String?,
        fanPeriodReadback: String?,
    ): FanCurveVerification {
        val intendedJson = FanCurveJson.serialize(intendedCurve)

        if (configReadback == null) {
            return FanCurveVerification.Unverified(
                "Could not re-read config.xml after applying — the curve may or may " +
                    "not have taken effect.",
            )
        }

        val configMatches = curvesEqual(intendedJson, configReadback)
        if (!configMatches) {
            return FanCurveVerification.NotApplied(
                "config.xml did not contain the new curve after writing. The write " +
                    "or reload did not stick.",
            )
        }

        // File matched. Now see whether the live fan nodes confirm the reload.
        val liveConfirmed = isPlausibleLiveFan(fanDutyReadback, fanPeriodReadback)
        return FanCurveVerification.Applied(
            liveConfirmed = liveConfirmed,
            fanDuty = fanDutyReadback?.trim()?.toIntOrNull(),
            fanPeriod = fanPeriodReadback?.trim()?.toIntOrNull(),
        )
    }

    /**
     * Compare two curve JSON strings by VALUE, not by string identity, so that
     * whitespace or key-ordering differences in the device's stored copy don't
     * register as a mismatch. Both must parse; if the readback can't be parsed
     * we fall back to a trimmed string compare (which would just fail, honestly).
     */
    internal fun curvesEqual(intendedJson: String, readbackJson: String): Boolean {
        val a = FanCurveJson.parse(intendedJson)
        val b = FanCurveJson.parse(readbackJson)
        return if (a is FanCurveParse.Ok && b is FanCurveParse.Ok) {
            a.curve == b.curve
        } else {
            intendedJson.trim() == readbackJson.trim()
        }
    }

    /**
     * A live fan reading is "plausible" when the PWM period is a positive number
     * and the duty parses as a non-negative integer <= period. At 20% fan the
     * device reports period=50000, duty=10000; at 0% duty=0,speed=0. A period of
     * 0 or unreadable nodes mean we can't observe the live state.
     */
    internal fun isPlausibleLiveFan(dutyRaw: String?, periodRaw: String?): Boolean {
        val period = periodRaw?.trim()?.toIntOrNull() ?: return false
        if (period <= 0) return false
        val duty = dutyRaw?.trim()?.toIntOrNull() ?: return false
        return duty in 0..period
    }
}

/** Result of [FanCurveVerifier.verify]. */
sealed interface FanCurveVerification {
    /**
     * The curve is in config.xml. [liveConfirmed] is true when the live PWM
     * nodes also corroborate the reload. The honest UI distinguishes
     * "Applied & verified" (liveConfirmed) from "Applied (file written, live
     * effect unconfirmed)".
     */
    data class Applied(
        val liveConfirmed: Boolean,
        val fanDuty: Int?,
        val fanPeriod: Int?,
    ) : FanCurveVerification

    /** config.xml was readable but did NOT contain the new curve. */
    data class NotApplied(val reason: String) : FanCurveVerification

    /** We could not read back enough to prove anything. Never a false success. */
    data class Unverified(val reason: String) : FanCurveVerification
}
