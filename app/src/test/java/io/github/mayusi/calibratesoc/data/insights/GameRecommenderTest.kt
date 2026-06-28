package io.github.mayusi.calibratesoc.data.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [GameRecommender.recommendFor].
 *
 * Covers:
 *   1. ≥2 sessions for a package → MEASURED tier with correct profile/evidence.
 *   2. No session data but KnownGames hit → SUGGESTED tier with hint profile.
 *   3. Neither session data nor KnownGames hit → null.
 *   4. A 1-session package never yields MEASURED (aggregator MIN_SESSIONS=2 guards this).
 *   5. MEASURED evidence omits fps clause when avgFps is null.
 *   6. SUGGESTED evidence never says "best" or "proven".
 */
class GameRecommenderTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun report(
        sessionId: Long,
        packageName: String? = "com.example.game",
        appLabel: String? = "My Game",
        profileName: String? = "Performance",
        avgFps: Float? = 60f,
        throttleEventCount: Int = 0,
    ) = SessionReport(
        sessionId = sessionId,
        startedAtMs = sessionId * 10_000L,
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

    private fun summaryFor(vararg reports: SessionReport): InsightsAggregator.InsightsSummary {
        val list = reports.toList()
        return InsightsAggregator.compute(list, weekReports = list)
    }

    // ── 1. MEASURED tier ─────────────────────────────────────────────────────

    @Test
    fun `returns MEASURED tier when package has at least 2 sessions`() {
        val summary = summaryFor(
            report(1L, packageName = "com.example.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.example.game", profileName = "Perf", avgFps = 60f),
        )
        val rec = GameRecommender.recommendFor("com.example.game", "My Game", summary)
        assertThat(rec).isNotNull()
        assertThat(rec!!.tier).isEqualTo(RecommendationTier.MEASURED)
        assertThat(rec.profileName).isEqualTo("Perf")
        assertThat(rec.sessionCount).isEqualTo(2)
    }

    @Test
    fun `MEASURED recommendation carries correct avgFps`() {
        val summary = summaryFor(
            report(1L, packageName = "com.example.game", profileName = "Perf", avgFps = 55f),
            report(2L, packageName = "com.example.game", profileName = "Perf", avgFps = 65f),
        )
        val rec = GameRecommender.recommendFor("com.example.game", "My Game", summary)!!
        assertThat(rec.avgFps).isWithin(0.5f).of(60f)
    }

    @Test
    fun `MEASURED evidence contains Proven for you and session count`() {
        val summary = summaryFor(
            report(1L, packageName = "com.example.game", profileName = "Perf", avgFps = 60f),
            report(2L, packageName = "com.example.game", profileName = "Perf", avgFps = 60f),
        )
        val rec = GameRecommender.recommendFor("com.example.game", "My Game", summary)!!
        assertThat(rec.evidence).contains("Proven for you")
        assertThat(rec.evidence).contains("2 sessions")
    }

    @Test
    fun `MEASURED evidence omits fps clause when avgFps is null`() {
        val summary = summaryFor(
            report(1L, packageName = "com.example.game", profileName = "Perf", avgFps = null),
            report(2L, packageName = "com.example.game", profileName = "Perf", avgFps = null),
        )
        val rec = GameRecommender.recommendFor("com.example.game", "My Game", summary)!!
        assertThat(rec.tier).isEqualTo(RecommendationTier.MEASURED)
        // No fps number fabricated
        assertThat(rec.evidence).doesNotContain("fps")
        assertThat(rec.avgFps).isNull()
    }

    // ── 2. SUGGESTED tier ────────────────────────────────────────────────────

    @Test
    fun `returns SUGGESTED when no sessions but KnownGames has an entry`() {
        // org.ppsspp.ppsspp is in KnownGames with EFFICIENCY profile.
        val summary = summaryFor() // no sessions
        val rec = GameRecommender.recommendFor("org.ppsspp.ppsspp", "PPSSPP", summary)
        assertThat(rec).isNotNull()
        assertThat(rec!!.tier).isEqualTo(RecommendationTier.SUGGESTED)
        assertThat(rec.profileName).isNull() // no profile name for SUGGESTED
        assertThat(rec.sessionCount).isEqualTo(0)
        assertThat(rec.suggestedAutoTdpProfile).isNotNull()
    }

    @Test
    fun `SUGGESTED evidence does not contain best or proven`() {
        val summary = summaryFor()
        val rec = GameRecommender.recommendFor("org.dolphinemu.dolphinemu", "Dolphin", summary)!!
        assertThat(rec.evidence.lowercase()).doesNotContain("best")
        assertThat(rec.evidence.lowercase()).doesNotContain("proven")
    }

    @Test
    fun `SUGGESTED evidence mentions it is a starting point suggestion`() {
        val summary = summaryFor()
        val rec = GameRecommender.recommendFor("org.ppsspp.ppsspp", "PPSSPP", summary)!!
        assertThat(rec.evidence).contains("Suggested starting point")
    }

    // ── 3. null when no data and not in KnownGames ───────────────────────────

    @Test
    fun `returns null for unknown package with no sessions`() {
        val summary = summaryFor()
        val rec = GameRecommender.recommendFor("com.unknown.random.app", "Unknown", summary)
        assertThat(rec).isNull()
    }

    // ── 4. 1-session package never yields MEASURED ───────────────────────────

    @Test
    fun `a single session for a package does not yield MEASURED`() {
        val summary = summaryFor(
            report(1L, packageName = "com.example.game", profileName = "Perf", avgFps = 60f),
        )
        // Either null or SUGGESTED (if in KnownGames) — never MEASURED.
        val rec = GameRecommender.recommendFor("com.example.game", "My Game", summary)
        // com.example.game is not in KnownGames, so result must be null.
        assertThat(rec).isNull()
    }

    @Test
    fun `a single session for a KnownGames package yields SUGGESTED not MEASURED`() {
        // org.ppsspp.ppsspp is in KnownGames; 1 session should not produce MEASURED.
        val summary = summaryFor(
            report(1L, packageName = "org.ppsspp.ppsspp", profileName = "Perf", avgFps = 60f),
        )
        val rec = GameRecommender.recommendFor("org.ppsspp.ppsspp", "PPSSPP", summary)
        assertThat(rec).isNotNull()
        assertThat(rec!!.tier).isEqualTo(RecommendationTier.SUGGESTED)
    }

    // ── 5. Correct profile selected when multiple profiles exist ─────────────

    @Test
    fun `MEASURED selects the best-performing profile among multiple`() {
        val summary = summaryFor(
            report(1L, packageName = "com.game", profileName = "High", avgFps = 55f),
            report(2L, packageName = "com.game", profileName = "High", avgFps = 55f),
            report(3L, packageName = "com.game", profileName = "Max", avgFps = 60f),
            report(4L, packageName = "com.game", profileName = "Max", avgFps = 60f),
        )
        val rec = GameRecommender.recommendFor("com.game", "Game", summary)!!
        assertThat(rec.profileName).isEqualTo("Max")
    }
}
