package io.github.mayusi.calibratesoc.data.thermal

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.FanProbe
import io.github.mayusi.calibratesoc.data.capability.FanSource
import org.junit.Test

class FanCurveModelTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A FanProbe representing a real writable PWM fan node. */
    private val hwmonProbe = FanProbe(
        source = FanSource.HWMON_PWM,
        controlPath = "/sys/class/hwmon/hwmon0/pwm1",
        supportsCurve = true,
        availablePresets = emptyList(),
        currentRpm = 1200,
    )

    /** A FanProbe for AYN-style vendor settings key. */
    private val vendorKeyProbe = FanProbe(
        source = FanSource.VENDOR_SETTINGS_KEY,
        controlPath = "fan_mode",
        supportsCurve = false,
        availablePresets = listOf("silent", "normal", "sport"),
        currentRpm = null,
    )

    /** A thermal-cooling-device probe — NOT directly controllable by us. */
    private val thermalCoolProbe = FanProbe(
        source = FanSource.THERMAL_COOLING_DEVICE,
        controlPath = "/sys/class/thermal/cooling_device0/cur_state",
        supportsCurve = false,
        availablePresets = emptyList(),
        currentRpm = null,
    )

    private val defaultPoints = FanCurveModel.DEFAULT_POINTS

    // ── isActive gate ─────────────────────────────────────────────────────────

    @Test
    fun `model with null fanProbe is not active`() {
        val model = FanCurveModel(points = defaultPoints, fanProbe = null)
        assertThat(model.isActive).isFalse()
    }

    @Test
    fun `model with HWMON_PWM probe is active`() {
        val model = FanCurveModel(points = defaultPoints, fanProbe = hwmonProbe)
        assertThat(model.isActive).isTrue()
    }

    @Test
    fun `model with VENDOR_SETTINGS_KEY probe is active`() {
        val model = FanCurveModel(points = defaultPoints, fanProbe = vendorKeyProbe)
        assertThat(model.isActive).isTrue()
    }

    @Test
    fun `model with VENDOR_SERVICE_INTENT probe is NOT active (honesty fix)`() {
        // HONESTY: no FanProbe-driven consumer writes via a generic service-intent
        // source — every fan write path in the app filters on the Settings.System
        // key. Advertising control here would over-claim a capability the model
        // cannot deliver, so VENDOR_SERVICE_INTENT must NOT be active.
        val intentProbe = FanProbe(
            source = FanSource.VENDOR_SERVICE_INTENT,
            controlPath = "com.ayaneo.ayaspace/.FanService",
            supportsCurve = false,
            availablePresets = listOf("quiet", "normal", "turbo"),
            currentRpm = null,
        )
        val model = FanCurveModel(points = defaultPoints, fanProbe = intentProbe)
        assertThat(model.isActive).isFalse()
    }

    @Test
    fun `model with THERMAL_COOLING_DEVICE probe is NOT active`() {
        val model = FanCurveModel(points = defaultPoints, fanProbe = thermalCoolProbe)
        assertThat(model.isActive).isFalse()
    }

    // ── evaluate returns null when inactive ───────────────────────────────────

    @Test
    fun `evaluate returns null when isActive is false (null probe)`() {
        val model = FanCurveModel(points = defaultPoints, fanProbe = null)
        assertThat(model.evaluate(70f)).isNull()
    }

    @Test
    fun `evaluate returns null for thermal-cooling-device (kernel-driven)`() {
        val model = FanCurveModel(points = defaultPoints, fanProbe = thermalCoolProbe)
        assertThat(model.evaluate(80f)).isNull()
    }

    // ── Curve interpolation ───────────────────────────────────────────────────

    @Test
    fun `evaluate at lowest control point returns lowest duty`() {
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 0f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(40f)).isWithin(0.01f).of(0f)
    }

    @Test
    fun `evaluate at highest control point returns 100 percent`() {
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 0f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(80f)).isWithin(0.01f).of(100f)
    }

    @Test
    fun `evaluate above highest control point clamps to 100 percent`() {
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 0f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(95f)).isWithin(0.01f).of(100f)
    }

    @Test
    fun `evaluate below lowest control point clamps to lowest duty`() {
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 10f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(20f)).isWithin(0.01f).of(10f)
    }

    @Test
    fun `evaluate midpoint interpolates linearly`() {
        // 40°C→0%, 80°C→100%; midpoint 60°C should be 50%.
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 0f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(60f)).isWithin(0.1f).of(50f)
    }

    @Test
    fun `evaluate at quarter interpolation point is correct`() {
        // 40°C→0%, 80°C→100%; 50°C (quarter point) should be 25%.
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 0f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(50f)).isWithin(0.1f).of(25f)
    }

    @Test
    fun `evaluate uses multiple segments correctly`() {
        // Three-point curve: 40°C→0%, 60°C→50%, 80°C→100%.
        // At 70°C (between 60 and 80): fraction=(70-60)/(80-60)=0.5 → 50+0.5*50=75%.
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(40f, 0f),
                FanCurveModel.CurvePoint(60f, 50f),
                FanCurveModel.CurvePoint(80f, 100f),
            ),
            fanProbe = hwmonProbe,
        )
        assertThat(model.evaluate(70f)).isWithin(0.1f).of(75f)
    }

    // ── Default curve sanity ──────────────────────────────────────────────────

    @Test
    fun `default curve has 5 points in ascending temperature order`() {
        val points = FanCurveModel.DEFAULT_POINTS
        assertThat(points).hasSize(5)
        for (i in 1 until points.size) {
            assertThat(points[i].tempC).isGreaterThan(points[i - 1].tempC)
        }
    }

    @Test
    fun `default curve returns 0 percent at 40°C (fan off at idle)`() {
        val model = FanCurveModel(points = FanCurveModel.DEFAULT_POINTS, fanProbe = hwmonProbe)
        assertThat(model.evaluate(40f)).isWithin(0.01f).of(0f)
    }

    @Test
    fun `default curve returns 100 percent at or above 85°C`() {
        val model = FanCurveModel(points = FanCurveModel.DEFAULT_POINTS, fanProbe = hwmonProbe)
        assertThat(model.evaluate(85f)).isWithin(0.01f).of(100f)
        assertThat(model.evaluate(100f)).isWithin(0.01f).of(100f)
    }

    @Test
    fun `INACTIVE model is not active`() {
        assertThat(FanCurveModel.INACTIVE.isActive).isFalse()
        assertThat(FanCurveModel.INACTIVE.evaluate(75f)).isNull()
    }

    // ── validate ─────────────────────────────────────────────────────────────

    @Test
    fun `validate returns null for valid default points`() {
        val model = FanCurveModel(points = FanCurveModel.DEFAULT_POINTS, fanProbe = null)
        assertThat(model.validate()).isNull()
    }

    @Test
    fun `validate returns error for single point`() {
        val model = FanCurveModel(
            points = listOf(FanCurveModel.CurvePoint(60f, 50f)),
            fanProbe = null,
        )
        assertThat(model.validate()).isNotNull()
    }

    @Test
    fun `validate returns error for duplicate temperatures`() {
        val model = FanCurveModel(
            points = listOf(
                FanCurveModel.CurvePoint(60f, 50f),
                FanCurveModel.CurvePoint(60f, 80f),
            ),
            fanProbe = null,
        )
        assertThat(model.validate()).isNotNull()
    }

    // ── isControllable extension ──────────────────────────────────────────────

    @Test
    fun `isControllable returns true for HWMON_PWM`() {
        assertThat(FanSource.HWMON_PWM.isControllable()).isTrue()
    }

    @Test
    fun `isControllable returns true for VENDOR_SETTINGS_KEY`() {
        assertThat(FanSource.VENDOR_SETTINGS_KEY.isControllable()).isTrue()
    }

    @Test
    fun `isControllable returns false for VENDOR_SERVICE_INTENT (honesty fix)`() {
        // No fan consumer drives a generic service-intent source; reporting it
        // controllable would over-claim. The shipping AYANEO binder fan editor
        // gates on CapabilityReport.ayaneoBinderLive, not on this enum value.
        assertThat(FanSource.VENDOR_SERVICE_INTENT.isControllable()).isFalse()
    }

    @Test
    fun `isControllable returns false for THERMAL_COOLING_DEVICE`() {
        assertThat(FanSource.THERMAL_COOLING_DEVICE.isControllable()).isFalse()
    }

    @Test
    fun `isControllable returns false for NONE`() {
        assertThat(FanSource.NONE.isControllable()).isFalse()
    }
}
