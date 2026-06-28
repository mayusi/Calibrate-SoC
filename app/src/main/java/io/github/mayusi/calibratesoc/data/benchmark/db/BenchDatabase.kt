package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsDao
import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsEntity
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportDao
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportEntity
import io.github.mayusi.calibratesoc.data.scorelog.ExternalScoreDao
import io.github.mayusi.calibratesoc.data.scorelog.ExternalScoreEntity
import io.github.mayusi.calibratesoc.data.session.SessionDao
import io.github.mayusi.calibratesoc.data.session.SessionEntity

// Bumped to 2 when KernelScores landed: BenchRunEntity gained a new
// `kernelsJson` column. We're on fallbackToDestructiveMigration so
// existing rows from v1 are dropped on first launch under v2 — fine,
// benchmark history is convenience data not load-bearing.
// v3 added per-run `name` column. Destructive migration fine for
// benchmark history (convenience data, not load-bearing).
// v4 added the stability_runs table (StabilityRunEntity). Still
// destructive — stability history is convenience data too.
// v5 added GPU frame-time detail fields to KernelScores (gpuAvgFrameMs,
// gpuP50Fps, gpuP1LowFps, gpuP99FrameMs, gpuFrameConsistencyPct,
// gpuFrameTimesMs). These ride inside the existing `kernelsJson` column
// (no structural entity change). Bumped for hygiene; destructive
// fallback wipes old bench + stability history on first launch under v5.
// v6 added gpuMaxMhz field to ThrottleSample (nullable default). Rides
// inside the serialized throttleSamplesJson/samplesJson; backwards-compatible
// via ignoreUnknownKeys + defaults in Json config.
// v7 added the game_sessions table (SessionEntity) for the gaming session
// recorder feature. Destructive fallback — session history is convenience
// data, not load-bearing. Pre-alpha; existing bench/stability history is
// wiped on first launch under v7.
// v8 added the external_scores table (ExternalScoreEntity) for the Benchmark
// Hub manual score log. Destructive fallback — all history is convenience
// data, not load-bearing. Pre-alpha.
// v9 added the session_reports table (SessionReportEntity) for the performance
// insights engine. Destructive fallback — all history is convenience data.
// v10 (Unit 0 — per-game learning foundation): ADDITIVE migration — no data
// loss. Three additive changes:
//   1. game_sessions gains `packageName TEXT` (nullable, default null).
//   2. session_reports gains `packageName TEXT` (nullable, default null).
//   3. New table `learned_game_params` for persisted per-game AutoTDP params.
// Existing rows in game_sessions and session_reports are preserved; the new
// column defaults to NULL for all pre-v10 rows (correct — package was unknown).
// fallbackToDestructiveMigration remains as a safety net for versions < 9 that
// were never in production; v9→v10 is handled by MIGRATION_9_10.
@Database(
    entities = [
        BenchRunEntity::class,
        StabilityRunEntity::class,
        SessionEntity::class,
        ExternalScoreEntity::class,
        SessionReportEntity::class,
        LearnedGameParamsEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class BenchDatabase : RoomDatabase() {
    abstract fun benchRunDao(): BenchRunDao
    abstract fun stabilityRunDao(): StabilityRunDao
    abstract fun sessionDao(): SessionDao
    abstract fun externalScoreDao(): ExternalScoreDao
    abstract fun sessionReportDao(): SessionReportDao
    abstract fun learnedGameParamsDao(): LearnedGameParamsDao

    companion object {
        /**
         * Additive migration from v9 → v10. No data loss:
         *  - ALTER TABLE adds nullable packageName columns to existing tables.
         *  - CREATE TABLE IF NOT EXISTS adds the new learned_game_params table.
         *
         * Column types exactly match the Room entity definitions:
         *  - packageName TEXT  (String? → nullable TEXT, no default needed for ALTER ADD)
         *  - pkg TEXT NOT NULL PRIMARY KEY
         *  - safeSustainedCapKhz INTEGER / throttleOnsetSec INTEGER /
         *    observedBandCenterPct INTEGER  (Int? → nullable INTEGER)
         *  - sessionCount INTEGER NOT NULL DEFAULT 0  (Int with default)
         *  - lastUpdatedMs INTEGER NOT NULL DEFAULT 0  (Long with default)
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add packageName to game_sessions (nullable, no default — existing
                //    rows will have NULL which is the correct "unknown" sentinel).
                db.execSQL(
                    "ALTER TABLE game_sessions ADD COLUMN packageName TEXT",
                )
                // 2. Add packageName to session_reports (same rationale).
                db.execSQL(
                    "ALTER TABLE session_reports ADD COLUMN packageName TEXT",
                )
                // 3. Create the learned_game_params table. Schema must match
                //    LearnedGameParamsEntity exactly or Room schema validation fails.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS learned_game_params (
                        pkg TEXT NOT NULL PRIMARY KEY,
                        safeSustainedCapKhz INTEGER,
                        throttleOnsetSec INTEGER,
                        observedBandCenterPct INTEGER,
                        sessionCount INTEGER NOT NULL DEFAULT 0,
                        lastUpdatedMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
