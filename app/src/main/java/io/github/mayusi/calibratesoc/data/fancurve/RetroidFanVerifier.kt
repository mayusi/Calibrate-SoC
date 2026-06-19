package io.github.mayusi.calibratesoc.data.fancurve

import kotlin.math.abs

/**
 * Pure, testable verification of a Retroid custom-fan apply — the Retroid analog of
 * [AyaneoFanCurveVerifier] and [FanCurveVerifier], honoring the SAME honesty contract
 * for the `FanProvider` binder path.
 *
 * ## What we can and cannot prove on Retroid
 * The Retroid fan binder gives us something AYANEO's does NOT: a READBACK
 * (`FanProvider.b()` / txn 2) that returns the current fan value on the same ~25000
 * scale as the speed we set. So after entering CUSTOM mode and setting the target
 * speed we can read back and check whether the value actually MOVED toward the
 * target:
 *   - readback ≈ target  → [FanCurveVerification.Applied] with `liveConfirmed = true`
 *     (the binder confirmed our speed took — the strongest signal Retroid offers).
 *   - readback DID NOT move toward target (still reads the stock/previous value, or
 *     unchanged) → [FanCurveVerification.Unverified]. This is the HONEST outcome when
 *     the CUSTOM-mode integer we used was wrong for this firmware: the governor stayed
 *     in Smart/auto and ignored the manual speed, so the value never moved. We do NOT
 *     fake success — we say the custom-mode value may differ on this firmware.
 *   - the binder did NOT accept the mode/speed command, OR the readback was unreadable
 *     → [FanCurveVerification.Unverified] (accepted-but-unprovable) or, when nothing
 *     was accepted at all, the controller maps it to a hard failure.
 *
 * ## Why this is more honest than AYANEO
 * AYANEO's readback is a temp-dependent live PWM sample that can NEVER prove the set
 * value. Retroid's readback echoes the SET value directly, so when it matches we CAN
 * honestly say `liveConfirmed = true`. When it doesn't move, that is precisely the
 * "custom-mode int may be wrong" signal the live calibration pass exists to resolve —
 * so we surface it as Unverified with that exact reason, never as a success.
 *
 * No Android, no I/O — the controller gathers the raw accepted flags + the readback
 * ints and passes them in.
 */
object RetroidFanVerifier {

    /**
     * Tolerance (as a FRACTION of the target speed) within which a readback counts as
     * "matched the target". The device may snap the set speed to a nearby governor
     * step, so we allow a small band. 10% comfortably covers snapping without masking
     * a genuine "didn't move" (which reads back the stock value — typically far more
     * than 10% from a custom target).
     */
    const val MATCH_TOLERANCE_FRACTION = 0.10

    /**
     * Decide the verification outcome for a Retroid apply.
     *
     * @param modeAccepted   true when the txn-5 setMode(CUSTOM) was accepted at the
     *                       binder layer.
     * @param speedAccepted  true when the txn-7 setSpeed(target) was accepted.
     * @param targetSpeed    the speed we asked the fan to hold (the clamped, safe
     *                       value the mapper produced).
     * @param readBack       the txn-2 readback AFTER the set, or null when the read
     *                       failed.
     * @param previousValue  the txn-2 readback taken BEFORE the set (the stock value,
     *                       e.g. 25000), or null when it couldn't be read. Used to
     *                       distinguish "moved toward target" from "unchanged".
     */
    fun verify(
        modeAccepted: Boolean,
        speedAccepted: Boolean,
        targetSpeed: Int,
        readBack: Int?,
        previousValue: Int?,
    ): FanCurveVerification {
        // Nothing landed at the binder layer at all.
        if (!modeAccepted && !speedAccepted) {
            return FanCurveVerification.NotApplied(
                "The Retroid fan service did not accept the custom-fan commands (the " +
                    "FanProvider binder rejected them or was unavailable). The fan was " +
                    "NOT changed.",
            )
        }

        // The speed command specifically must be accepted to claim anything was set.
        if (!speedAccepted) {
            return FanCurveVerification.Unverified(
                "Calibrate switched the Retroid fan toward custom mode but the fan " +
                    "service did not accept the speed value, so the custom fan speed " +
                    "could not be confirmed.",
            )
        }

        // Accepted. Can we read the value back?
        if (readBack == null) {
            return FanCurveVerification.Unverified(
                "The Retroid fan service accepted the custom fan speed, but the fan " +
                    "value couldn't be read back to confirm it took effect.",
            )
        }

        // Did the readback land at the target (within tolerance)?
        if (matchesTarget(readBack, targetSpeed)) {
            return FanCurveVerification.Applied(
                liveConfirmed = true,
                fanDuty = readBack,
                fanPeriod = null, // Retroid has no period node; the value IS the duty-scale reading.
            )
        }

        // The readback did NOT reach the target. If it's still sitting at the previous
        // (stock) value, the governor most likely ignored the manual speed because the
        // CUSTOM-mode integer was wrong for this firmware — the exact thing the live
        // calibration pass resolves. Be HONEST: accepted, but unverified.
        val stuckAtPrevious = previousValue != null && matchesTarget(readBack, previousValue)
        val reason = if (stuckAtPrevious) {
            "The Retroid fan service accepted the custom fan speed, but the fan value " +
                "stayed at its previous setting ($readBack) instead of moving to the " +
                "target ($targetSpeed). The custom-mode value may differ on this " +
                "firmware, so the change couldn't be confirmed."
        } else {
            "The Retroid fan service accepted the custom fan speed, but the fan value " +
                "read back as $readBack instead of the target $targetSpeed, so the " +
                "change couldn't be confirmed."
        }
        return FanCurveVerification.Unverified(reason)
    }

    /**
     * True when [value] is within [MATCH_TOLERANCE_FRACTION] of [target]. Exact match
     * always passes; for non-trivial targets we allow the governor-snap band. A
     * non-positive target requires an exact match (no fraction to scale).
     */
    internal fun matchesTarget(value: Int, target: Int): Boolean {
        if (value == target) return true
        if (target <= 0) return value == target
        val deltaFraction = abs(value - target).toDouble() / target.toDouble()
        return deltaFraction <= MATCH_TOLERANCE_FRACTION
    }
}
