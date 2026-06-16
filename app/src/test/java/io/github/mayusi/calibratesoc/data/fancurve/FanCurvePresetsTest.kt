package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Safety invariants for the built-in presets:
 *  - all structurally valid,
 *  - none ships a sub-20% duty floor,
 *  - none can let temps run away (strong duty by the hot zone),
 *  - Balanced mirrors the stock Odin curve exactly.
 */
class FanCurvePresetsTest {

    @Test
    fun `every preset is structurally valid`() {
        FanCurvePreset.entries.forEach { preset ->
            assertThat(preset.curve.validate())
                .isInstanceOf(FanCurveValidation.Valid::class.java)
        }
    }

    @Test
    fun `no built-in preset ships a sub-20 percent floor`() {
        FanCurvePreset.entries.forEach { preset ->
            val hasSubFloor = preset.curve.warnings()
                .any { it is FanCurveWarning.SubFloorDuty }
            assertThat(hasSubFloor).isFalse()
        }
    }

    @Test
    fun `no built-in preset triggers the runaway-cooling warning`() {
        FanCurvePreset.entries.forEach { preset ->
            val weakHot = preset.curve.warnings()
                .any { it is FanCurveWarning.WeakHotZone }
            assertThat(weakHot).isFalse()
        }
    }

    @Test
    fun `every preset reaches strong duty by 95C`() {
        FanCurvePreset.entries.forEach { preset ->
            val hotDuty = preset.curve.hotZoneDuty()!!
            assertThat(hotDuty).isAtLeast(FanCurve.MIN_HOT_DUTY_PCT)
        }
    }

    @Test
    fun `every preset duty is non-decreasing (no duty-drop warning)`() {
        FanCurvePreset.entries.forEach { preset ->
            val drops = preset.curve.warnings().any { it is FanCurveWarning.DutyDecreases }
            assertThat(drops).isFalse()
        }
    }

    @Test
    fun `balanced equals the stock curve`() {
        assertThat(FanCurvePreset.BALANCED.curve).isEqualTo(FanCurve.STOCK)
    }

    @Test
    fun `every preset ends in the INT_MAX sentinel`() {
        FanCurvePreset.entries.forEach { preset ->
            assertThat(preset.curve.points.last().isSentinel).isTrue()
        }
    }

    @Test
    fun `byId resolves known presets and rejects unknown`() {
        assertThat(FanCurvePreset.byId("quiet")).isEqualTo(FanCurvePreset.QUIET)
        assertThat(FanCurvePreset.byId("nope")).isNull()
        assertThat(FanCurvePreset.byId(null)).isNull()
    }
}
