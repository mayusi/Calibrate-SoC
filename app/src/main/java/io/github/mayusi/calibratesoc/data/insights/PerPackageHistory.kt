package io.github.mayusi.calibratesoc.data.insights

/**
 * Cross-session aggregate for a single game package.
 *
 * All trend lists are sorted oldest→newest (ascending [SessionReport.startedAtMs]).
 * Each element in a trend list corresponds to one session; sessions missing the
 * relevant data are skipped from that specific list (rather than inserting a null
 * or zero, which would be dishonest).
 *
 * [savedPercent] and [savingsBasis] follow strict honesty rules:
 *  - savedPercent is ONLY computed from sessions that have BOTH
 *    [SessionReport.autoTdpSavedMwh] (non-null) AND [SessionReport.energyMwh]
 *    (non-null). Sessions missing either field are completely excluded from the
 *    savings calculation.
 *  - When no qualifying sessions exist, savedPercent is null and savingsBasis
 *    says so explicitly — never "0%" or a made-up number.
 *  - savedFps is ONLY set when there are ≥2 distinct profiles for this package
 *    with avgFps data; it is the spread (best − worst), not a fabricated gain.
 */
data class PerPackageHistory(
    val packageName: String,
    val appLabel: String?,
    /** Total number of recorded sessions for this package. */
    val sessionCount: Int,
    /**
     * Per-session avgFps, oldest→newest, skipping sessions with null avgFps.
     * May be shorter than sessionCount.
     */
    val avgFpsTrend: List<Float>,
    /**
     * Per-session peakCpuTempC, oldest→newest, skipping sessions with null peakCpuTempC.
     * May be shorter than sessionCount.
     */
    val peakTempTrend: List<Float>,
    /**
     * Per-session battery draw in mWh/hour (= effective milliwatt average draw),
     * oldest→newest, skipping sessions missing either energyMwh or durationMs≤0.
     * Computed as: energyMwh / (durationMs / 3_600_000.0).
     */
    val batteryPerHourTrend: List<Float>,
    /**
     * Percentage of energy saved by AutoTDP across all qualifying sessions.
     * Formula: saved / (saved + consumed) * 100, where:
     *   saved    = sum of autoTdpSavedMwh across sessions where both fields are present
     *   consumed = sum of energyMwh across those same sessions
     * Null when no session has both autoTdpSavedMwh and energyMwh populated.
     * MEASURED only — never fabricated.
     */
    val savedPercent: Double?,
    /**
     * Measured profile fps spread across distinct profiles for this package.
     * E.g. "+15 fps vs your other tune" — set only when ≥2 profiles with fps data exist.
     * Null when insufficient profile diversity exists for a comparison.
     * NEVER fabricated.
     */
    val savedFps: Float?,
    /**
     * Plain-English explanation of where [savedPercent] came from, or an honest
     * statement when data is insufficient. Examples:
     *   "Measured from 3 sessions where AutoTDP was active."
     *   "No AutoTDP savings measured for this game yet."
     */
    val savingsBasis: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// Pure computation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compute a [PerPackageHistory] for [packageName] from the given list of reports.
 *
 * Returns null when there are no sessions at all for [packageName].
 * Returns a valid (honest) object even for a single session — but trends with
 * a single point will not display as a chart (MetricLineChart requires ≥2 points).
 */
fun computePerPackageHistory(
    reports: List<SessionReport>,
    packageName: String,
): PerPackageHistory? {
    val forPackage = reports
        .filter { it.packageName == packageName }
        .sortedBy { it.startedAtMs }

    if (forPackage.isEmpty()) return null

    // Carry the most-recent non-null appLabel for display.
    val appLabel = forPackage.lastOrNull { it.appLabel != null }?.appLabel

    // ── Trend series ─────────────────────────────────────────────────────────
    val avgFpsTrend = forPackage.mapNotNull { it.avgFps }
    val peakTempTrend = forPackage.mapNotNull { it.peakCpuTempC }
    val batteryPerHourTrend = forPackage.mapNotNull { r ->
        val energyMwh = r.energyMwh ?: return@mapNotNull null
        val durationMs = r.durationMs
        if (durationMs <= 0L) return@mapNotNull null
        val hours = durationMs / 3_600_000.0
        (energyMwh / hours).toFloat()
    }

    // ── Savings headline ─────────────────────────────────────────────────────
    // Only from sessions that have BOTH autoTdpSavedMwh AND energyMwh.
    val savingsQualified = forPackage.filter {
        it.autoTdpSavedMwh != null && it.energyMwh != null
    }

    val savedPercent: Double?
    val savingsBasis: String
    if (savingsQualified.isEmpty()) {
        savedPercent = null
        savingsBasis = "No AutoTDP savings measured for this game yet."
    } else {
        val totalSaved = savingsQualified.sumOf { it.autoTdpSavedMwh!! }
        val totalConsumed = savingsQualified.sumOf { it.energyMwh!! }
        val baseline = totalSaved + totalConsumed
        savedPercent = if (baseline > 0.0) (totalSaved / baseline) * 100.0 else null
        savingsBasis = "Measured from ${savingsQualified.size} session${if (savingsQualified.size == 1) "" else "s"} where AutoTDP was active."
    }

    // ── Profile fps spread ───────────────────────────────────────────────────
    // Only when ≥2 distinct profiles with avgFps exist for this package.
    val savedFps: Float? = run {
        val profileGroups = forPackage
            .filter { it.profileName != null && it.avgFps != null }
            .groupBy { it.profileName!! }
        if (profileGroups.size < 2) return@run null
        val avgFpsPerProfile = profileGroups.mapValues { (_, sessions) ->
            sessions.mapNotNull { it.avgFps }.average().toFloat()
        }
        val best = avgFpsPerProfile.values.maxOrNull() ?: return@run null
        val worst = avgFpsPerProfile.values.minOrNull() ?: return@run null
        best - worst
    }

    return PerPackageHistory(
        packageName = packageName,
        appLabel = appLabel,
        sessionCount = forPackage.size,
        avgFpsTrend = avgFpsTrend,
        peakTempTrend = peakTempTrend,
        batteryPerHourTrend = batteryPerHourTrend,
        savedPercent = savedPercent,
        savedFps = savedFps,
        savingsBasis = savingsBasis,
    )
}
