package io.github.mayusi.calibratesoc.data.boost

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clean control surface the UI (Wave 4) binds to for Game Boost. Mirrors
 * [io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController]:
 *   - Exposes [state] — a [StateFlow<GameBoostState>] the UI observes.
 *   - Routes [start]/[stop] to [GameBoostService].
 *   - Receives state updates FROM the service via [updateState] (same singleton).
 *
 * The UI NEVER pokes [GameBoostService] directly.
 */
@Singleton
class GameBoostController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(GameBoostState())
    val state: StateFlow<GameBoostState> = _state.asStateFlow()

    /** Start a Game Boost session with [config]. The service handles the LIVE gate. */
    fun start(config: GameBoostConfig = GameBoostConfig()) {
        GameBoostService.start(context, config.normalized())
    }

    /** Stop the boost and revert everything (the service reverts via TunableWriter). */
    fun stop() {
        GameBoostService.stop(context)
        val current = _state.value
        if (current.status == GameBoostStatus.BOOSTING) {
            _state.value = current.copy(status = GameBoostStatus.STOPPED)
        }
    }

    /** Observe the live boost state. UI collects this. */
    fun observeState(): StateFlow<GameBoostState> = state

    // ── Internal API (called by GameBoostService) ──────────────────────────────

    internal fun updateState(newState: GameBoostState) {
        _state.value = newState
    }
}
