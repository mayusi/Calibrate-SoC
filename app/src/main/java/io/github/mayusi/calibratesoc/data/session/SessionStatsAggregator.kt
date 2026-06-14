package io.github.mayusi.calibratesoc.data.session

/**
 * Pure Kotlin aggregation/analysis logic for recorded [GameSession]s.
 * No Android, Room, or Coroutine dependencies — fully unit-testable on the JVM.
 *
 * Two responsibilities:
 *   1. [detectThrottleEvents] — scans a single session's sample list and
 *      returns a list of [ThermalThrottleEvent]s: moments where the CPU
 *      temperature crossed a heuristic threshold AND FPS simultaneously
 *      dropped more than 10 % below the session's rolling average.
 *      Labelled honestly as a "heuristic" because no per-zone kernel
 *      throttle sysfs data is stored in [SessionSample].
 *
 *   2. [aggregateByApp] — groups a list of sessions by [GameSession.appLabel]
 *      and returns [AppSessionStats] per app: avg FPS, peak temp, avg watts,
 *      total playtime, and throttle-event count across all sessions for that
 *      app.
 */
object SessionStatsAggregator {

    // ──────────────────────────────────────────────────────────────────────
    // Thermal throttle event detection
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A detected moment where thermal conditions likely caused an FPS dip.
     *
     * Detection heuristic (honest label applied in UI):
     *   - The CPU temperature at this sample crossed the 90th-percentile of
     *     all CPU temperatures in the session (i.e. it is in the hottest
     *     10 % of samples).
     *   - At the same sample (within ±1 s), FPS dropped more than 10 % below
     *     the session rolling average (window = 30 s).
     *
     * The UI MUST label events from this function as "heuristic" — they are
     * a strong signal but NOT confirmed kernel throttle events.
     *
     * [elapsedMs] mirrors [SessionSample.elapsedMs] — seconds since session start.
     * [fpsBefore] is the rolling-average FPS immediately before the dip.
     * [fpsAtEvent] is the FPS at the event sample (may be null if unavailable).
     * [cpuTempC] is the CPU temperature at the event sample.
     */
    data class ThermalThrottleEvent(
        val elapsedMs: Long,
        val fpsBefore: Float,
        val fpsAtEvent: Float?,
        val cpuTempC: Float,
    )

    /**
     * Detect thermal throttle events in a single session.
     *
     * Returns an empty list (never null) when:
     *   - The session has fewer than [MIN_SAMPLES_FOR_ANALYSIS] samples.
     *   - FPS was not available during the session (no HUD active).
     *   - No sample crosses the 90th-percentile temp threshold AND has a
     *     simultaneous FPS dip.
     *
     * Rolling FPS average uses a backward window of [ROLLING_WINDOW_SAMPLES].
     */
    fun detectThrottleEvents(session: GameSession): List<ThermalThrottleEvent> {
        val samples = session.samples
        if (samples.size < MIN_SAMPLES_FOR_ANALYSIS) return emptyList()
        if (!session.fpsAvailableDuringSampling) return emptyList()

        // Compute 90th-percentile CPU temperature across all samples that have it.
        val cpuTemps = samples.mapNotNull { it.cpuTempC }.sorted()
        if (cpuTemps.isEmpty()) return emptyList()
        val p90TempIndex = ((cpuTemps.size - 1) * 0.90).toInt()
        val p90TempThreshold = cpuTemps[p90TempIndex]

        // Compute session avg FPS (used as fallback rolling anchor).
        val sessionFpsSamples = samples.mapNotNull { it.fps }
        if (sessionFpsSamples.isEmpty()) return emptyList()
        val sessionAvgFps = sessionFpsSamples.average().toFloat()

        val events = mutableListOf<ThermalThrottleEvent>()
        var inEvent = false

        for (i in samples.indices) {
            val s = samples[i]
            val currentTemp = s.cpuTempC ?: continue
            val currentFps = s.fps ?: continue

            // Rolling average: look back up to ROLLING_WINDOW_SAMPLES, or use session avg.
            val windowStart = maxOf(0, i - ROLLING_WINDOW_SAMPLES)
            val windowFps = samples.subList(windowStart, i)
                .mapNotNull { it.fps }
                .takeIf { it.isNotEmpty() }
                ?.average()?.toFloat() ?: sessionAvgFps

            val tempHot = currentTemp >= p90TempThreshold
            val fpsDipped = windowFps > 0f && currentFps < windowFps * FPS_DIP_FACTOR

            if (tempHot && fpsDipped) {
                if (!inEvent) {
                    events += ThermalThrottleEvent(
                        elapsedMs = s.elapsedMs,
                        fpsBefore = windowFps,
                        fpsAtEvent = currentFps,
                        cpuTempC = currentTemp,
                    )
                    inEvent = true
                }
            } else {
                inEvent = false
            }
        }

        return events
    }

    /**
     * Build a one-line plain-language summary of the throttle events detected
     * in a session. Returns null when there are no events (caller shows
     * "No throttle events detected" or similar).
     *
     * Example: "3 throttle events (heuristic); biggest FPS drop 58→41 at 7m 12s as CPU hit 84 °C."
     */
    fun buildThrottleSummary(events: List<ThermalThrottleEvent>): String? {
        if (events.isEmpty()) return null
        val worst = events.maxByOrNull { ev ->
            val after = ev.fpsAtEvent ?: ev.fpsBefore
            ev.fpsBefore - after
        } ?: return null
        val elapsedSec = worst.elapsedMs / 1_000L
        val m = elapsedSec / 60
        val s = elapsedSec % 60
        val timeStr = if (m > 0) "${m}m ${s}s" else "${s}s"
        val fpsBefore = "%.0f".format(worst.fpsBefore)
        val fpsAfter = worst.fpsAtEvent?.let { "%.0f".format(it) } ?: "?"
        val temp = "%.0f".format(worst.cpuTempC)
        return "${events.size} throttle event${if (events.size == 1) "" else "s"} (heuristic); " +
            "biggest FPS drop ${fpsBefore}→${fpsAfter} at $timeStr as CPU hit ${temp}°C."
    }

