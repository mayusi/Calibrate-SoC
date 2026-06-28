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
import io.github.mayusi.calibratesoc.data.benchmark.db.StabilityRunDao
import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsDao
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportDao
import io.github.mayusi.calibratesoc.data.scorelog.ExternalScoreDao
import io.github.mayusi.calibratesoc.data.session.SessionDao
import io.github.mayusi.calibratesoc.data.share.AndroidBase64Encoder
import io.github.mayusi.calibratesoc.data.share.Base64Encoder
import io.github.mayusi.calibratesoc.data.share.GameTuneShareCodec
import io.github.mayusi.calibratesoc.data.share.PresetShareCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okio.FileSystem
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the application-lifetime [CoroutineScope] provided by [AppModule].
 *
 * This scope lives for the whole process. It is used to host long-lived shared
 * flows — notably [io.github.mayusi.calibratesoc.data.monitor.MonitorService]'s
 * single shared telemetry stream — so that ONE sysfs-polling loop is shared by
 * every default-interval subscriber instead of each one spawning its own. The
 * scope is never cancelled (the process death is the only "shutdown"), which is
 * correct for `SharingStarted.WhileSubscribed` flows that stop their upstream on
 * their own when no one is collecting.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Explicit Hilt bindings live here. Most classes use @Inject constructor +
 * @Singleton directly. Reserved for things we don't own — JSON config, the
 * FileSystem indirection for testable sysfs probing, etc.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Application-lifetime coroutine scope (IO dispatcher + a [SupervisorJob] so
     * one failing child never tears down siblings). Hosts process-scoped shared
     * flows. Never cancelled — process death is the lifecycle boundary.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // v9→v10: additive migration — adds packageName columns to existing
            // tables and creates the learned_game_params table. No data loss.
            .addMigrations(BenchDatabase.MIGRATION_9_10)
            // Destructive fallback covers any version older than v9 (pre-production).
            // Combined with the explicit migration above, v9 devices get the additive
            // path; anything older gets wiped (convenience data only).
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBenchRunDao(db: BenchDatabase): BenchRunDao = db.benchRunDao()

    @Provides
    fun provideStabilityRunDao(db: BenchDatabase): StabilityRunDao = db.stabilityRunDao()

    @Provides
    fun provideSessionDao(db: BenchDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideExternalScoreDao(db: BenchDatabase): ExternalScoreDao = db.externalScoreDao()

    @Provides
    fun provideSessionReportDao(db: BenchDatabase): SessionReportDao = db.sessionReportDao()

    @Provides
    fun provideLearnedGameParamsDao(db: BenchDatabase): LearnedGameParamsDao =
        db.learnedGameParamsDao()

    /** Production Base64 encoder backed by android.util.Base64. */
    @Provides
    @Singleton
    fun provideBase64Encoder(): Base64Encoder = AndroidBase64Encoder()

    /** Preset share codec — injected into ProfilesViewModel for share/import. */
    @Provides
    @Singleton
    fun providePresetShareCodec(json: Json, base64: Base64Encoder): PresetShareCodec =
        PresetShareCodec(json, base64)

    /** Game tune share codec — injected into GameTuneViewModel for CSOC2 share/import. */
    @Provides
    @Singleton
    fun provideGameTuneShareCodec(json: Json, base64: Base64Encoder): GameTuneShareCodec =
        GameTuneShareCodec(json, base64)
}
