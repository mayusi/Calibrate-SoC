package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Database
import androidx.room.RoomDatabase

// Bumped to 2 when KernelScores landed: BenchRunEntity gained a new
// `kernelsJson` column. We're on fallbackToDestructiveMigration so
// existing rows from v1 are dropped on first launch under v2 — fine,
// benchmark history is convenience data not load-bearing.
// v3 added per-run `name` column. Destructive migration fine for
// benchmark history (convenience data, not load-bearing).
@Database(entities = [BenchRunEntity::class], version = 3, exportSchema = false)
abstract class BenchDatabase : RoomDatabase() {
    abstract fun benchRunDao(): BenchRunDao
}
