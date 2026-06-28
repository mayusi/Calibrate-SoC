package io.github.mayusi.calibratesoc.data.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [InsightsAggregator.computeBestProfilePerPackage].
 *
 * Covers:
 *   1. Ranks correct profile by avgFps (highest wins).
 *   2. Tiebreaks by lowest throttle/session when avgFps equal.
 *   3. Null-fps entries rank last (a profile with fps beats one without).
 *   4. Requires ≥ MIN_SESSIONS_FOR_PROFILE_RANK sessions per (pkg, profile) pair.
 *   5. Groups by packageName not appLabel (two labels, same pkg → same bucket).
 *   6. Carries appLabel from most-recent non-null session for that package.
 *   7. bestProfilePerPackage is populated in InsightsSummary via compute().
 *   8. A 1-session package never yields a MEASURED-eligible result.
 */
class InsightsAggregatorPackageTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun report(
        sessionId: Long,
        startedAtMs: Long = sessionId * 10_000L,
        packageName: String? = "com.example.game",
        appLabel: String? = "MyGame",
        profileName: String? = "Performance",
        avgFps: Float? = 60f,
        throttleEventCount: Int = 0,
    ) = SessionReport(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        durationMs = 60_000L,
        appLabel = appLabel,
        packageName = packageName,
        profileName = profileName,
        avgFps = avgFps,
        peakFps = avgFps?.let { it + 5f },
        p1LowFps = null,
        peakCpuTempC = 65f,
        peakGpuTempC = null,
        avgPowerW = 5.0,
        energyMwh = null,
        autoTdpSavedMwh = null,
        throttleEventCount = throttleEventCount,
        verdict = "Test verdict.",
    )

    // ── 1. Ranks by avgFps ────────────────────────────────────────────────────

    @Test
    fun `computeBestProfilePerPackage selects profile with highest avgFps`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(3L, packageName = "com.game", profileName = "Balanced", avgFps = 45f),
            report(4L, packageName = "com.game", profileName = "Balanced", avgFps = 45f),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result).containsKey("com.game")
        assertThat(result["com.game"]!!.profileName).isEqualTo("Perf")
        assertThat(result["com.game"]!!.avgFps).isWithin(0.1f).of(60f)
    }

    // ── 2. Tiebreaker: lowest throttle/session ────────────────────────────────

    @Test
    fun `computeBestProfilePerPackage tiebreaks by lowest throttle when fps equal`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "LowThrottle",
                avgFps = 60f, throttleEventCount = 0),
            report(2L, packageName = "com.game", profileName = "LowThrottle",
                avgFps = 60f, throttleEventCount = 0),
            report(3L, packageName = "com.game", profileName = "HighThrottle",
                avgFps = 60f, throttleEventCount = 4),
            report(4L, packageName = "com.game", profileName = "HighThrottle",
                avgFps = 60f, throttleEventCount = 4),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result["com.game"]!!.profileName).isEqualTo("LowThrottle")
    }

    // ── 3. Null-fps ranks last ────────────────────────────────────────────────

    @Test
    fun `computeBestProfilePerPackage null fps ranks last`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "WithFps", avgFps = 30f),
            report(2L, packageName = "com.game", profileName = "WithFps", avgFps = 30f),
            report(3L, packageName = "com.game", profileName = "NoFps", avgFps = null),
            report(4L, packageName = "com.game", profileName = "NoFps", avgFps = null),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result["com.game"]!!.profileName).isEqualTo("WithFps")
    }

    // ── 4. Requires ≥ MIN_SESSIONS_FOR_PROFILE_RANK ───────────────────────────

    @Test
    fun `computeBestProfilePerPackage skips pairs with fewer than MIN sessions`() {
        // Only 1 session — below the minimum of 2.
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result).isEmpty()
    }

    @Test
    fun `computeBestProfilePerPackage requires exactly MIN sessions to qualify`() {
        // Exactly MIN_SESSIONS_FOR_PROFILE_RANK (2) — should qualify.
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result).containsKey("com.game")
        assertThat(result["com.game"]!!.sessionCount).isEqualTo(2)
    }

    // ── 5. Groups by packageName not appLabel ─────────────────────────────────

    @Test
    fun `computeBestProfilePerPackage groups by packageName ignoring appLabel variation`() {
        // Same package, two different label spellings (e.g. locale difference) —
        // should be treated as the same package, giving 2 sessions per (pkg, profile).
        val reports = listOf(
            report(1L, packageName = "com.game", appLabel = "My Game",
                profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.game", appLabel = "my game",
                profileName = "Perf", avgFps = 60f),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result).containsKey("com.game")
        assertThat(result["com.game"]!!.sessionCount).isEqualTo(2)
    }

    @Test
    fun `computeBestProfilePerPackage treats different packages as separate even with same label`() {
        val reports = listOf(
            report(1L, packageName = "com.gameA", appLabel = "Game",
                profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.gameA", appLabel = "Game",
                profileName = "Perf", avgFps = 60f),
            report(3L, packageName = "com.gameB", appLabel = "Game",
                profileName = "Balanced", avgFps = 45f),
            report(4L, packageName = "com.gameB", appLabel = "Game",
                profileName = "Balanced", avgFps = 45f),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result).containsKey("com.gameA")
        assertThat(result).containsKey("com.gameB")
        assertThat(result["com.gameA"]!!.profileName).isEqualTo("Perf")
        assertThat(result["com.gameB"]!!.profileName).isEqualTo("Balanced")
    }

    // ── 6. Carries most-recent non-null appLabel ──────────────────────────────

    @Test
    fun `computeBestProfilePerPackage carries most recent non-null appLabel`() {
        val reports = listOf(
            report(1L, startedAtMs = 1_000L, packageName = "com.game",
                appLabel = "Old Label", profileName = "Perf"),
            report(2L, startedAtMs = 2_000L, packageName = "com.game",
                appLabel = "New Label", profileName = "Perf"),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        // Most-recent (highest startedAtMs with non-null label) wins.
        assertThat(result["com.game"]!!.appLabel).isEqualTo("New Label")
    }

    @Test
    fun `computeBestProfilePerPackage carries null appLabel when all sessions have null`() {
        val reports = listOf(
            report(1L, packageName = "com.game", appLabel = null, profileName = "Perf"),
            report(2L, packageName = "com.game", appLabel = null, profileName = "Perf"),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result["com.game"]!!.appLabel).isNull()
    }

    // ── 7. bestProfilePerPackage populated in InsightsSummary ────────────────

    @Test
    fun `compute populates bestProfilePerPackage in InsightsSummary`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
        )
        val summary = InsightsAggregator.compute(reports, weekReports = reports)
        assertThat(summary.bestProfilePerPackage).containsKey("com.game")
        assertThat(summary.bestProfilePerPackage["com.game"]!!.profileName).isEqualTo("Perf")
    }

    @Test
    fun `compute returns empty bestProfilePerPackage when no package data`() {
        // Reports with null packageName — should yield empty package map.
        val reports = listOf(
            report(1L, packageName = null, profileName = "Perf"),
            report(2L, packageName = null, profileName = "Perf"),
        )
        val summary = InsightsAggregator.compute(reports, weekReports = emptyList())
        assertThat(summary.bestProfilePerPackage).isEmpty()
    }

    // ── 8. 1-session package never qualifies ─────────────────────────────────

    @Test
    fun `a single session for a package never appears in bestProfilePerPackage`() {
        val reports = listOf(
            report(1L, packageName = "com.game", profileName = "Perf", avgFps = 60f),
        )
        val result = InsightsAggregator.computeBestProfilePerPackage(reports)
        assertThat(result).doesNotContainKey("com.game")
    }
}
