package io.github.mayusi.calibratesoc.data.insights

import io.github.mayusi.calibratesoc.data.session.GameSession

/**
 * Pure cross-session rollup logic. No Android, no I/O, no coroutines.
 *
 * Takes a list of [SessionReport]s (already built by [SessionReportBuilder])
 * and a list of the underlying [GameSession]s (used for duration-weighting)
 * and produces an [InsightsSummary].
 *
 * Honesty rules:
 *   - "Battery saved this week" is the sum of [SessionReport.autoTdpSavedMwh]
 *     across sessions that had a valid AutoTDP measurement. Null when no
 *     session this week had an enoughData savings result.
 *   - "Best profile per game" requires ≥ [MIN_SESSIONS_FOR_PROFILE_RANK] sessions
 *     for the same (appLabel, profileName) pair.
 *   - Temp trend requires ≥ 2 sessions with CPU temp data.
 *   - All nulls are surfaced honestly; callers must not replace them with 0.
 */
object InsightsAggregator {

    /** Minimum sessions for a (app, profile) pair before we claim it is "best". */
    const val MIN_SESSIONS_FOR_PROFILE_RANK = 2

    /** Minimum sessions with temp data to compute a trend. */
    const val MIN_SESSIONS_FOR_TREND = 2

    /**
     * Rollup of cross-session insights.
     *
     * [batterySavedThisWeekMwh] — sum of AutoTDP-saved energy (mWh) across
     *   all reports in the [weekReports] window. Null when no session had a
     *   valid AutoTDP savings measurement.
     *
     * [tempTrendCPerSession] — slope of peak CPU temp across sessions ordered
     *   by [startedAtMs], in °C per session. Positive = getting hotter over
     *   time; negative = cooling trend. Null when fewer than [MIN_SESSIONS_FOR_TREND]
     *   sessions have CPU temp data.
     *
     * [bestProfilePerApp] — for each app (by label), the profile name that
     *   produced the highest sustained average FPS AND the lowest throttle-event
     *   rate across ≥ [MIN_SESSIONS_FOR_PROFILE_RANK] sessions. Empty map when
     *   no app has enough data.
     *
     * [insufficientDataReason] — set to a human-readable string when the
     *   entire input has too little data to produce any meaningful rollup
     *   (e.g. "Only 1 session recorded — play more to see trends.").
     *   Null when at least some rollup was produced.
     */
    data class InsightsSummary(
        val batterySavedThisWeekMwh: Double?,
        val tempTrendCPerSession: Double?,
        val bestProfilePerApp: Map<String, BestProfileEntry>,
        val insufficientDataReason: String?,
    )

    /**
     * The profile that produced the best measured performance for one app.
     *
     * [profileName] is the profile/preset name.
     * [avgFps] is the mean FPS across sessions where this profile was active
     *   and FPS was available. Null when none of those sessions had FPS data.
     * [avgThrottleEventsPerSession] is the mean throttle count — lower is better.
     * [sessionCount] is how many sessions contributed to this entry.
     */
    data class BestProfileEntry(
        val appLabel: String,
        val profileName: String,
        val avgFps: Float?,
        val avgThrottleEventsPerSession: Double,
        val sessionCount: Int,
    )

    /**
     * Compute rollup insights from a list of [SessionReport]s.
     *
     * [allReports]  — all stored [SessionReport]s, in any order.
     * [weekReports] — reports from the current calendar week (caller filters by
     *   [SessionReport.startedAtMs]). May be a subset of [allReports].
     */
    fun compute(
        allReports: List<SessionReport>,
        weekReports: List<SessionReport>,
    ): InsightsSummary {
        if (allReports.isEmpty()) {
            return InsightsSummary(
                batterySavedThisWeekMwh = null,
                tempTrendCPerSession = null,
                bestProfilePerApp = emptyMap(),
                insufficientDataReason = "No sessions recorded yet — start a session to see insights.",
            )
        }

        // ── Battery saved this week ─────────────────────────────────────────
        val savedValues = weekReports.mapNotNull { it.autoTdpSavedMwh }
        val batterySavedThisWeekMwh: Double? = if (savedValues.isEmpty()) null
            else savedValues.sum()

        // ── Temp trend (simple first-difference slope) ──────────────────────
        val tempTrendCPerSession: Double? = computeTempTrend(allReports)

        // ── Best profile per app ────────────────────────────────────────────
        val bestProfilePerApp: Map<String, BestProfileEntry> =
            computeBestProfilePerApp(allReports)

        // ── Insufficiency note ─────────────────────────────────────────────
        val insufficientDataReason: String? = when {
            allReports.size == 1 ->
                "Only 1 session recorded — play more to see trends."
            batterySavedThisWeekMwh == null && bestProfilePerApp.isEmpty() && tempTrendCPerSession == null ->
                "Not enough data yet to compute rollup insights — record more sessions with the HUD active."
            else -> null
        }

        return InsightsSummary(
            batterySavedThisWeekMwh = batterySavedThisWeekMwh,
            tempTrendCPerSession = tempTrendCPerSession,
            bestProfilePerApp = bestProfilePerApp,
            insufficientDataReason = insufficientDataReason,
        )
    }

