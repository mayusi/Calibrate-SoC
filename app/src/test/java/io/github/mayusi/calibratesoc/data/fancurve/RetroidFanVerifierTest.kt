package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Honest verification of the Retroid apply. Unlike AYANEO (whose readback can never
 * prove the curve), Retroid's FanProvider readback (txn 2) echoes the SET value on the
 * same scale, so when it lands at the target we CAN honestly say liveConfirmed = true.
 * When it stays at the previous (stock) value the most likely cause is the wrong
 * CUSTOM-mode int for this firmware — so we report Unverified with that exact reason,
 * NEVER a faked success. These tests pin that contract.
 */
class RetroidFanVerifierTest {

    private val TARGET = 15_000
    private val STOCK = 25_000

    @Test
    fun `readback at the target is Applied and live-confirmed`() {
        val r = RetroidFanVerifier.verify(
            modeAccepted = true,
            speedAccepted = true,
            targetSpeed = TARGET,
            readBack = TARGET,
            previousValue = STOCK,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.Applied::class.java)
        val applied = r as FanCurveVerification.Applied
        assertThat(applied.liveConfirmed).isTrue()
        assertThat(applied.fanDuty).isEqualTo(TARGET)
        // Retroid has no period node.
        assertThat(applied.fanPeriod).isNull()
    }

    @Test
    fun `readback within the snap tolerance still counts as Applied`() {
        // 5% off target is within the 10% match band → Applied.
        val near = (TARGET * 0.95).toInt()
        val r = RetroidFanVerifier.verify(
            modeAccepted = true, speedAccepted = true,
            targetSpeed = TARGET, readBack = near, previousValue = STOCK,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.Applied::class.java)
    }

    @Test
    fun `readback stuck at the previous stock value is Unverified with the custom-mode reason`() {
        // The classic wrong-CUSTOM-mode-int signal: the governor ignored r(int) and the
        // value never left the stock 25000. Must be HONEST Unverified, never Applied.
        val r = RetroidFanVerifier.verify(
            modeAccepted = true, speedAccepted = true,
            targetSpeed = TARGET, readBack = STOCK, previousValue = STOCK,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.Unverified::class.java)
        assertThat((r as FanCurveVerification.Unverified).reason).contains("custom-mode value may differ")
    }

    @Test
    fun `readback at neither target nor previous is Unverified (generic mismatch)`() {
        val r = RetroidFanVerifier.verify(
            modeAccepted = true, speedAccepted = true,
            targetSpeed = TARGET, readBack = 99_999, previousValue = STOCK,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.Unverified::class.java)
        assertThat((r as FanCurveVerification.Unverified).reason).contains("read back as")
    }

    @Test
    fun `accepted but unreadable readback is Unverified, never a fake success`() {
        val r = RetroidFanVerifier.verify(
            modeAccepted = true, speedAccepted = true,
            targetSpeed = TARGET, readBack = null, previousValue = STOCK,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.Unverified::class.java)
    }

    @Test
    fun `speed not accepted is Unverified even if mode was accepted`() {
        val r = RetroidFanVerifier.verify(
            modeAccepted = true, speedAccepted = false,
            targetSpeed = TARGET, readBack = TARGET, previousValue = STOCK,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.Unverified::class.java)
    }

    @Test
    fun `nothing accepted is NotApplied (hard failure)`() {
        val r = RetroidFanVerifier.verify(
            modeAccepted = false, speedAccepted = false,
            targetSpeed = TARGET, readBack = null, previousValue = null,
        )
        assertThat(r).isInstanceOf(FanCurveVerification.NotApplied::class.java)
    }

    @Test
    fun `matchesTarget honors the tolerance band and exact small values`() {
        assertThat(RetroidFanVerifier.matchesTarget(15_000, 15_000)).isTrue()
        assertThat(RetroidFanVerifier.matchesTarget(15_900, 15_000)).isTrue() // 6% off
        assertThat(RetroidFanVerifier.matchesTarget(18_000, 15_000)).isFalse() // 20% off
        // Non-positive target requires exact match.
        assertThat(RetroidFanVerifier.matchesTarget(0, 0)).isTrue()
        assertThat(RetroidFanVerifier.matchesTarget(1, 0)).isFalse()
    }
}
