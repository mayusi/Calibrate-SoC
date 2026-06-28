package io.github.mayusi.calibratesoc.data.insights.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.TdpDecision
import io.github.mayusi.calibratesoc.data.autotdp.TdpState
import io.github.mayusi.calibratesoc.data.benchmark.db.BenchDatabase
import io.github.mayusi.calibratesoc.data.insights.SessionReport
import io.github.mayusi.calibratesoc.data.insights.SessionReportBuilder
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.computeSessionSummary
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test

/**
 * Unit 0 schema + plumbing tests.
 *
 * Pure JVM tests (no Room runtime, no Android instrumentation). Verifies:
 *   1. MIGRATION_9_10 covers v9→v10 and its SQL statements are correct.
 *   2. LearnedGameParamsEntity defaults and round-trip.
 *   3. packageName round-trips through GameSession → SessionReport → SessionReportEntity.
 *   4. TdpDecision.nextTickHintMs defaults to null (behaviour-preserving).
 */
class Unit0SchemaTest {

    // ── Migration SQL capture helper ─────────────────────────────────────────

    /**
     * Invoke the migration against a mockk stub that records every execSQL
     * call. Returns the ordered list of SQL strings the migration executed.
     */
    private fun captureMigrationSql(): List<String> {
        val captured = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()
        every { db.execSQL(capture(sqlSlot)) } answers {
            captured += sqlSlot.captured
        }
        BenchDatabase.MIGRATION_9_10.migrate(db)
        return captured
    }

    // ── 1. Migration object correctness ─────────────────────────────────────

    @Test
    fun `MIGRATION_9_10 covers version 9 to 10`() {
        val m = BenchDatabase.MIGRATION_9_10
        assertThat(m.startVersion).isEqualTo(9)
        assertThat(m.endVersion).isEqualTo(10)
    }

    @Test
    fun `MIGRATION_9_10 executes exactly 3 SQL statements`() {
        // 1: ALTER game_sessions, 2: ALTER session_reports, 3: CREATE learned_game_params
        assertThat(captureMigrationSql()).hasSize(3)
    }

    @Test
    fun `MIGRATION_9_10 first statement adds packageName to game_sessions`() {
        val sql = captureMigrationSql()[0]
        assertThat(sql).ignoringCase().contains("ALTER TABLE")
        assertThat(sql).contains("game_sessions")
        assertThat(sql).contains("packageName")
        assertThat(sql).contains("TEXT")
    }

    @Test
    fun `MIGRATION_9_10 second statement adds packageName to session_reports`() {
        val sql = captureMigrationSql()[1]
        assertThat(sql).ignoringCase().contains("ALTER TABLE")
        assertThat(sql).contains("session_reports")
        assertThat(sql).contains("packageName")
        assertThat(sql).contains("TEXT")
    }

    @Test
    fun `MIGRATION_9_10 third statement creates learned_game_params table`() {
        val sql = captureMigrationSql()[2]
        assertThat(sql).ignoringCase().contains("CREATE TABLE")
        assertThat(sql).contains("learned_game_params")
        assertThat(sql).contains("pkg")
        assertThat(sql).contains("TEXT NOT NULL PRIMARY KEY")
        assertThat(sql).contains("safeSustainedCapKhz")
        assertThat(sql).contains("throttleOnsetSec")
        assertThat(sql).contains("observedBandCenterPct")
        assertThat(sql).contains("sessionCount")
        assertThat(sql).contains("lastUpdatedMs")
        assertThat(sql).contains("INTEGER NOT NULL")
        assertThat(sql).contains("DEFAULT 0")
    }

    // ── 2. LearnedGameParamsEntity ───────────────────────────────────────────

    @Test
    fun `LearnedGameParamsEntity has correct defaults for a new row`() {
        val entity = LearnedGameParamsEntity(
            pkg = "com.example.game",
            safeSustainedCapKhz = null,
            throttleOnsetSec = null,
            observedBandCenterPct = null,
        )
        assertThat(entity.pkg).isEqualTo("com.example.game")
        assertThat(entity.safeSustainedCapKhz).isNull()
        assertThat(entity.throttleOnsetSec).isNull()
        assertThat(entity.observedBandCenterPct).isNull()
        assertThat(entity.sessionCount).isEqualTo(0)
        assertThat(entity.lastUpdatedMs).isEqualTo(0L)
    }

