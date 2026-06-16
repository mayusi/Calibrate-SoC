package io.github.mayusi.calibratesoc.data.thermal

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.FanProbe
import io.github.mayusi.calibratesoc.data.capability.FanSource

/**
 * Fan-curve model — pure logic with an honest capability gate.
 *
 * Models a temperature→fan-duty mapping as a set of (tempC, dutyPct) control
 * points that are linearly interpolated to yield a duty cycle for any given
 * temperature.  A duty cycle of 0% = fan off, 100% = full speed.
 *
 * ## Capability gate — HONESTY INVARIANT
 *
 * Fan control is only offered when a device actually exposes a writable fan
 * surface.  Three surfaces are recognised:
 *
 *  - [FanSource.HWMON_PWM] — generic /sys/class/hwmon/hwmonN/pwm1 node.
 *    Requires root or the unlocked-sysfs tier.  Found on some Windows-
 *    compatible handhelds (GPD, AYANEO via hwmon).
 *  - [FanSource.VENDOR_SETTINGS_KEY] — AYN / Retroid "fan_mode" Settings.System
 *    key.  Writable when the AYN game-assistant or retroid companion is
 *    present AND the app holds WRITE_SECURE_SETTINGS (VENDOR_SETTINGS tier).
 *    Full PWM curve is not available here — only presets — but we still model
 *    a duty→preset mapping so the service can pick the nearest preset.
 *  - [FanSource.VENDOR_SERVICE_INTENT] — AYANEO AYASpace binder. NOT treated as
 *    controllable by THIS model: no FanProbe-driven consumer writes via a generic
 *    service-intent source (every fan write path in the app filters on the
 *    Settings.System key). The AYANEO binder fan editor that ships lives in
 *    data/fancurve and gates on [CapabilityReport.ayaneoBinderLive], not on this
 *    enum value, so it does not make this source controllable here.
 *
 * [FanSource.VENDOR_SERVICE_INTENT], [FanSource.THERMAL_COOLING_DEVICE] and
 * [FanSource.NONE] are NOT treated as controllable by this model. Thermal cooling
 * devices expose only integer
 * "levels" (0 = off, N = max); the kernel drives them automatically via the
 * thermal framework.  We do NOT override kernel thermal decisions — that would
 * risk hardware damage.
 *
 * When no controllable fan is present, [isActive] is false and [evaluate]
 * always returns null.  Callers MUST check [isActive] before writing.
 *
 * ## Curve semantics
 *
 * Control points are (tempC, dutyPct) pairs. Between two consecutive points
 * the duty is linearly interpolated.  Below the lowest control point the duty
 * is clamped to the lowest duty.  Above the highest control point the duty is
 * clamped to 100%.
 *
 * The default curve shipped with this model:
 *
 *   40 °C → 0 %    (fan off / minimum)
 *   55 °C → 30 %   (audible but quiet)
 *   70 °C → 65 %   (typical gaming load)
 *   80 °C → 90 %   (heavy sustained load)
 *   85 °C → 100 %  (maximum)
 *
 * These are sensible defaults; the user/service layer can replace them via
 * [copy] or by constructing a new model with custom points.
 */
