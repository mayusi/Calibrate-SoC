package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Honest verification of the AYANEO apply: the binder send is fire-and-forget and
 * the curve lives inside the overlay (no app-readable curve node), so we can only
 * confirm "accepted + fan node active", NEVER the exact curve. These tests pin
 * that honesty: we NEVER fabricate `liveConfirmed = true` on AYANEO, and a refused
 * command is NotApplied.
 */
class AyaneoFanCurveVerifierTest {

    @Test
    fun `accepted plus readable pwm is Applied but NOT live-confirmed`() {
        val result = AyaneoFanCurveVerifier.verify(accepted = true, pwmReadback = "128")
        assertThat(result).isInstanceOf(FanCurveVerification.Applied::class.java)
        val applied = result as FanCurveVerification.Applied
        // AYANEO can NEVER prove the exact temp-dependent curve from one sample.
        assertThat(applied.liveConfirmed).isFalse()
        // pwm 128/255 ≈ 50% reported in the duty slot; period = full-scale 255.
        assertThat(applied.fanDuty).isEqualTo(50)
        assertThat(applied.fanPeriod).isEqualTo(AyaneoPwm.PWM_MAX)
    }

    @Test
    fun `accepted with a zero pwm reading is still Applied (idle fan is valid)`() {
        // At idle the AYANEO fan legitimately reads low / zero — that's a valid,
        // readable node, so it counts as accepted-and-active, not a failure.
        val result = AyaneoFanCurveVerifier.verify(accepted = true, pwmReadback = "0")
        assertThat(result).isInstanceOf(FanCurveVerification.Applied::class.java)
        assertThat((result as FanCurveVerification.Applied).fanDuty).isEqualTo(0)
    }

    @Test
    fun `accepted but unreadable pwm is Unverified - never a fake success`() {
        val result = AyaneoFanCurveVerifier.verify(accepted = true, pwmReadback = null)
        assertThat(result).isInstanceOf(FanCurveVerification.Unverified::class.java)
    }

    @Test
    fun `accepted but out-of-range pwm is Unverified`() {
        // A garbage / out-of-range readback can't confirm the node is active.
        val result = AyaneoFanCurveVerifier.verify(accepted = true, pwmReadback = "999")
        assertThat(result).isInstanceOf(FanCurveVerification.Unverified::class.java)
    }

    @Test
    fun `not accepted is NotApplied regardless of pwm`() {
        val result = AyaneoFanCurveVerifier.verify(accepted = false, pwmReadback = "128")
        assertThat(result).isInstanceOf(FanCurveVerification.NotApplied::class.java)
    }
}
