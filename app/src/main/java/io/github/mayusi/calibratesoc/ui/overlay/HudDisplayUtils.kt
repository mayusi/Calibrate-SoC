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
}