    // ──────────────────────────────────────────────────────────────────────
    // Per-app aggregation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Aggregated performance stats for one app across all its recorded sessions.
     *
     * Honesty rules:
     *   - [avgFps] is null when no session for this app had FPS available.
     *   - [peakCpuTempC] is null when no session had CPU temp data.
     *   - [avgWatts] is null when no session reported battery draw.
     *   - All nulls render as "—" in the UI (not 0, not fabricated).
     *   - [sessionCount] is always accurate (≥ 1 by construction from groupBy).
     */
    data class AppSessionStats(
        /** Human-readable app name (the [GameSession.appLabel]). */
        val appLabel: String,
        /** Number of sessions recorded for this app. */
        val sessionCount: Int,
        /** Total recorded playtime in milliseconds across all sessions. */
        val totalPlaytimeMs: Long,
        /** Average FPS across all sessions that had FPS data; null if none did. */
        val avgFps: Float?,
        /** Peak CPU temperature across all sessions; null if no session had temp data. */
        val peakCpuTempC: Float?,
        /** Peak GPU temperature across all sessions; null if no session had GPU temp data. */
        val peakGpuTempC: Float?,
        /** Weighted-average battery draw (W) across all sessions that reported it; null if none. */
        val avgWatts: Double?,
        /** Total throttle events (heuristic) across all sessions. */
        val totalThrottleEvents: Int,
        /** Total time (ms) spent in throttle events across all sessions. */
        val throttleTimeTotalMs: Long,
    )

    /**
     * Aggregate [sessions] grouped by [GameSession.appLabel].
     *
     * Sessions whose [GameSession.appLabel] is null are grouped under the
     * sentinel key [UNKNOWN_APP_LABEL] so they appear in the UI as
     * "Unknown app" rather than being silently dropped.
     *
     * Result is sorted by [AppSessionStats.totalPlaytimeMs] descending
     * (most-played apps first).
     */
    fun aggregateByApp(sessions: List<GameSession>): List<AppSessionStats> {
        if (sessions.isEmpty()) return emptyList()

        return sessions
            .groupBy { it.appLabel ?: UNKNOWN_APP_LABEL }
            .map { (label, appSessions) ->
                val totalPlaytimeMs = appSessions.sumOf { it.durationMs }

                // Avg FPS: only from sessions where FPS was captured.
                val fpsSessions = appSessions.filter { it.fpsAvailableDuringSampling }
                    .mapNotNull { it.summary.avgFps }
                val avgFps: Float? = if (fpsSessions.isEmpty()) null
                    else (fpsSessions.sum() / fpsSessions.size)

                val peakCpuTempC = appSessions
                    .mapNotNull { it.summary.peakCpuTempC }
                    .maxOrNull()

                val peakGpuTempC = appSessions
                    .mapNotNull { it.summary.peakGpuTempC }
                    .maxOrNull()

                // Weighted avg watts: weight each session's avgWatts by its durationMs.
                val wattsWithDuration = appSessions
                    .mapNotNull { s -> s.summary.avgWatts?.let { w -> Pair(w, s.durationMs) } }
                val avgWatts: Double? = if (wattsWithDuration.isEmpty()) null else {
                    val totalWeight = wattsWithDuration.sumOf { it.second.toDouble() }
                    if (totalWeight > 0)
                        wattsWithDuration.sumOf { (w, d) -> w * d } / totalWeight
                    else
                        wattsWithDuration.map { it.first }.average()
                }

                // Throttle events: sum from each session's summary fpsDipEvents
                // (a proxy for throttle events when thermal analysis isn't pre-computed).
                // The UI clarifies this is derived from the recorded dip count.
                val totalThrottleEvents = appSessions.sumOf { it.summary.fpsDipEvents }

                // Throttle time: we don't have direct per-event duration stored, so we
                // estimate: each fpsDipEvent ≈ 1 sample = 1 second at 1 Hz.
                // Honest label in UI: "approx."
                val throttleTimeTotalMs = totalThrottleEvents * 1_000L

                AppSessionStats(
                    appLabel = label,
                    sessionCount = appSessions.size,
                    totalPlaytimeMs = totalPlaytimeMs,
                    avgFps = avgFps,
                    peakCpuTempC = peakCpuTempC,
                    peakGpuTempC = peakGpuTempC,
                    avgWatts = avgWatts,
                    totalThrottleEvents = totalThrottleEvents,
                    throttleTimeTotalMs = throttleTimeTotalMs,
                )
            }
            .sortedByDescending { it.totalPlaytimeMs }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────

    /** Minimum samples in a session to attempt throttle analysis. */
    const val MIN_SAMPLES_FOR_ANALYSIS = 10

    /** Backward rolling window (in samples) for the FPS average used in event detection. */
    const val ROLLING_WINDOW_SAMPLES = 30

    /**
     * FPS must drop below this fraction of the rolling average to count as a dip.
     * 0.90 = "more than 10 % below average".
     */
    const val FPS_DIP_FACTOR = 0.90f

    /** Sentinel label for sessions with no detected foreground app. */
    const val UNKNOWN_APP_LABEL = "Unknown app"
}