    /**
     * Compute the slope of peak CPU temp over sessions sorted by [startedAtMs].
     * Uses a simple mean of consecutive first-differences (°C per session step).
     * Returns null when fewer than [MIN_SESSIONS_FOR_TREND] sessions have temp data.
     */
    internal fun computeTempTrend(reports: List<SessionReport>): Double? {
        val tempSeries = reports
            .filter { it.peakCpuTempC != null }
            .sortedBy { it.startedAtMs }
            .map { it.peakCpuTempC!! }

        if (tempSeries.size < MIN_SESSIONS_FOR_TREND) return null

        // Mean of consecutive differences: positive → warming trend.
        val diffs = (1 until tempSeries.size).map { i ->
            tempSeries[i] - tempSeries[i - 1]
        }
        return diffs.sum().toDouble() / diffs.size
    }

    /**
     * For each app, find which profile delivered the highest sustained average
     * FPS (primary) and lowest throttle-events-per-session (tiebreaker).
     * A (app, profile) pair must have ≥ [MIN_SESSIONS_FOR_PROFILE_RANK] sessions
     * before it qualifies for ranking.
     *
     * Apps with no qualifying (app, profile) pair are omitted from the result.
     */
    internal fun computeBestProfilePerApp(
        reports: List<SessionReport>,
    ): Map<String, BestProfileEntry> {
        // Only consider reports that have both an app label and a profile name.
        val eligible = reports.filter { it.appLabel != null && it.profileName != null }
        if (eligible.isEmpty()) return emptyMap()

        // Group by (appLabel, profileName).
        val grouped: Map<Pair<String, String>, List<SessionReport>> = eligible
            .groupBy { Pair(it.appLabel!!, it.profileName!!) }

        // Filter pairs with enough sessions.
        val qualified = grouped.filter { it.value.size >= MIN_SESSIONS_FOR_PROFILE_RANK }
        if (qualified.isEmpty()) return emptyMap()

        // Build a candidate entry per (app, profile) pair.
        val candidates: List<BestProfileEntry> = qualified.map { (key, sessions) ->
            val (appLabel, profileName) = key

            val fpsSessions = sessions.filter { it.avgFps != null }
            val avgFps: Float? = if (fpsSessions.isEmpty()) null
                else fpsSessions.sumOf { it.avgFps!!.toDouble() }.toFloat() / fpsSessions.size

            val avgThrottlePerSession = sessions.sumOf { it.throttleEventCount }.toDouble() /
                sessions.size

            BestProfileEntry(
                appLabel = appLabel,
                profileName = profileName,
                avgFps = avgFps,
                avgThrottleEventsPerSession = avgThrottlePerSession,
                sessionCount = sessions.size,
            )
        }

        // For each app, pick the best profile: highest avgFps (null fps entries rank last),
        // then lowest throttle count as tiebreaker.
        val byApp: Map<String, BestProfileEntry> = candidates
            .groupBy { it.appLabel }
            .mapValues { (_, entries) ->
                entries.maxWithOrNull(profileComparator)!!
            }

        return byApp
    }

    /**
     * Comparator: higher avgFps wins (null FPS ranks last), then lower throttle rate.
     */
    private val profileComparator: Comparator<BestProfileEntry> = Comparator { a, b ->
        val aFps = a.avgFps
        val bFps = b.avgFps
        when {
            aFps != null && bFps != null -> {
                val fpsDiff = aFps.compareTo(bFps)
                if (fpsDiff != 0) fpsDiff
                else b.avgThrottleEventsPerSession.compareTo(a.avgThrottleEventsPerSession)
            }
            aFps != null -> 1   // a wins: has FPS data, b doesn't
            bFps != null -> -1  // b wins
            // Both null: prefer lower throttle rate
            else -> b.avgThrottleEventsPerSession.compareTo(a.avgThrottleEventsPerSession)
        }
    }
}
