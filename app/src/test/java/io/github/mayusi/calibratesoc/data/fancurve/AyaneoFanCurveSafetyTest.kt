package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The hard hot-zone cooling floor ([FanCurve.validate], NOT opt-out-able) applies
 * EQUALLY to AYANEO — an AYANEO curve must be just as unable to overheat the device
 * as an Odin curve. The AYANEO apply path runs the SAME [FanCurve.validate] before
 * it ever builds a binder command, so these tests assert the shared safety contract
 * AND that every shippable preset maps to a clean AYANEO command.
 */
class AyaneoFanCurveSafetyTest {

    @Test
    fun `an overheating curve is REJECTED by validate - the AYANEO apply can never see it`() {
        // Pins the fan at the 20% floor right through the hot + critical zones:
        // 20% at 95C is below the 45% hot floor → hard Invalid. The controller's
        // AYANEO branch validates FIRST, so this curve never reaches the binder.
        val overheating = FanCurve(
            listOf(
                FanCurvePoint(0, 20),
                FanCurvePoint(95, 20),
                FanCurvePoint(105, 20),
                FanCurvePoint(FanCurve.SENTINEL_TEMP_C, 20),
            ),
        )
        val validation = overheating.validate()
        assertThat(validation).isInstanceOf(FanCurveValidation.Invalid::class.java)
        assertThat((validation as FanCurveValidation.Invalid).reason).contains("cool enough")
    }

    @Test
    fun `a curve weak only at the critical zone is also REJECTED`() {
        // Strong enough at 95C (60%) but collapses by 105C (only 50% at/above the
        // critical zone) — below the 60% critical floor. Still a hard Invalid.
        val weakCritical = FanCurve(
            listOf(
                FanCurvePoint(0, 30),
                FanCurvePoint(95, 60),
                FanCurvePoint(105, 50),
                FanCurvePoint(FanCurve.SENTINEL_TEMP_C, 50),
            ),
        )
        assertThat(weakCritical.validate()).isInstanceOf(FanCurveValidation.Invalid::class.java)
    }

    @Test
    fun `every shippable preset passes validate AND maps to a clean AYANEO command`() {
        FanCurvePreset.entries.forEach { preset ->
            // 1. Safety: every built-in preset clears the hard cooling floor.
            assertThat(preset.curve.validate()).isEqualTo(FanCurveValidation.Valid)

            // 2. Mapping: it produces a well-formed AYANEO command — FAN_MODE_CUSTOM
            //    prefix, pipe-separated temp,duty pairs, no INT_MAX leaking through.
            val cmd = AyaneoFanCurveMapper.buildCurveCommand(preset.curve)
            assertThat(cmd).startsWith(
                "calibrate:msg_type_performance:com_set_fan_speed_strategy:FAN_MODE_CUSTOM-",
            )
            assertThat(cmd).doesNotContain("2147483647")
            // Same point count as the curve, pipe-separated.
            val body = cmd.substringAfter("FAN_MODE_CUSTOM-")
            assertThat(body.split("|")).hasSize(preset.curve.points.size)
            // Every pair is `int,int`.
            body.split("|").forEach { pair ->
                val (t, d) = pair.split(",")
                assertThat(t.toIntOrNull()).isNotNull()
                assertThat(d.toIntOrNull()).isNotNull()
            }
        }
    }

    @Test
    fun `the projected sentinel duty preserves the hot-zone cooling the curve guarantees`() {
        // The STOCK curve guarantees >=60% above 105C via its INT_MAX sentinel.
        // After projection the final AYANEO pair must STILL carry that 60% duty at
        // a temp above the critical zone, so the cooling floor is carried over.
        val pairs = AyaneoFanCurveMapper.toAyaneoPairs(FanCurve.STOCK)
        val last = pairs.last()
        assertThat(last.first).isAtLeast(FanCurve.CRITICAL_ZONE_TEMP_C)
        assertThat(last.second).isAtLeast(FanCurve.MIN_CRITICAL_DUTY_PCT)
    }
}