data class FanCurveModel(
    /**
     * Control points — (tempC, dutyPct) in ascending temperature order.
     * Must have at least two points; validated in [validate].
     * dutyPct is clamped to [0, 100] at evaluation time.
     */
    val points: List<CurvePoint>,
    /**
     * The [FanProbe] from [CapabilityReport.fan] at model creation time.
     * Null → no fan surface found → [isActive] == false.
     */
    val fanProbe: FanProbe?,
) {

    /** One (temperature °C, duty %) control point. */
    data class CurvePoint(
        val tempC: Float,
        /** Fan duty as a percentage [0, 100]. */
        val dutyPct: Float,
    )

    /**
     * Whether the model can actually do anything on this device.
     *
     * True when [fanProbe] describes a surface this model knows how to
     * drive (HWMON_PWM or a vendor key/service).
     *
     * False → the caller must NOT attempt any fan write.
     */
    val isActive: Boolean
        get() = fanProbe != null && fanProbe.source.isControllable()

    /**
     * Evaluate the curve at [tempC] and return the recommended duty percent.
     *
     * Returns null when [isActive] is false (no controllable fan present) —
     * the null return is a hard gate: never write a zero duty to silence the
     * fan on a device where fan writes are unsupported; that silences the
     * kernel's own thermal-cooling path.
     *
     * When [isActive] is true, returns a value in [0.0, 100.0].
     */
    fun evaluate(tempC: Float): Float? {
        if (!isActive) return null
        if (points.isEmpty()) return null

        val sorted = points.sortedBy { it.tempC }

        // Below the lowest control point — clamp to lowest duty.
        if (tempC <= sorted.first().tempC) return sorted.first().dutyPct.clampDuty()

        // Above the highest control point — clamp to 100%.
        if (tempC >= sorted.last().tempC) return 100f

        // Linear interpolation between the two bounding control points.
        val lower = sorted.lastOrNull { it.tempC <= tempC } ?: return sorted.first().dutyPct.clampDuty()
        val upper = sorted.firstOrNull { it.tempC > tempC } ?: return 100f

        val fraction = (tempC - lower.tempC) / (upper.tempC - lower.tempC)
        val duty = lower.dutyPct + fraction * (upper.dutyPct - lower.dutyPct)
        return duty.clampDuty()
    }

    /**
     * Validate the curve definition. Returns a non-null error string when
     * the curve is invalid (caller should log/show the message and fall back
     * to [DEFAULT]).
     */
    fun validate(): String? {
        if (points.size < 2) return "FanCurveModel requires at least 2 control points (got ${points.size})"
        val sorted = points.sortedBy { it.tempC }
        for (i in 1 until sorted.size) {
            if (sorted[i].tempC <= sorted[i - 1].tempC) {
                return "FanCurveModel points must have strictly increasing temperature: " +
                    "${sorted[i - 1].tempC}°C >= ${sorted[i].tempC}°C at index $i"
            }
        }
        return null
    }

    private fun Float.clampDuty() = coerceIn(0f, 100f)

    companion object {

        /**
         * Factory: build a [FanCurveModel] with the default curve for the
         * given [CapabilityReport].
         *
         * When the report has no controllable fan, returns a model with
         * [isActive] == false.  No exceptions thrown.
         */
        fun fromReport(report: CapabilityReport): FanCurveModel =
            FanCurveModel(
                points = DEFAULT_POINTS,
                fanProbe = report.fan?.takeIf { it.source.isControllable() },
            )

        /**
         * Build a model with custom [points] for the given [fanProbe].
         *
         * Useful for testing or when the user configures a custom curve.
         * Pass null for [fanProbe] to create a permanently inactive model
         * (useful in tests that verify the gate).
         */
        fun withPoints(points: List<CurvePoint>, fanProbe: FanProbe?): FanCurveModel =
            FanCurveModel(points = points, fanProbe = fanProbe)

        /**
         * Default fan curve control points — conservative but effective for
         * handheld gaming devices.
         *
         * The curve is designed so the fan is silent during light browsing
         * (≤40 °C), audible-but-quiet during moderate gaming (≈55 °C), and
         * at full speed before the SoC would self-throttle (≥85 °C).
         */
        val DEFAULT_POINTS: List<CurvePoint> = listOf(
            CurvePoint(tempC = 40f, dutyPct = 0f),
            CurvePoint(tempC = 55f, dutyPct = 30f),
            CurvePoint(tempC = 70f, dutyPct = 65f),
            CurvePoint(tempC = 80f, dutyPct = 90f),
            CurvePoint(tempC = 85f, dutyPct = 100f),
        )

        /**
         * A permanently-inactive model with the default curve.
         * Useful as a safe default when the device has no fan.
         */
        val INACTIVE: FanCurveModel = FanCurveModel(points = DEFAULT_POINTS, fanProbe = null)
    }
}

/**
 * Whether this [FanSource] is something the app can ACTUALLY drive today.
 *
 * HWMON_PWM → full duty control (0–255 mapped to 0–100 %), via the unlocked-sysfs
 *   / root tier.
 * VENDOR_SETTINGS_KEY → AYN / Retroid "fan_mode" Settings.System preset path. This
 *   is the one vendor path every fan consumer in the app actually writes
 *   (AutoTdpService / GameBoostService / ForegroundAppWatcher all gate on
 *   FanAdapterKind.SETTINGS_KEY).
 *
 * VENDOR_SERVICE_INTENT → HONESTY FIX: returns FALSE. No consumer in the app drives
 *   a fan via a generic VENDOR_SERVICE_INTENT source — every fan write path filters
 *   on SETTINGS_KEY. (The AYANEO binder fan editor that DOES ship lives in
 *   data/fancurve and gates on CapabilityReport.ayaneoBinderLive, NOT on this enum
 *   value, so it does not make this source controllable here.) Advertising control
 *   for VENDOR_SERVICE_INTENT would over-claim a capability the model cannot deliver,
 *   so we report it as not-controllable until a real FanProbe-driven driver exists.
 *
 * THERMAL_COOLING_DEVICE → kernel-driven; we do NOT touch it.
 * NONE → no fan hardware.
 */
fun FanSource.isControllable(): Boolean = when (this) {
    FanSource.HWMON_PWM,
    FanSource.VENDOR_SETTINGS_KEY -> true
    FanSource.VENDOR_SERVICE_INTENT,
    FanSource.THERMAL_COOLING_DEVICE,
    FanSource.NONE -> false
}
