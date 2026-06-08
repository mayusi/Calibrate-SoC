package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Database
import androidx.room.RoomDatabase

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
@Database(
    entities = [BenchRunEntity::class, StabilityRunEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class BenchDatabase : RoomDatabase() {
    abstract fun benchRunDao(): BenchRunDao
    abstract fun stabilityRunDao(): StabilityRunDao
}
