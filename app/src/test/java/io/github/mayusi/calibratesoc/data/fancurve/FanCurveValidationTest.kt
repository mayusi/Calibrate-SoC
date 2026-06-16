package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.fancurve.FanCurve.Companion.SENTINEL_TEMP_C
import org.junit.Test

/**
 * Validation invariants for [FanCurve]: monotonic temp, duty range, the
 * required INT_MAX sentinel ending, point-count bounds, and the
 * recommendation-level warnings (sub-floor, runaway, duty-decrease).
 */
class FanCurveValidationTest {

    private fun valid() = FanCurve(
        listOf(
            FanCurvePoint(0, 30),
            FanCurvePoint(60, 50),
            FanCurvePoint(95, 80),
            FanCurvePoint(SENTINEL_TEMP_C, 100),
        ),
    )

    @Test
    fun `the stock curve is valid`() {
        assertThat(FanCurve.STOCK.validate()).isInstanceOf(FanCurveValidation.Valid::class.java)
        assertThat(FanCurve.STOCK.isValid).isTrue()
    }

    @Test
    fun `a well-formed curve is valid`() {
        assertThat(valid().validate()).isInstanceOf(FanCurveValidation.Valid::class.java)
    }

    @Test
    fun `non-monotonic temperature is rejected`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 30),
                FanCurvePoint(60, 50),
                FanCurvePoint(40, 60), // drops below previous temp
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("decrease")
    }

    @Test
    fun `duty above 100 is rejected`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 30),
                FanCurvePoint(60, 101), // out of range
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("range")
    }

    @Test
    fun `negative duty is rejected`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, -1),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        assertThat(curve.validate()).isInstanceOf(FanCurveValidation.Invalid::class.java)
    }

    @Test
    fun `missing sentinel ending is rejected`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 30),
                FanCurvePoint(60, 50),
                FanCurvePoint(105, 80), // last point is NOT the INT_MAX sentinel
            ),
        )
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("catch-all ceiling")
    }

    @Test
    fun `sentinel in a non-final position is rejected`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 30),
                FanCurvePoint(SENTINEL_TEMP_C, 50), // sentinel mid-list
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        assertThat(curve.validate()).isInstanceOf(FanCurveValidation.Invalid::class.java)
    }

    @Test
    fun `too few points is rejected`() {
        val curve = FanCurve(listOf(FanCurvePoint(SENTINEL_TEMP_C, 50)))
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("at least")
    }

    @Test
    fun `too many points is rejected`() {
        val pts = (0 until FanCurve.MAX_POINTS).map { FanCurvePoint(it, 20 + it) } +
            FanCurvePoint(SENTINEL_TEMP_C, 100)
        val result = FanCurve(pts).validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("at most")
    }

    @Test
    fun `equal adjacent temps are allowed (monotonic non-decreasing)`() {
        // Used for near-vertical steps via adjacent thresholds; equal temps must
        // NOT be rejected (only strictly-decreasing is invalid).
        val curve = FanCurve(
            listOf(
                FanCurvePoint(49, 20),
                FanCurvePoint(49, 50),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        assertThat(curve.validate()).isInstanceOf(FanCurveValidation.Valid::class.java)
    }

    // ── Warnings ──────────────────────────────────────────────────────────────

    @Test
    fun `a clean curve produces no warnings`() {
        assertThat(valid().warnings()).isEmpty()
    }

    @Test
    fun `duty decreasing as temp rises produces a warning`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 60),
                FanCurvePoint(60, 40), // duty drops
                FanCurvePoint(95, 80),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        val warnings = curve.warnings()
        assertThat(warnings.any { it is FanCurveWarning.DutyDecreases }).isTrue()
    }

    @Test
    fun `sub-floor duty produces a warning`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 10), // below 20% floor
                FanCurvePoint(60, 50),
                FanCurvePoint(95, 80),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        assertThat(curve.warnings().any { it is FanCurveWarning.SubFloorDuty }).isTrue()
    }

    @Test
    fun `weak hot zone produces a runaway warning`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(95, 25), // only 25% in the hot zone
                FanCurvePoint(SENTINEL_TEMP_C, 30),
            ),
        )
        assertThat(curve.warnings().any { it is FanCurveWarning.WeakHotZone }).isTrue()
    }
}
