package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure tests for the FanCurve → AYANEO command mapping + the pwm%↔255 conversion.
 *
 * These pin the EXACT wire payload the gamewindow binder receives for a sample
 * curve (the `temp,duty|temp,duty|…` format under `FAN_MODE_CUSTOM-`), the
 * INT_MAX-sentinel projection, and the readback conversion used for verification.
 */
class AyaneoFanCurveMapperTest {

    @Test
    fun `toAyaneoPairs projects the INT_MAX sentinel to a sane high temp and keeps its duty`() {
        // The stock curve's final point is (INT_MAX, 60). On AYANEO that must become
        // (SENTINEL_PROJECTED_TEMP_C, 60) — duty preserved, threshold made finite.
        val pairs = AyaneoFanCurveMapper.toAyaneoPairs(FanCurve.STOCK)
        val last = pairs.last()
        assertThat(last.first).isEqualTo(AyaneoFanCurveMapper.SENTINEL_PROJECTED_TEMP_C)
        assertThat(last.second).isEqualTo(60)
        // Every NON-final point passes through unchanged.
        assertThat(pairs.dropLast(1)).isEqualTo(
            FanCurve.STOCK.points.dropLast(1).map { it.tempC to it.dutyPct },
        )
        // No projected pair carries the raw INT_MAX threshold.
        assertThat(pairs.none { it.first == FanCurve.SENTINEL_TEMP_C }).isTrue()
    }

    @Test
    fun `buildCurveCommand produces the exact com_set_fan_speed_strategy payload`() {
        // A simple, valid curve mirroring the stock thresholds.
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(45, 20),
                FanCurvePoint(65, 30),
                FanCurvePoint(85, 45),
                FanCurvePoint(105, 60),
                FanCurvePoint(FanCurve.SENTINEL_TEMP_C, 60),
            ),
        )
        assertThat(AyaneoFanCurveMapper.buildCurveCommand(curve)).isEqualTo(
            "calibrate:msg_type_performance:com_set_fan_speed_strategy:" +
                "FAN_MODE_CUSTOM-0,20|45,20|65,30|85,45|105,60|120,60",
        )
    }

    @Test
    fun `buildLinearityCommand defaults to STEP`() {
        assertThat(AyaneoFanCurveMapper.buildLinearityCommand())
            .isEqualTo("calibrate:msg_type_performance:com_set_fan_speed_is_linear:STEP")
        assertThat(AyaneoFanCurveMapper.buildLinearityCommand(linear = true))
            .isEqualTo("calibrate:msg_type_performance:com_set_fan_speed_is_linear:LINEAR")
    }

    // ── pwm%↔255 conversion ──────────────────────────────────────────────────

    @Test
    fun `rawToPercent converts the 0-255 PWM scale to 0-100 percent`() {
        assertThat(AyaneoPwm.rawToPercent(0)).isEqualTo(0)
        assertThat(AyaneoPwm.rawToPercent(255)).isEqualTo(100)
        // 128/255 ≈ 50.2 → rounds to 50.
        assertThat(AyaneoPwm.rawToPercent(128)).isEqualTo(50)
        // 64/255 ≈ 25.1 → 25.
        assertThat(AyaneoPwm.rawToPercent(64)).isEqualTo(25)
        // 191/255 ≈ 74.9 → rounds to 75.
        assertThat(AyaneoPwm.rawToPercent(191)).isEqualTo(75)
    }

    @Test
    fun `rawToPercent rejects out-of-range values honestly`() {
        assertThat(AyaneoPwm.rawToPercent(-1)).isNull()
        assertThat(AyaneoPwm.rawToPercent(256)).isNull()
    }

    @Test
    fun `rawStringToPercent parses then converts, null on garbage`() {
        assertThat(AyaneoPwm.rawStringToPercent("255")).isEqualTo(100)
        assertThat(AyaneoPwm.rawStringToPercent(" 128 ")).isEqualTo(50)
        assertThat(AyaneoPwm.rawStringToPercent("not-a-number")).isNull()
        assertThat(AyaneoPwm.rawStringToPercent(null)).isNull()
    }

    @Test
    fun `isPlausibleRawPwm accepts in-range (incl 0), rejects out-of-range or unreadable`() {
        // 0 is a VALID "fan off / idle low" reading — must be plausible.
        assertThat(AyaneoPwm.isPlausibleRawPwm("0")).isTrue()
        assertThat(AyaneoPwm.isPlausibleRawPwm("255")).isTrue()
        assertThat(AyaneoPwm.isPlausibleRawPwm("100")).isTrue()
        assertThat(AyaneoPwm.isPlausibleRawPwm("256")).isFalse()
        assertThat(AyaneoPwm.isPlausibleRawPwm("-5")).isFalse()
        assertThat(AyaneoPwm.isPlausibleRawPwm("nope")).isFalse()
        assertThat(AyaneoPwm.isPlausibleRawPwm(null)).isFalse()
    }
}
