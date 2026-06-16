package io.github.mayusi.calibratesoc.data.profiles

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the profiles package.
 *
 * [GameBoostLauncher] is bound to [GameBoostLauncherImpl] which delegates to
 * Wave 3a's GameBoostService.
 *
 * [AppReaper] is bound to [AppReaperImpl] — the concrete reaper that issues
 * `am force-stop` commands via PServerWriter.executeShell.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProfilesModule {

    @Binds
    @Singleton
    abstract fun bindGameBoostLauncher(impl: GameBoostLauncherImpl): GameBoostLauncher

    @Binds
    @Singleton
    abstract fun bindAppReaper(impl: AppReaperImpl): AppReaper
}
