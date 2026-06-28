package io.github.mayusi.calibratesoc.data.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [computePerPackageHistory].
 *
 * Covers:
 *   1. Returns null when no sessions exist for the package.
 *   2. Filters by packageName — other packages excluded.
 *   3. Trend lists are sorted oldest→newest (ascending startedAtMs).
 *   4. savedPercent computed only from sessions with BOTH autoTdpSavedMwh AND energyMwh.
 *   5. savedPercent is null (with honest basis) when no qualifying sessions.
 *   6. batteryPerHour = energyMwh / (durationMs / 3_600_000.0).
 *   7. savedFps is null when fewer than 2 distinct profiles with fps data.
 *   8. savedFps = best avgFps − worst avgFps across distinct profiles.
 *   9. appLabel carries most-recent non-null label.
 *  10. Single session returns a valid object (not null) with sessionCount=1.
 */
class PerPackageHistoryTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun report(
        sessionId: Long,
        startedAtMs: Long = sessionId * 10_000L,
        packageName: String? = "com.game",
        appLabel: String? = "My Game",
        profileName: String? = "Perf",
        avgFps: Float? = 60f,
        peakCpuTempC: Float? = 65f,
        energyMwh: Double? = null,
        autoTdpSavedMwh: Double? = null,
        durationMs: Long = 3_600_000L, // 1 hour default
    ) = SessionReport(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        appLabel = appLabel,
        packageName = packageName,
        profileName = profileName,
        avgFps = avgFps,
        peakFps = avgFps?.let { it + 5f },
        p1LowFps = null,
        peakCpuTempC = peakCpuTempC,
        peakGpuTempC = null,
        avgPowerW = 5.0,
        energyMwh = energyMwh,
        autoTdpSavedMwh = autoTdpSavedMwh,
        throttleEventCount = 0,
        verdict = "Test verdict.",
    )

    // ── 1. Returns null for unknown package ───────────────────────────────────

    @Test
    fun `returns null when no sessions exist for the package`() {
        val reports = listOf(report(1L, packageName = "com.other"))
        val result = computePerPackageHistory(reports, "com.game")
        assertThat(result).isNull()
    }

    @Test
    fun `returns null for empty report list`() {
        val result = computePerPackageHistory(emptyList(), "com.game")
        assertThat(result).isNull()
    }

    // ── 2. Filters by packageName ────────────────────────────────────────────

    @Test
    fun `only includes sessions for the requested package`() {
        val reports = listOf(
            report(1L, packageName = "com.game", avgFps = 60f),
            report(2L, packageName = "com.other", avgFps = 30f),
            report(3L, packageName = "com.game", avgFps = 60f),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.sessionCount).isEqualTo(2)
        // avgFpsTrend should only have 2 entries (not 3)
        assertThat(result.avgFpsTrend).hasSize(2)
        result.avgFpsTrend.forEach { assertThat(it).isWithin(0.1f).of(60f) }
    }

    // ── 3. Sorted oldest→newest ───────────────────────────────────────────────

    @Test
    fun `avgFpsTrend is sorted oldest to newest`() {
        val reports = listOf(
            report(3L, startedAtMs = 3_000L, packageName = "com.game", avgFps = 70f),
            report(1L, startedAtMs = 1_000L, packageName = "com.game", avgFps = 50f),
            report(2L, startedAtMs = 2_000L, packageName = "com.game", avgFps = 60f),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.avgFpsTrend).hasSize(3)
        assertThat(result.avgFpsTrend[0]).isWithin(0.1f).of(50f) // oldest
        assertThat(result.avgFpsTrend[1]).isWithin(0.1f).of(60f)
        assertThat(result.avgFpsTrend[2]).isWithin(0.1f).of(70f) // newest
    }

    @Test
    fun `peakTempTrend is sorted oldest to newest`() {
        val reports = listOf(
            report(2L, startedAtMs = 2_000L, packageName = "com.game", peakCpuTempC = 75f),
            report(1L, startedAtMs = 1_000L, packageName = "com.game", peakCpuTempC = 65f),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.peakTempTrend[0]).isWithin(0.1f).of(65f) // oldest
        assertThat(result.peakTempTrend[1]).isWithin(0.1f).of(75f) // newest
    }

    // ── 4. savedPercent only from sessions with both fields ───────────────────

    @Test
    fun `savedPercent uses only sessions with both autoTdpSavedMwh and energyMwh`() {
        val reports = listOf(
            // Session 1: has both — qualifies. saved=100, energy=400 → 20%
            report(1L, packageName = "com.game",
                autoTdpSavedMwh = 100.0, energyMwh = 400.0),
            // Session 2: missing energyMwh — excluded from savings calc
            report(2L, packageName = "com.game",
                autoTdpSavedMwh = 999.0, energyMwh = null),
            // Session 3: missing autoTdpSavedMwh — excluded
            report(3L, packageName = "com.game",
                autoTdpSavedMwh = null, energyMwh = 500.0),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        // Only session 1 qualifies: 100 / (100 + 400) * 100 = 20%
        assertThat(result.savedPercent).isNotNull()
        assertThat(result.savedPercent!!).isWithin(0.1).of(20.0)
        assertThat(result.savingsBasis).contains("1 session")
    }

    @Test
    fun `savedPercent aggregates across multiple qualifying sessions`() {
        val reports = listOf(
            report(1L, packageName = "com.game",
                autoTdpSavedMwh = 100.0, energyMwh = 400.0),
            report(2L, packageName = "com.game",
                autoTdpSavedMwh = 100.0, energyMwh = 400.0),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        // total saved=200, total energy=800, baseline=1000 → 20%
        assertThat(result.savedPercent!!).isWithin(0.1).of(20.0)
        assertThat(result.savingsBasis).contains("2 sessions")
    }

    // ── 5. savedPercent null when no qualifying sessions ──────────────────────

    @Test
    fun `savedPercent is null when no session has both fields`() {
        val reports = listOf(
            report(1L, packageName = "com.game",
                autoTdpSavedMwh = null, energyMwh = null),
            report(2L, packageName = "com.game",
                autoTdpSavedMwh = 100.0, energyMwh = null),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.savedPercent).isNull()
        assertThat(result.savingsBasis).contains("No AutoTDP savings measured")
    }

    // ── 6. batteryPerHour = energyMwh / hours ────────────────────────────────

    @Test
    fun `batteryPerHourTrend is energyMwh divided by duration in hours`() {
        // 1 hour = 3_600_000 ms; energyMwh=500 → 500 mWh/hr
        val reports = listOf(
            report(1L, packageName = "com.game",
                energyMwh = 500.0, durationMs = 3_600_000L),
            report(2L, packageName = "com.game",
                energyMwh = 1000.0, durationMs = 7_200_000L), // 2 hours → 500 mWh/hr
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.batteryPerHourTrend).hasSize(2)
        assertThat(result.batteryPerHourTrend[0]).isWithin(1f).of(500f)
        assertThat(result.batteryPerHourTrend[1]).isWithin(1f).of(500f)
    }

    @Test
    fun `batteryPerHourTrend excludes sessions with null energyMwh`() {
        val reports = listOf(
            report(1L, packageName = "com.game", energyMwh = null),
            report(2L, packageName = "com.game", energyMwh = 600.0, durationMs = 3_600_000L),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.batteryPerHourTrend).hasSize(1)
        assertThat(result.batteryPerHourTrend[0]).isWithin(1f).of(600f)
    }

    // ── 7. savedFps null when < 2 distinct profiles with fps ─────────────────

    @Test
    fun `savedFps is null when only one profile has fps data`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.savedFps).isNull()
    }

    @Test
    fun `savedFps is null when no sessions have fps data`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = null),
            report(2L, packageName = "com.game", profileName = "Balanced", avgFps = null),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.savedFps).isNull()
    }

    // ── 8. savedFps = best avgFps − worst avgFps ─────────────────────────────

    @Test
    fun `savedFps is difference between best and worst profile avgFps`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(3L, packageName = "com.game", profileName = "Balanced", avgFps = 45f),
            report(4L, packageName = "com.game", profileName = "Balanced", avgFps = 45f),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        // best=60, worst=45 → spread=15
        assertThat(result.savedFps).isNotNull()
        assertThat(result.savedFps!!).isWithin(0.5f).of(15f)
    }

    // ── 9. appLabel carries most-recent non-null label ────────────────────────

    @Test
    fun `appLabel is the most recent non-null label`() {
        val reports = listOf(
            report(1L, startedAtMs = 1_000L, packageName = "com.game", appLabel = "Old Name"),
            report(2L, startedAtMs = 2_000L, packageName = "com.game", appLabel = "New Name"),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.appLabel).isEqualTo("New Name")
    }

    @Test
    fun `appLabel is null when all sessions have null appLabel`() {
        val reports = listOf(
            report(1L, packageName = "com.game", appLabel = null),
            report(2L, packageName = "com.game", appLabel = null),
        )
        val result = computePerPackageHistory(reports, "com.game")!!
        assertThat(result.appLabel).isNull()
    }

    // ── 10. Single session returns valid object with sessionCount=1 ────────────

    @Test
    fun `single session returns a valid PerPackageHistory with sessionCount 1`() {
        val reports = listOf(
            report(1L, packageName = "com.game", avgFps = 60f, peakCpuTempC = 70f),
        )
        val result = computePerPackageHistory(reports, "com.game")
        assertThat(result).isNotNull()
        assertThat(result!!.sessionCount).isEqualTo(1)
        assertThat(result.avgFpsTrend).hasSize(1)
        assertThat(result.peakTempTrend).hasSize(1)
    }
}
