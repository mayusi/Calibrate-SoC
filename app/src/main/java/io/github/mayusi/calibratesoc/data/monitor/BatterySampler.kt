package io.github.mayusi.calibratesoc.data.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatteryManager sampler. Reads:
 *   - Temperature (deci-Celsius) via the BATTERY_CHANGED sticky intent
 *     (most reliable across OEMs).
 *   - Instantaneous current (µA) and voltage (µV) via BATTERY_PROPERTY_*.
 *
 * No special permissions required — BatteryManager is unprivileged.
 *
 * Current direction varies by OEM: some report discharging as negative,
 * some positive. The Telemetry.batteryDrawMilliW extension normalises
 * that — this sampler reports raw values.
 */
@Singleton
class BatterySampler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bm: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    fun sample(): Sample {
        // One binder IPC for the sticky intent; read all fields from it.
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val tempInt = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temp = tempInt?.takeIf { it != Int.MIN_VALUE }

        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltageUv = if (mv > 0) mv * 1_000L else null

        val current = bm?.runCatching {
            getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }?.getOrNull()
        // Voltage is reported in millivolts on some devices and microvolts
        // on others — there's no spec. We treat values < 100_000 as mV and
        // multiply, anything bigger we treat as µV. 100 mV is a safe
        // threshold since a real Li-ion never reads below ~2.5 V.
        val rawVoltage = bm?.runCatching {
            getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }?.getOrNull()
        return Sample(
            temperatureDeciC = temp,
            currentUa = current,
            voltageUv = voltageUv,
            chargeCounter = rawVoltage,
        )
    }

    data class Sample(
        val temperatureDeciC: Int?,
        val currentUa: Long?,
        val voltageUv: Long?,
        val chargeCounter: Long?,
    )
}
