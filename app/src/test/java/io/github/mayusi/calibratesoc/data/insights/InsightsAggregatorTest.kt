package io.github.mayusi.calibratesoc.data.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [InsightsAggregator].
 *
 * Covers:
 *   - Empty input → insufficientDataReason + null rollups
 *   - batterySavedThisWeekMwh: sum of autoTdpSavedMwh from weekReports
 *   - batterySavedThisWeekMwh: null when no report has savings data
 *   - tempTrendCPerSession: slope from ordered session peak temps
 *   - tempTrendCPerSession: null when fewer than 2 sessions have temp data
 *   - bestProfilePerApp: selects highest avg fps profile
 *   - bestProfilePerApp: uses throttle count as tiebreaker when fps equal
 *   - bestProfilePerApp: null fps entries rank last
 *   - bestProfilePerApp: skips pairs with fewer than MIN_SESSIONS_FOR_PROFILE_RANK sessions
 *   - bestProfilePerApp: empty when no reports have both app and profile labels
 *   - insufficientDataReason: set for single session, cleared for richer data
 */
class InsightsAggregatorTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun report(
        sessionId: Long = 1L,
        startedAtMs: Long = sessionId * 10_000L,
        durationMs: Long = 60_000L,
        appLabel: String? = "TestGame",
        profileName: String? = "Performance",
        avgFps: Float? = 60f,
        peakCpuTempC: Float? = 65f,
        peakGpuTempC: Float? = null,
        avgPowerW: Double? = 5.0,
        autoTdpSavedMwh: Double? = null,
        throttleEventCount: Int = 0,
    ) = SessionReport(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        appLabel = appLabel,
        packageName = null,
        profileName = profileName,
        avgFps = avgFps,
        peakFps = avgFps?.let { it + 5f },
        p1LowFps = null,
        peakCpuTempC = peakCpuTempC,
        peakGpuTempC = peakGpuTempC,
        avgPowerW = avgPowerW,
        energyMwh = null,
        autoTdpSavedMwh = autoTdpSavedMwh,
        throttleEventCount = throttleEventCount,
        verdict = "Test verdict.",
    )

    // ── Empty input ──────────────────────────────────────────────────────────

    @Test
    fun `empty allReports returns insufficientDataReason and null rollups`() {
        val summary = InsightsAggregator.compute(emptyList(), emptyList())
        assertThat(summary.batterySavedThisWeekMwh).isNull()
        assertThat(summary.tempTrendCPerSession).isNull()
        assertThat(summary.bestProfilePerApp).isEmpty()
        assertThat(summary.insufficientDataReason).isNotNull()
    }

    // ── Battery saved this week ──────────────────────────────────────────────

    @Test
    fun `batterySavedThisWeekMwh is sum of autoTdpSavedMwh from weekReports`() {
        val r1 = report(sessionId = 1L, autoTdpSavedMwh = 30.0)
        val r2 = report(sessionId = 2L, autoTdpSavedMwh = 20.0)
        val r3 = report(sessionId = 3L, autoTdpSavedMwh = 10.0)
        val summary = InsightsAggregator.compute(
            allReports = listOf(r1, r2, r3),
            weekReports = listOf(r1, r2, r3),
        )
        assertThat(summary.batterySavedThisWeekMwh!!).isWithin(0.1).of(60.0)
    }

    @Test
    fun `batterySavedThisWeekMwh only sums weekReports not allReports`() {
        val r1 = report(sessionId = 1L, autoTdpSavedMwh = 100.0)
        val r2 = report(sessionId = 2L, autoTdpSavedMwh = 50.0)
        val summary = InsightsAggregator.compute(
            allReports = listOf(r1, r2),
            weekReports = listOf(r2), // only r2 is in the week window
        )
        assertThat(summary.batterySavedThisWeekMwh!!).isWithin(0.1).of(50.0)
    }

    @Test
    fun `batterySavedThisWeekMwh is null when no weekReport has savings data`() {
        val r1 = report(sessionId = 1L, autoTdpSavedMwh = null)
        val r2 = report(sessionId = 2L, autoTdpSavedMwh = null)
        val summary = InsightsAggregator.compute(
            allReports = listOf(r1, r2),
            weekReports = listOf(r1, r2),
        )
        assertThat(summary.batterySavedThisWeekMwh).isNull()
    }

    @Test
    fun `batterySavedThisWeekMwh ignores null savings entries in sum`() {
        val r1 = report(sessionId = 1L, autoTdpSavedMwh = 40.0)
        val r2 = report(sessionId = 2L, autoTdpSavedMwh = null) // no savings this session
        val r3 = report(sessionId = 3L, autoTdpSavedMwh = 20.0)
        val summary = InsightsAggregator.compute(
            allReports = listOf(r1, r2, r3),
            weekReports = listOf(r1, r2, r3),
        )
        assertThat(summary.batterySavedThisWeekMwh!!).isWithin(0.1).of(60.0)
    }

    // ── Temp trend ───────────────────────────────────────────────────────────

    @Test
    fun `tempTrend is null when fewer than MIN_SESSIONS_FOR_TREND sessions have temp data`() {
        val r1 = report(sessionId = 1L, peakCpuTempC = 70f)
        val r2 = report(sessionId = 2L, peakCpuTempC = null) // no temp
        val summary = InsightsAggregator.compute(listOf(r1, r2), emptyList())
        assertThat(summary.tempTrendCPerSession).isNull()
    }

    @Test
    fun `tempTrend is null when all reports have no temp data`() {
        val r1 = report(sessionId = 1L, peakCpuTempC = null)
        val r2 = report(sessionId = 2L, peakCpuTempC = null)
        val r3 = report(sessionId = 3L, peakCpuTempC = null)
        val summary = InsightsAggregator.compute(listOf(r1, r2, r3), emptyList())
        assertThat(summary.tempTrendCPerSession).isNull()
    }

    @Test
    fun `tempTrend is positive when temps are increasing over sessions`() {
        // Sessions ordered by startedAtMs: 60, 65, 70 → slope = +5 °C per session
        val reports = listOf(
            report(sessionId = 1L, startedAtMs = 1_000L, peakCpuTempC = 60f),
            report(sessionId = 2L, startedAtMs = 2_000L, peakCpuTempC = 65f),
            report(sessionId = 3L, startedAtMs = 3_000L, peakCpuTempC = 70f),
        )
        val summary = InsightsAggregator.compute(reports, emptyList())
        assertThat(summary.tempTrendCPerSession!!).isWithin(0.1).of(5.0)
    }

    @Test
    fun `tempTrend is negative when temps are decreasing over sessions`() {
        val reports = listOf(
            report(sessionId = 1L, startedAtMs = 1_000L, peakCpuTempC = 80f),
            report(sessionId = 2L, startedAtMs = 2_000L, peakCpuTempC = 75f),
            report(sessionId = 3L, startedAtMs = 3_000L, peakCpuTempC = 70f),
        )
        val summary = InsightsAggregator.compute(reports, emptyList())
        assertThat(summary.tempTrendCPerSession!!).isWithin(0.1).of(-5.0)
    }

    @Test
    fun `tempTrend is zero for flat temps`() {
        val reports = listOf(
            report(sessionId = 1L, startedAtMs = 1_000L, peakCpuTempC = 70f),
            report(sessionId = 2L, startedAtMs = 2_000L, peakCpuTempC = 70f),
            report(sessionId = 3L, startedAtMs = 3_000L, peakCpuTempC = 70f),
        )
        val summary = InsightsAggregator.compute(reports, emptyList())
        assertThat(summary.tempTrendCPerSession!!).isWithin(0.01).of(0.0)
    }

    @Test
    fun `tempTrend is computed only from sessions that have temp data`() {
        // Mix: sessions 1, 3 have temp (60 → 70 = +10, slope +10).
        // Session 2 has no temp and should be skipped.
        val reports = listOf(
            report(sessionId = 1L, startedAtMs = 1_000L, peakCpuTempC = 60f),
            report(sessionId = 2L, startedAtMs = 2_000L, peakCpuTempC = null),
            report(sessionId = 3L, startedAtMs = 3_000L, peakCpuTempC = 70f),
        )
        val trend = InsightsAggregator.computeTempTrend(reports)
        assertThat(trend!!).isWithin(0.1).of(10.0)
    }

    // ── Best profile per app ─────────────────────────────────────────────────

    @Test
    fun `bestProfilePerApp is empty when no reports have both app and profile label`() {
        val r1 = report(sessionId = 1L, appLabel = null, profileName = "Performance")
        val r2 = report(sessionId = 2L, appLabel = "Game", profileName = null)
        val result = InsightsAggregator.computeBestProfilePerApp(listOf(r1, r2))
        assertThat(result).isEmpty()
    }

    @Test
    fun `bestProfilePerApp skips pairs with fewer than MIN_SESSIONS_FOR_PROFILE_RANK`() {
        // Only 1 session for ("Game", "Performance") — below minimum of 2.
        val r1 = report(sessionId = 1L, appLabel = "Game", profileName = "Performance", avgFps = 60f)
        val result = InsightsAggregator.computeBestProfilePerApp(listOf(r1))
        assertThat(result).isEmpty()
    }

    @Test
    fun `bestProfilePerApp selects profile with highest avgFps`() {
        // "Performance" profile: 2 sessions at 60 fps avg.
        // "Balanced" profile: 2 sessions at 45 fps avg.
        val reports = listOf(
            report(sessionId = 1L, appLabel = "Game", profileName = "Performance", avgFps = 60f),
            report(sessionId = 2L, appLabel = "Game", profileName = "Performance", avgFps = 60f),
            report(sessionId = 3L, appLabel = "Game", profileName = "Balanced", avgFps = 45f),
            report(sessionId = 4L, appLabel = "Game", profileName = "Balanced", avgFps = 45f),
        )
        val result = InsightsAggregator.computeBestProfilePerApp(reports)
        assertThat(result).containsKey("Game")
        assertThat(result["Game"]!!.profileName).isEqualTo("Performance")
    }

    @Test
    fun `bestProfilePerApp uses throttle count as tiebreaker when fps equal`() {
        // Both profiles have 60 fps avg; "LowThrottle" has 0 events vs "HighThrottle" at 3.
        val reports = listOf(
            report(sessionId = 1L, appLabel = "Game", profileName = "LowThrottle",
                avgFps = 60f, throttleEventCount = 0),
            report(sessionId = 2L, appLabel = "Game", profileName = "LowThrottle",
                avgFps = 60f, throttleEventCount = 0),
            report(sessionId = 3L, appLabel = "Game", profileName = "HighThrottle",
                avgFps = 60f, throttleEventCount = 3),
            report(sessionId = 4L, appLabel = "Game", profileName = "HighThrottle",
                avgFps = 60f, throttleEventCount = 3),
        )
        val result = InsightsAggregator.computeBestProfilePerApp(reports)
        assertThat(result["Game"]!!.profileName).isEqualTo("LowThrottle")
    }

    @Test
    fun `bestProfilePerApp null fps entries rank last`() {
        // "NoFps" profile has no fps data. "WithFps" has 50 fps avg. WithFps should win.
        val reports = listOf(
            report(sessionId = 1L, appLabel = "Game", profileName = "WithFps", avgFps = 50f),
            report(sessionId = 2L, appLabel = "Game", profileName = "WithFps", avgFps = 50f),
            report(sessionId = 3L, appLabel = "Game", profileName = "NoFps", avgFps = null),
            report(sessionId = 4L, appLabel = "Game", profileName = "NoFps", avgFps = null),
        )
        val result = InsightsAggregator.computeBestProfilePerApp(reports)
        assertThat(result["Game"]!!.profileName).isEqualTo("WithFps")
    }

    @Test
    fun `bestProfilePerApp works independently per app`() {
        val reports = listOf(
            // Game A: Profile X wins (higher fps)
            report(sessionId = 1L, appLabel = "GameA", profileName = "X", avgFps = 60f),
            report(sessionId = 2L, appLabel = "GameA", profileName = "X", avgFps = 60f),
            report(sessionId = 3L, appLabel = "GameA", profileName = "Y", avgFps = 30f),
            report(sessionId = 4L, appLabel = "GameA", profileName = "Y", avgFps = 30f),
            // Game B: Profile Y wins (higher fps)
            report(sessionId = 5L, appLabel = "GameB", profileName = "X", avgFps = 45f),
            report(sessionId = 6L, appLabel = "GameB", profileName = "X", avgFps = 45f),
            report(sessionId = 7L, appLabel = "GameB", profileName = "Y", avgFps = 55f),
            report(sessionId = 8L, appLabel = "GameB", profileName = "Y", avgFps = 55f),
        )
        val result = InsightsAggregator.computeBestProfilePerApp(reports)
        assertThat(result["GameA"]!!.profileName).isEqualTo("X")
        assertThat(result["GameB"]!!.profileName).isEqualTo("Y")
    }

    // ── insufficientDataReason ───────────────────────────────────────────────

    @Test
    fun `insufficientDataReason is set for a single session`() {
        val r = report(sessionId = 1L)
        val summary = InsightsAggregator.compute(listOf(r), emptyList())
        assertThat(summary.insufficientDataReason).isNotNull()
    }

    @Test
    fun `insufficientDataReason is null when there is enough data`() {
        // 3 sessions with FPS, temp, and savings — should produce rollups and clear the note.
        val reports = (1L..3L).map { id ->
            report(
                sessionId = id,
                startedAtMs = id * 10_000L,
                autoTdpSavedMwh = 20.0,
                appLabel = "Game",
                profileName = "Perf",
                avgFps = 60f,
                peakCpuTempC = 65f,
            )
        }
        val summary = InsightsAggregator.compute(reports, weekReports = reports)
        assertThat(summary.batterySavedThisWeekMwh).isNotNull()
        // With 3 sessions and savings data the reason should be cleared or irrelevant.
        // The reason is only set when data is genuinely insufficient — 3 sessions with
        // full data should clear it.
        assertThat(summary.insufficientDataReason).isNull()
    }
}
