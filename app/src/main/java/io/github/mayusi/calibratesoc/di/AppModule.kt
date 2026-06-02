package io.github.mayusi.calibratesoc.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mayusi.calibratesoc.data.benchmark.db.BenchDatabase
import io.github.mayusi.calibratesoc.data.benchmark.db.BenchRunDao
import kotlinx.serialization.json.Json
import okio.FileSystem
import javax.inject.Singleton

/**
 * Explicit Hilt bindings live here. Most classes use @Inject constructor +
 * @Singleton directly. Reserved for things we don't own — JSON config, the
 * FileSystem indirection for testable sysfs probing, etc.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Lenient + non-strict so future schema additions don't break old caches. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Real filesystem for production. Tests inject Okio FakeFileSystem
     * via @TestInstallIn so SysfsProber unit tests don't touch /sys.
     */
    @Provides
    @Singleton
    fun provideFileSystem(): FileSystem = FileSystem.SYSTEM

    @Provides
    @Singleton
    fun provideBenchDatabase(@ApplicationContext context: Context): BenchDatabase =
        Room.databaseBuilder(context, BenchDatabase::class.java, "bench_history.db")
            // Destructive migrations are fine for v1 — benchmark history
            // is a user-facing convenience, not load-bearing data. If
            // we ever ship a schema change, the next launch resets the
            // history rather than crashing.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBenchRunDao(db: BenchDatabase): BenchRunDao = db.benchRunDao()
}
