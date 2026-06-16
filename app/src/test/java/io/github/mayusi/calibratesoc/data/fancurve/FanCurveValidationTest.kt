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
        // NOT be rejected (only strictly-decreasing is invalid). The curve also
        // satisfies the hard hot-zone floor (reaches 100% well before 95C) so
        // the equal-temps acceptance is tested in isolation.
        val curve = FanCurve(
            listOf(
                FanCurvePoint(49, 20),
                FanCurvePoint(49, 50),
                FanCurvePoint(90, 100),
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

    // ── HARD hot-zone cooling floor (C1 / C2 — thermal safety) ─────────────────

    @Test
    fun `an all-20 percent curve is REJECTED (cannot cool the hot zone)`() {
        // A flat curve pinned at 20% everywhere, including 105C+ and the
        // sentinel. The stock UI clamps duty 0..100 so this is buildable via
        // setPointDuty — it MUST NOT validate.
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(50, 20),
                FanCurvePoint(95, 20),
                FanCurvePoint(105, 20),
                FanCurvePoint(SENTINEL_TEMP_C, 20),
            ),
        )
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("cool enough")
        assertThat(curve.isValid).isFalse()
    }

    @Test
    fun `a sentinel-collapse curve is REJECTED (hot band runs at the low ceiling)`() {
        // [(0,80),(90,80),(MAX,20)] — looks strong (80% at 90C) but everything
        // ABOVE 90C up to INT_MAX runs at the sentinel's 20%. The hot zone is
        // therefore 20%, and the curve MUST be rejected (C2).
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 80),
                FanCurvePoint(90, 80),
                FanCurvePoint(SENTINEL_TEMP_C, 20),
            ),
        )
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("cool enough")
    }

    @Test
    fun `a curve strong at 95C but weak at 105C is REJECTED (critical floor)`() {
        // Meets the 95C floor (45%) but collapses to 50% at the 105C+ critical
        // band via the sentinel — below the 60% critical floor.
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(95, 50),
                FanCurvePoint(105, 50),
                FanCurvePoint(SENTINEL_TEMP_C, 50),
            ),
        )
        val result = curve.validate()
        assertThat(result).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((result as FanCurveValidation.Invalid).reason).contains("critical")
    }

    @Test
    fun `a curve meeting both hot-zone floors is valid`() {
        // 45% at 95C and 60% at 105C+ exactly — the minimum safe shape.
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(95, 45),
                FanCurvePoint(105, 60),
                FanCurvePoint(SENTINEL_TEMP_C, 60),
            ),
        )
        assertThat(curve.validate()).isInstanceOf(FanCurveValidation.Valid::class.java)
    }

    @Test
    fun `the stock curve passes the hard hot-zone floor`() {
        // Regression: the stock "Smart" curve reaches 45% at 95C and 60% at 105C,
        // exactly meeting both floors — it must remain valid.
        assertThat(FanCurve.STOCK.validate()).isInstanceOf(FanCurveValidation.Valid::class.java)
    }

    @Test
    fun `hotZoneDuty reflects the MIN across the hot band including the sentinel`() {
        // For the sentinel-collapse shape the min effective duty at/above 95C is
        // the sentinel's 20%, NOT the 80% of the 90C point (C2).
        val collapse = FanCurve(
            listOf(
                FanCurvePoint(0, 80),
                FanCurvePoint(90, 80),
                FanCurvePoint(SENTINEL_TEMP_C, 20),
            ),
        )
        assertThat(collapse.hotZoneDuty()).isEqualTo(20)

        // For a curve whose real hot-zone points dip then recover, the MIN is
        // reported (not the last point).
        val dip = FanCurve(
            listOf(
                FanCurvePoint(0, 30),
                FanCurvePoint(95, 50),
                FanCurvePoint(105, 100),
                FanCurvePoint(SENTINEL_TEMP_C, 100),
            ),
        )
        assertThat(dip.hotZoneDuty()).isEqualTo(50)
    }

    @Test
    fun `bandMinDuty includes the governing point at the band edge`() {
        // At 95C the governing duty is the 85C point (45%) because no real point
        // sits between 85 and 95; the sentinel (60%) is higher, so the band min
        // is 45 — exactly the stock curve's hot-zone duty.
        assertThat(FanCurve.STOCK.bandMinDuty(95)).isEqualTo(45)
        // At 105C the governing + in-band points are 60 and the sentinel 60.
        assertThat(FanCurve.STOCK.bandMinDuty(105)).isEqualTo(60)
    }
}
