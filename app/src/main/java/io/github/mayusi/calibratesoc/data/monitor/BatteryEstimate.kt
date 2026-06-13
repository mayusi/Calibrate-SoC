package io.github.mayusi.calibratesoc.data.monitor

/**
 * Estimated battery time remaining based on live power draw and the current
 * charge counter reading from [BatteryManager].
 *
 * This is an APPROXIMATION. Power draw fluctuates with workload; the
 * estimate is only meaningful "at this load". The UI must label it
 * accordingly — never present it as an exact figure.
 *
 * @param hoursRemaining Estimated hours left, or null when not computable
 *   (see [basis] for the reason). Always positive when non-null.
 * @param watts Current smoothed discharge rate in watts (positive = draw).
 *   Null when power data is unavailable.
 * @param basis How (or why not) the estimate was produced. Always honest.
 */
data class BatteryEstimate(
    val hoursRemaining: Double?,
    val watts: Double?,
    val basis: EstimateBasis,
)

enum class EstimateBasis {
    /** Normal discharge: estimate is valid and based on live telemetry. */
    LIVE_DRAW,
    /** The device is currently charging; no discharge estimate is produced. */
    CHARGING,
    /** One or more required inputs (charge counter, power draw) are absent. */
    INSUFFICIENT_DATA,
}

/**
 * Computes a [BatteryEstimate] from a smoothed power value and current
 * charge counter reading.
 *
 * This is a pure function — all Android API calls are done in the caller
 * ([BatteryChargeReader] / [DashboardViewModel]) so this can be tested
 * on the JVM without Android dependencies.
 *
 * @param chargeCounterUah Remaining charge in µAh from
 *   [android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER], or null
 *   if the property is unsupported on this device.
 * @param nominalVoltageV The cell voltage to use for Wh calculation. Pass
 *   the live voltage (from [Telemetry.batteryVoltageUv] converted to V)
 *   when available; fall back to [FALLBACK_VOLTAGE_V] (3.85 V, typical
 *   Li-ion nominal) when telemetry voltage is null. The fallback is noted
 *   in [EstimateBasis] only implicitly — we do not surface it separately
 *   as it has minimal impact on the estimate (±5 % vs nominal).
 * @param smoothedPowerMilliW Rolling average of recent [Telemetry.batteryDrawMilliW]
 *   samples in milliwatts (positive = discharging). Null when no telemetry
 *   has arrived yet or all recent samples lacked current/voltage readings.
 */
fun computeBatteryEstimate(
    chargeCounterUah: Long?,
    nominalVoltageV: Double,
    smoothedPowerMilliW: Long?,
): BatteryEstimate {
    // No charge data → cannot produce any estimate.
    if (chargeCounterUah == null) {
        return BatteryEstimate(
            hoursRemaining = null,
            watts = smoothedPowerMilliW?.let { it / 1_000.0 },
            basis = EstimateBasis.INSUFFICIENT_DATA,
        )
    }

    // No power data → insufficient.
    if (smoothedPowerMilliW == null) {
        return BatteryEstimate(
            hoursRemaining = null,
            watts = null,
            basis = EstimateBasis.INSUFFICIENT_DATA,
        )
    }

    val powerW = smoothedPowerMilliW / 1_000.0

    // Non-positive draw means the device is idle/charging; skip discharge
    // estimate for v1 (time-to-full requires charge curve, out of scope).
    if (powerW <= 0.0) {
        return BatteryEstimate(
            hoursRemaining = null,
            watts = powerW,
            basis = EstimateBasis.CHARGING,
        )
    }

    // Remaining energy in watt-hours.
    // chargeCounterUah is in µAh; divide by 1_000_000 to get Ah, then * V = Wh.
    val energyRemainingWh = (chargeCounterUah / 1_000_000.0) * nominalVoltageV

    val hoursRemaining = energyRemainingWh / powerW

    return BatteryEstimate(
        hoursRemaining = hoursRemaining.takeIf { it.isFinite() && it > 0.0 },
        watts = powerW,
        basis = EstimateBasis.LIVE_DRAW,
    )
}

/**
 * Computes a rolling-average power (milliwatts) from the tail of the
 * telemetry history. Returns null when the history is empty or no sample
 * in the tail has a valid power reading.
 *
 * @param history Recent telemetry samples, ordered oldest-first (same
 *   ordering as [DashboardViewModel.history]).
 * @param tailSize How many of the most recent samples to average over.
 *   Defaults to [DEFAULT_SMOOTHING_SAMPLES] (12 seconds at 1 Hz).
 */
fun smoothedPowerMilliW(
    history: List<Telemetry>,
    tailSize: Int = DEFAULT_SMOOTHING_SAMPLES,
): Long? {
    val tail = if (history.size <= tailSize) history else history.takeLast(tailSize)
    val valid = tail.mapNotNull { it.batteryDrawMilliW }
    if (valid.isEmpty()) return null
    return valid.sum() / valid.size
}

/** Fallback cell voltage when telemetry voltage is absent. 3.85 V is the
 *  nominal midpoint for Li-ion / Li-poly cells used in handhelds. */
const val FALLBACK_VOLTAGE_V: Double = 3.85

/** Number of 1 Hz samples to average for smoothed power (~12 s window). */
const val DEFAULT_SMOOTHING_SAMPLES: Int = 12
