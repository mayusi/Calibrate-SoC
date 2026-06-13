package io.github.mayusi.calibratesoc.data.monitor

/**
 * Result of evaluating one telemetry sample against the alert threshold.
 *
 * [tripped]     true when the hottest reading >= [thresholdC].
 * [hottestC]    the single highest temperature found across all relevant
 *               sensors, in °C (null when no temp is readable at all).
 * [sourceLabel] human-readable label of the hottest sensor ("cpu", "gpu",
 *               "battery", or the raw thermal-zone label).
 */
data class TempAlertEvaluation(
    val tripped: Boolean,
    val hottestC: Float?,
    val sourceLabel: String,
)

/**
 * Pure, stateless evaluator. Takes a single [Telemetry] snapshot and a
 * threshold in °C and returns whether the threshold is crossed plus the
 * hottest reading and its source.
 *
 * "Hottest relevant temp" = max over:
 *   - all thermal-zone temps whose label starts with "cpu" (case-insensitive)
 *   - all thermal-zone temps whose label starts with "gpu" or contains "kgsl"
 *     (matches OverlayService's GPU filter)
 *   - battery temp
 *
 * If no temp is readable (empty zones, null battery), tripped = false and
 * hottestC = null. No crash, no phantom alert.
 */
object TempAlertEvaluator {

    /**
     * @param telemetry the live sample to evaluate
     * @param thresholdC alert fires when hottestC >= this value (°C)
     */
    fun evaluate(telemetry: Telemetry, thresholdC: Int): TempAlertEvaluation {
        var hottest: Float? = null
        var hottestLabel = "unknown"

        // CPU zones
        for (zone in telemetry.zoneTempsMilliC) {
            if (zone.label.startsWith("cpu", ignoreCase = true)) {
                val c = zone.tempMilliC / 1000f
                if (hottest == null || c > hottest) {
                    hottest = c
                    hottestLabel = zone.label
                }
            }
        }

        // GPU zones (matches the label filter used in OverlayService)
        for (zone in telemetry.zoneTempsMilliC) {
            if (zone.label.contains("gpu", ignoreCase = true) ||
                zone.label.contains("kgsl", ignoreCase = true)
            ) {
                val c = zone.tempMilliC / 1000f
                if (hottest == null || c > hottest) {
                    hottest = c
                    hottestLabel = zone.label
                }
            }
        }

        // Battery temp
        telemetry.batteryTempDeciC?.let { deciC ->
            val c = deciC / 10f
            if (hottest == null || c > hottest) {
                hottest = c
                hottestLabel = "battery"
            }
        }

        val tripped = hottest != null && hottest >= thresholdC.toFloat()
        return TempAlertEvaluation(
            tripped = tripped,
            hottestC = hottest,
            sourceLabel = hottestLabel,
        )
    }
}
