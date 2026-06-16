package io.github.mayusi.calibratesoc.data.profiles

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.boost.GameBoostConfig
import io.github.mayusi.calibratesoc.data.boost.GameBoostService
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GameBoostLauncherImpl"

/**
 * Concrete [GameBoostLauncher] that delegates to Wave 3a's [GameBoostService].
 *
 * Uses the default [GameBoostConfig] (30-minute time box, fan Sport). Per-game
 * config tuning (custom time box, disable fan Sport) can be added to
 * [PerAppBundle] and passed through here in a later wave.
 *
 * This implementation is bound by [ProfilesModule] so [ForegroundAppWatcher]
 * receives the real service instead of [NoOpGameBoostLauncher].
 */
@Singleton
class GameBoostLauncherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GameBoostLauncher {

    override suspend fun startBoost(packageName: String) {
        Log.i(TAG, "startBoost($packageName): starting GameBoostService with default config")
        GameBoostService.start(context, GameBoostConfig())
    }

    override suspend fun stopBoost(packageName: String) {
        Log.i(TAG, "stopBoost($packageName): stopping GameBoostService")
        GameBoostService.stop(context)
    }
}
