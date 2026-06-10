package io.github.mayusi.calibratesoc.data.prefs

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure formatting helpers that convert raw sensor values to strings
 * using the user's preferred units. Injected as a singleton so any
 * ViewModel can use the same instance once other screens adopt it.
 *
 * Neither function is stateful — the [unit] parameters come from
 * the callers so the formatter is trivially testable without a DataStore.
 */
@Singleton
class UnitsFormatter @Inject constructor() {

    /**
     * Format a raw MHz integer for display.
     *
     * Examples:
     *   formatClockMhz(2400, ClockUnit.MHZ)  → "2400 MHz"
     *   formatClockMhz(2400, ClockUnit.GHZ)  → "2.40 GHz"
     *   formatClockMhz(0,    ClockUnit.MHZ)  → "0 MHz"
     */
    fun formatClockMhz(mhz: Int, unit: ClockUnit): String = when (unit) {
        ClockUnit.MHZ -> "$mhz MHz"
        ClockUnit.GHZ -> "%.2f GHz".format(mhz / 1000.0)
    }

    /**
     * Format a Celsius float for display.
     *
     * Examples:
     *   formatTempC(45.0f, TempUnit.CELSIUS)    → "45.0°C"
     *   formatTempC(45.0f, TempUnit.FAHRENHEIT) → "113.0°F"
     */
    fun formatTempC(c: Float, unit: TempUnit): String = when (unit) {
        TempUnit.CELSIUS    -> "%.1f°C".format(c)
        TempUnit.FAHRENHEIT -> "%.1f°F".format(c * 9f / 5f + 32f)
    }
}