    @Test
    fun `LearnedGameParamsEntity copy with updated values round-trips correctly`() {
        val base = LearnedGameParamsEntity(
            pkg = "com.rp.retroarch",
            safeSustainedCapKhz = null,
            throttleOnsetSec = null,
            observedBandCenterPct = null,
        )
        val updated = base.copy(
            safeSustainedCapKhz = 1804800,
            throttleOnsetSec = 120,
            observedBandCenterPct = 72,
            sessionCount = 5,
            lastUpdatedMs = 1_700_000_000_000L,
        )
        assertThat(updated.pkg).isEqualTo("com.rp.retroarch")
        assertThat(updated.safeSustainedCapKhz).isEqualTo(1804800)
        assertThat(updated.throttleOnsetSec).isEqualTo(120)
        assertThat(updated.observedBandCenterPct).isEqualTo(72)
        assertThat(updated.sessionCount).isEqualTo(5)
        assertThat(updated.lastUpdatedMs).isEqualTo(1_700_000_000_000L)
    }

    // ── 3. packageName round-trips ───────────────────────────────────────────

    @Test
    fun `GameSession carries packageName`() {
        assertThat(makeSession(packageName = "com.rp.retroarch").packageName)
            .isEqualTo("com.rp.retroarch")
    }

    @Test
    fun `GameSession packageName is null when unknown`() {
        assertThat(makeSession(packageName = null).packageName).isNull()
    }

    @Test
    fun `SessionReportBuilder propagates packageName to SessionReport`() {
        val report = SessionReportBuilder.build(makeSession(packageName = "com.ayaneo.gamesir"))
        assertThat(report.packageName).isEqualTo("com.ayaneo.gamesir")
    }

    @Test
    fun `SessionReportBuilder propagates null packageName`() {
        val report = SessionReportBuilder.build(makeSession(packageName = null))
        assertThat(report.packageName).isNull()
    }

    @Test
    fun `SessionReportEntity fromDomain preserves packageName`() {
        val entity = SessionReportEntity.fromDomain(makeReport(packageName = "com.rp.retroarch"))
        assertThat(entity.packageName).isEqualTo("com.rp.retroarch")
    }

    @Test
    fun `SessionReportEntity toDomain preserves packageName`() {
        val domain = SessionReportEntity.fromDomain(makeReport(packageName = "com.example.game"))
            .toDomain()
        assertThat(domain.packageName).isEqualTo("com.example.game")
    }

    @Test
    fun `SessionReportEntity toDomain preserves null packageName for pre-v10 rows`() {
        // null simulates an existing row that had no packageName before v10 migration.
        val domain = SessionReportEntity.fromDomain(makeReport(packageName = null)).toDomain()
        assertThat(domain.packageName).isNull()
    }

    // ── 4. TdpDecision.nextTickHintMs default ───────────────────────────────

    @Test
    fun `TdpDecision nextTickHintMs defaults to null preserving current behaviour`() {
        val decision = TdpDecision(
            target = TdpState.STOCK,
            reason = "test",
        )
        assertThat(decision.nextTickHintMs).isNull()
    }

    @Test
    fun `TdpDecision nextTickHintMs can be explicitly set by Unit 2`() {
        val decision = TdpDecision(
            target = TdpState.STOCK,
            reason = "adaptive",
            nextTickHintMs = 2000,
        )
        assertThat(decision.nextTickHintMs).isEqualTo(2000)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun makeSession(packageName: String?): GameSession = GameSession(
        id = 1L,
        startedAtMs = 1_000_000L,
        durationMs = 60_000L,
        appLabel = packageName?.let { "TestApp" },
        packageName = packageName,
        profileName = null,
        samples = emptyList(),
        summary = computeSessionSummary(emptyList()),
        fpsAvailableDuringSampling = false,
    )

    private fun makeReport(packageName: String?): SessionReport = SessionReport(
        sessionId = 1L,
        startedAtMs = 1_000_000L,
        durationMs = 60_000L,
        appLabel = null,
        packageName = packageName,
        profileName = null,
        avgFps = null,
        peakFps = null,
        p1LowFps = null,
        peakCpuTempC = null,
        peakGpuTempC = null,
        avgPowerW = null,
        energyMwh = null,
        autoTdpSavedMwh = null,
        throttleEventCount = 0,
        verdict = "Not enough data (only 0 samples recorded).",
    )
}
