package io.github.mayusi.calibratesoc.ui.overlay

/**
 * Pure display-formatting helpers for the HUD overlay.
 *
 * Kept out of [HudOverlayContent] so they can be unit-tested on the JVM
 * without a Compose harness.
 */
object HudDisplayUtils {

    /**
     * Format the AutoTDP compact status line text.
     *
     * Examples:
     *  - Running, savings ready:   "AutoTDP ● −1800mW parked cpu6,7 cap 1.69G"
     *  - Running, measuring:       "AutoTDP ● measuring…  parked cpu6,7"
     *  - Not running:              ""
     *
     * Honesty contract: savings text is only included when [savingsReady] is
     * true and [savingsMw] is non-null. Never fabricate.
     *
     * @param running         true when AutoTDP status is RUNNING
     * @param parkedCores     set of core indices AutoTDP has offlined
     * @param bigCapMhz       big-cluster MHz cap, null = uncapped
     * @param savingsMw       measured power delta in mW, null = not yet measured
     * @param savingsReady    true only when enough samples exist to show savings
     * @return                display string; empty when not running
     */
    @JvmStatic
    fun formatAutoTdpCompactLine(
        running: Boolean,
        parkedCores: Set<Int>,
        bigCapMhz: Int?,
        savingsMw: Int?,
        savingsReady: Boolean,
    ): String {
        if (!running) return ""
        val parkedStr = if (parkedCores.isEmpty()) ""
            else " parked cpu${parkedCores.sorted().joinToString(",")}"
        val capStr = bigCapMhz?.let { " cap ${"%.2f".format(it / 1000.0)}G" } ?: ""
        val savingsStr = when {
            savingsReady && savingsMw != null -> "  −${savingsMw}mW"
            else -> "  measuring…"
        }
        return "AutoTDP ●$parkedStr$capStr$savingsStr"
    }

    /**
     * Decide whether the manual tune steppers should be gated (disabled)
     * because AutoTDP is currently managing the CPU clocks.
     *
     * This is a two-layer gate:
     *  1. [HudTuneController.stepBigCoreMhz] checks this and returns early.
     *  2. [HudOverlayContent] hides/replaces the stepper row when gated.
     *
     * A simple pure function so we can unit-test the gate rule without
     * any Android or coroutine dependencies.
     *
     * @param autoTdpRunning  true when AutoTDP status is RUNNING
     * @return                true when the steppers must be gated
     */
    @JvmStatic
    fun shouldGateSteppers(autoTdpRunning: Boolean): Boolean = autoTdpRunning

    /**
     * Format the FPS honesty label shown in the compact HUD.
     *
     * Returns "FPS" (real game framerate) or "REFRESH" (HUD cadence) based
     * on whether the sampled value is distinguishable from known refresh
     * rates. Also returns the sub-label ("game" or "Hz").
     *
     * Heuristic: if fps matches hudHz exactly, or falls in the 59-61 or
     * 119-121 band (common panel refresh rates), we label it REFRESH.
     *
     * @param fps    sampled FPS value
     * @param hudHz  HUD Choreographer rate
     * @return       Pair(label, sublabel) — e.g. ("FPS", "game") or ("REFRESH", "Hz")
     */
    @JvmStatic
    fun fpsTileLabel(fps: Int, hudHz: Int): Pair<String, String> {
        val isLikelyReal = fps != hudHz && fps !in 59..61 && fps !in 119..121
        return if (isLikelyReal) "FPS" to "game" else "REFRESH" to "Hz"
    }

    /**
     * Format the per-cluster cap label for a stepper button row.
     *
     * Example: "2918MHz" or "—" when null.
     */
    @JvmStatic
    fun formatClusterMhz(mhz: Int?): String = mhz?.let { "${it}MHz" } ?: "—"

    /**
     * Format a watts reading concisely for the HUD.
     *
     * Examples: "4.2W", "0.8W", "—".
     */
    @JvmStatic
    fun formatWatts(watts: Double?): String = watts?.let { "%.1fW".format(it) } ?: "—"

    /**
     * Format a temperature reading with one decimal.
     *
     * Examples: "72°C", "—".
     */
    @JvmStatic
    fun formatTemp(tempC: Float?): String = tempC?.let { "%.0f°C".format(it) } ?: "—"

    /**
     * Format a GHz value from MHz — returns e.g. "2.92G" or "—".
     */
    @JvmStatic
    fun formatGhzFromMhz(mhz: Int?): String = mhz?.let { "%.2fG".format(it / 1000.0) } ?: "—"

    /**
     * HUD width in dp for a given size index.
     *
     * 0 = small  (220dp)
     * 1 = medium (270dp, default)
     * 2 = large  (330dp)
     */
    @JvmStatic
    fun hudWidthDp(sizeIndex: Int): Int = when (sizeIndex.coerceIn(0, 2)) {
        0 -> 220
        1 -> 270
        else -> 330
    }

    /**
     * Label for the HUD size cycle button shown in verbose mode.
     *
     * Examples: "SM", "MD", "LG".
     */
    @JvmStatic
    fun hudSizeLabel(sizeIndex: Int): String = when (sizeIndex.coerceIn(0, 2)) {
        0 -> "SM"
        1 -> "MD"
        else -> "LG"
    }

    /**
     * Format an AutoTDP profile name concisely.
     *
     * "EFFICIENCY" → "EFF"
     * "BALANCED"   → "BAL"
     * "BATTERY_TARGET" → "TGT"
     */
    @JvmStatic
    fun formatAutoTdpProfileShort(profileName: String): String = when (profileName.uppercase()) {
        "EFFICIENCY"     -> "EFF"
        "BALANCED"       -> "BAL"
        "BATTERY_TARGET" -> "TGT"
        else -> profileName.take(3).uppercase()
    }

    /**
     * Format an AutoTDP savings value for the verbose panel.
     *
     * [savingsMw] null or [savingsReady] false → "measuring…"
     * Otherwise → "−1.8W (12%)" or "−1.8W" if pct is null.
     */
    @JvmStatic
    fun formatAutoTdpSavings(savingsMw: Int?, savingsPct: Double?, savingsReady: Boolean): String {
        if (!savingsReady || savingsMw == null) return "measuring…"
        val w = "%.1f".format(savingsMw / 1000.0)
        return if (savingsPct != null) "−${w}W (${savingsPct.toInt()}%)" else "−${w}W"
    }

    /**
     * Format a Hz value for the refresh-rate picker chip label.
     *
     * Example: 120.0f → "120Hz"
     */
    @JvmStatic
    fun formatHz(hz: Float): String = "${hz.toInt()}Hz"

    /**
     * Compute the opacity percentage string for display.
     * E.g. 0.94f → "94%"
     */
    @JvmStatic
    fun formatOpacityPct(opacity: Float): String = "${(opacity * 100).toInt()}%"
}
