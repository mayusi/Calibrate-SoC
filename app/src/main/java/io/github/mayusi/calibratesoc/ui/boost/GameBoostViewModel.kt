package io.github.mayusi.calibratesoc.ui.boost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.boost.GameBoostConfig
import io.github.mayusi.calibratesoc.data.boost.GameBoostController
import io.github.mayusi.calibratesoc.data.boost.GameBoostState
import io.github.mayusi.calibratesoc.data.boost.GameBoostStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Game Boost UI card/screen (Wave 4b).
 *
 * Owns:
 *  - [boostState]: live [GameBoostState] from [GameBoostController].
 *  - [autoTdpRunning]: true when AutoTDP is also running — used to show the
 *    mutual-exclusion warning (Game Boost and AutoTDP cannot both own clocks).
 *  - [canStart]: gate derived from both states — false when AutoTDP is running.
 *
 * Actions:
 *  - [startBoost]: kicks off a Game Boost session with the default config.
 *  - [stopBoost]: reverts and stops.
 */
@HiltViewModel
class GameBoostViewModel @Inject constructor(
    private val boostController: GameBoostController,
    private val autoTdpController: AutoTdpController,
) : ViewModel() {

    val boostState: StateFlow<GameBoostState> = boostController.state

    val autoTdpRunning: StateFlow<Boolean> = autoTdpController.state
        .map { it.status == AutoTdpStatus.RUNNING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True when Game Boost CAN be started — i.e. not already boosting and
     * AutoTDP is not running (mutual exclusion).
     */
    val canStart: StateFlow<Boolean> = combine(
        boostController.state,
        autoTdpController.state,
    ) { boost, autotdp ->
        boost.status != GameBoostStatus.BOOSTING &&
            autotdp.status != AutoTdpStatus.RUNNING
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Start a Game Boost session with the default config (30-min time box,
     * fan set to Sport). The controller handles the LIVE gate; if writes are
     * unavailable it self-stops and updates [boostState.status] to
     * LIVE_UNAVAILABLE with an explanation.
     *
     * @param timeBoxMinutes custom time box; clamped to 1..120.
     */
    fun startBoost(timeBoxMinutes: Int = GameBoostConfig.DEFAULT_TIME_BOX_MINUTES) {
        boostController.start(GameBoostConfig(timeBoxMinutes = timeBoxMinutes, setFanSport = true))
    }

    /** Stop the boost and revert all writes. */
    fun stopBoost() {
        boostController.stop()
    }
}
