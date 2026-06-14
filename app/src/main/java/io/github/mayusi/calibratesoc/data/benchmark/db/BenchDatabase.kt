package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
@Database(
    entities = [
        BenchRunEntity::class,
        StabilityRunEntity::class,
        SessionEntity::class,
        ExternalScoreEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class BenchDatabase : RoomDatabase() {
    abstract fun benchRunDao(): BenchRunDao
    abstract fun stabilityRunDao(): StabilityRunDao
    abstract fun sessionDao(): SessionDao
    abstract fun externalScoreDao(): ExternalScoreDao
}
