package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Honest verification: a curve apply is only reported APPLIED when the re-read
 * config.xml proves the new curve is in place; NOT-APPLIED when the file
 * readback proves it didn't stick; and UNVERIFIED when we couldn't read enough
 * to prove anything. We NEVER fabricate success.
 */
class FanCurveVerifierTest {

    private val curve = FanCurve.STOCK
    private val curveJson = FanCurveJson.serialize(FanCurve.STOCK)

    @Test
    fun `config match plus live fan reading is Applied and live-confirmed`() {
        val result = FanCurveVerifier.verify(
            intendedCurve = curve,
            configReadback = curveJson,
            fanDutyReadback = "10000",
            fanPeriodReadback = "50000",
        )
        assertThat(result).isInstanceOf(FanCurveVerification.Applied::class.java)
        val applied = result as FanCurveVerification.Applied
        assertThat(applied.liveConfirmed).isTrue()
        assertThat(applied.fanDuty).isEqualTo(10000)
        assertThat(applied.fanPeriod).isEqualTo(50000)
    }

    @Test
    fun `config match but unreadable fan nodes is Applied but not live-confirmed`() {
        val result = FanCurveVerifier.verify(
            intendedCurve = curve,
            configReadback = curveJson,
            fanDutyReadback = null,
            fanPeriodReadback = null,
        )
        assertThat(result).isInstanceOf(FanCurveVerification.Applied::class.java)
        assertThat((result as FanCurveVerification.Applied).liveConfirmed).isFalse()
    }

    @Test
    fun `config readback that does not match is NotApplied`() {
        val differentCurve = FanCurveJson.serialize(FanCurvePreset.MAX_COOLING.curve)
        val result = FanCurveVerifier.verify(
            intendedCurve = curve,
            configReadback = differentCurve, // the file did NOT get our curve
            fanDutyReadback = "10000",
            fanPeriodReadback = "50000",
        )
        assertThat(result).isInstanceOf(FanCurveVerification.NotApplied::class.java)
    }

    @Test
    fun `unreadable config is Unverified - never a fake success`() {
        val result = FanCurveVerifier.verify(
            intendedCurve = curve,
            configReadback = null, // couldn't re-read the file
            fanDutyReadback = "10000",
            fanPeriodReadback = "50000",
        )
        assertThat(result).isInstanceOf(FanCurveVerification.Unverified::class.java)
    }

    @Test
    fun `config match tolerates whitespace and key-order differences`() {
        // The device may store the same curve with incidental whitespace.
        val spaced = curveJson.replace(",", ", ")
        val result = FanCurveVerifier.verify(
            intendedCurve = curve,
            configReadback = spaced,
            fanDutyReadback = "0",
            fanPeriodReadback = "50000",
        )
        assertThat(result).isInstanceOf(FanCurveVerification.Applied::class.java)
    }

    @Test
    fun `zero-duty fan reading is plausible (fan off)`() {
        // At 0% the device reports duty=0, period>0 — that's a valid live state.
        assertThat(FanCurveVerifier.isPlausibleLiveFan("0", "50000")).isTrue()
    }

    @Test
    fun `zero or unreadable period is not plausible`() {
        assertThat(FanCurveVerifier.isPlausibleLiveFan("100", "0")).isFalse()
        assertThat(FanCurveVerifier.isPlausibleLiveFan("100", null)).isFalse()
        assertThat(FanCurveVerifier.isPlausibleLiveFan(null, "50000")).isFalse()
    }

    @Test
    fun `duty greater than period is not plausible`() {
        assertThat(FanCurveVerifier.isPlausibleLiveFan("60000", "50000")).isFalse()
    }
}
