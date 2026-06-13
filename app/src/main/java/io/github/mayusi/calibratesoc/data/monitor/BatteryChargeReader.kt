package io.github.mayusi.calibratesoc.data.monitor

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER]
 * so the ViewModel can stay decoupled from Android framework APIs and
 * tests can supply a fake implementation.
 *
 * This is a cheap binder call (~0.1 ms) and is safe to invoke from
 * the main thread, though in practice the ViewModel calls it from
 * [viewModelScope] which runs on Dispatchers.Main.immediate.
 *
 * Returns null when the device does not expose the property (returns –1).
 * This is documented as a possibility by the Android framework — e.g.
 * some vendor kernels on the Retroid Pocket 6 may not support it. In
 * that case [computeBatteryEstimate] will return INSUFFICIENT_DATA.
 */
@Singleton
class BatteryChargeReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bm: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    /**
     * Returns remaining charge in µAh, or null if unsupported / error.
     *
     * [BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER] returns –1 when the
     * property is unsupported. We convert that sentinel to null so callers
     * can treat absence uniformly.
     */
    fun readChargeCounterUah(): Long? {
        val raw = bm?.runCatching {
            getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }?.getOrNull() ?: return null
        return if (raw < 0L) null else raw
    }
}
