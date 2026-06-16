package io.github.mayusi.calibratesoc.ui.thermal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.boost.GameBoostController
import io.github.mayusi.calibratesoc.data.boost.GameBoostStatus
import io.github.mayusi.calibratesoc.data.thermal.ThrottleGuardController
import io.github.mayusi.calibratesoc.data.thermal.ThrottleGuardState
import io.github.mayusi.calibratesoc.data.thermal.ThrottleGuardStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Predictive Throttle Guard toggle UI (Wave 4b).
 *
 * Owns:
 *  - [guardState]: live [ThrottleGuardState] from [ThrottleGuardController].
 *  - [isEnabled]: whether the guard is currently running (RUNNING status).
 *  - [suppressionReason]: human-readable explanation when [ThrottleGuardState.suppressed]
 *    is true (AutoTDP or Game Boost owns thermals).
 *
 * Actions:
 *  - [setEnabled]: start or stop the guard.
 */
@HiltViewModel
class ThrottleGuardViewModel @Inject constructor(
    private val guardController: ThrottleGuardController,
    private val autoTdpController: AutoTdpController,
    private val boostController: GameBoostController,
) : ViewModel() {

    val guardState: StateFlow<ThrottleGuardState> = guardController.state

    val isEnabled: StateFlow<Boolean> = guardController.state
        .map { it.status == ThrottleGuardStatus.RUNNING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Human-readable suppression reason when the guard is running but suppressed
     * because another subsystem owns the clocks. Null when not suppressed.
     */
    val suppressionReason: StateFlow<String?> = combine(
        guardController.state,
        autoTdpController.state,
        boostController.state,
    ) { guard, autotdp, boost ->
        if (!guard.suppressed) return@combine null
        when {
            autotdp.status == AutoTdpStatus.RUNNING ->
                "AutoTDP is handling thermals through the band controller."
            boost.status == GameBoostStatus.BOOSTING ->
                "Game Boost is handling thermals through the thermal-trip guard."
            else ->
                "Another subsystem is managing clocks and thermals."
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Start or stop the guard. */
    fun setEnabled(on: Boolean) {
        if (on) guardController.start() else guardController.stop()
    }
}
