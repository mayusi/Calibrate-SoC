package io.github.mayusi.calibratesoc.data.fancurve

/**
 * Pure, testable verification of an AYANEO fan-curve apply — the AYANEO analog of
 * [FanCurveVerifier], honoring the SAME honesty contract but for the binder path.
 *
 * ## Why AYANEO verification is necessarily weaker than Odin's
 * On Odin we re-read config.xml and can prove the EXACT curve JSON landed. On
 * AYANEO the curve lives inside the overlay (uid=system) — there is NO app-readable
 * node that echoes the curve back. The only thing we can read is `pwm1` (the live
 * fan duty, 0..255), and that is **temperature-dependent**: at idle the fan runs at
 * the LOW end of the curve, so a low (or even zero) `pwm1` is the correct, expected
 * reading and tells us NOTHING about whether the full curve took. We therefore can
 * NEVER prove the exact applied curve from a single readback.
 *
 * So this verifier reports, HONESTLY:
 *   - [FanCurveVerification.Applied] with `liveConfirmed = false` when the binder
 *     ACCEPTED the command AND `pwm1` is readable + in-range (the fan path is alive
 *     and the command was accepted) — but we deliberately do NOT claim the live
 *     curve was confirmed, because a single temp-dependent sample can't prove it.
 *   - [FanCurveVerification.Unverified] when the binder accepted but `pwm1` is
 *     unreadable (we can't even confirm the node is active) — accepted, NOT proven.
 *   - [FanCurveVerification.NotApplied] when the binder did NOT accept the command
 *     (the overlay/binder refused it) — nothing landed.
 *
 * We intentionally NEVER return `liveConfirmed = true` on AYANEO: there is no
 * readback that can corroborate the curve to that standard, and faking it would
 * violate the honesty law. (`liveConfirmed` stays the Odin-only "the live PWM
 * corroborated the exact reload" signal.)
 *
 * No Android, no I/O — the controller gathers the raw `accepted` flag + `pwm1`
 * readback and passes them in.
 */
object AyaneoFanCurveVerifier {

    /**
     * Decide the verification outcome for an AYANEO apply.
     *
     * @param accepted     true when [io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient.sendCommand]
     *                     returned true for BOTH the curve command and the
     *                     linearity command (the overlay accepted them at the
     *                     binder layer). false = the binder rejected/was unavailable.
     * @param pwmReadback  raw `pwm1` value (0..255 string) read back after the
     *                     send, or null when the node could not be read.
     */
    fun verify(accepted: Boolean, pwmReadback: String?): FanCurveVerification {
        if (!accepted) {
            return FanCurveVerification.NotApplied(
                "The AYANEO fan service did not accept the curve (the gamewindow binder " +
                    "rejected the command or was unavailable). The curve was NOT applied.",
            )
        }
        // Command accepted. Can we at least see the fan node is alive?
        if (!AyaneoPwm.isPlausibleRawPwm(pwmReadback)) {
            return FanCurveVerification.Unverified(
                "The AYANEO fan service accepted the curve, but the fan PWM node " +
                    "(pwm1) couldn't be read back to confirm the fan path is active. " +
                    "The curve was likely applied but couldn't be verified.",
            )
        }
        // Accepted + node readable/in-range. Honest "Applied, but live curve NOT
        // independently confirmed" — liveConfirmed stays false on AYANEO by design.
        val rawPwm = pwmReadback?.trim()?.toIntOrNull()
        val pctPwm = AyaneoPwm.rawStringToPercent(pwmReadback)
        return FanCurveVerification.Applied(
            liveConfirmed = false,
            // Report the readback as a 0..100 percentage in the `fanDuty` slot so the
            // UI's existing "duty N" rendering shows a meaningful number; the AYANEO
            // node has no period, so `fanPeriod` is the raw 0..255 scale max for context.
            fanDuty = pctPwm,
            fanPeriod = rawPwm?.let { AyaneoPwm.PWM_MAX },
        )
    }
}
