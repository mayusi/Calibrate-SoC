package io.github.mayusi.calibratesoc.ui.autotdp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Smart-AutoTDP goal-mode picker (Wave 4b).
 *
 * Exposes:
 *  - [selectedGoal]: the currently chosen [GoalProfile] (user intent).
 *  - [runState]: forwarded from [AutoTdpController] (the live daemon state).
 *  - [detectedContext]: the classifier's current DETECTED belief — exposed
 *    separately so the UI can apply the correct honesty tier styling (DETECTED,
 *    not MEASURED).
 *  - [activeGoal]: the CONCRETE goal the daemon is running this tick (for AUTO
 *    this is the classifier's resolution; for other goals it equals [selectedGoal]).
 *
 * Wire:
 *  - [selectGoal] to the 5-mode picker chip.
 *  - [startWithSelectedGoal] to the Start button (replaces the old start-with-profile).
 *  - [stop] to the Stop button.
 *
 * This VM is used by [AutoTdpScreen] alongside the existing [AutoTdpViewModel] —
 * the two VMs coexist in the same screen. [AutoTdpViewModel] owns the legacy
 * profile picker, rung decision, sweep, and script flows. This VM owns the Smart
 * goal picker and the new Wave 4a/4b fields.
 */
@HiltViewModel
class SmartAutoTdpViewModel @Inject constructor(
    private val controller: AutoTdpController,
) : ViewModel() {

    // ── Selected goal (user intent, persists across stop/start) ──────────────

    private val _selectedGoal = MutableStateFlow(GoalProfile.BALANCED_SMART)
    val selectedGoal: StateFlow<GoalProfile> = _selectedGoal.asStateFlow()

    // ── Live run-state passthrough ────────────────────────────────────────────

    val runState: StateFlow<AutoTdpRunState> = controller.state

    /**
     * The CONCRETE goal the daemon is running right now (for AUTO, this is the
     * classifier's resolved goal; for other goals it mirrors [selectedGoal]).
     * Null before the first decision tick.
     */
    val activeGoal: StateFlow<GoalProfile?> = controller.state
        .map { it.activeGoal }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The workload context the classifier BELIEVES it is in.
     * This is the DETECTED honesty tier — NOT a measurement. Null before the
     * first tick, or when running a non-AUTO goal (the classifier still runs
     * internally but the field is only meaningful in AUTO mode).
     */
    val detectedContext: StateFlow<WorkloadContext?> = controller.state
        .map { it.detectedContext }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Convenience: is the daemon currently RUNNING. */
    val isRunning: StateFlow<Boolean> = controller.state
        .map { it.status == AutoTdpStatus.RUNNING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Update the selected goal (does not start/stop the daemon). */
    fun selectGoal(goal: GoalProfile) {
        _selectedGoal.value = goal
    }

    /**
     * Start the daemon with the currently selected goal.
     * Uses [AutoTdpController.start(GoalProfile)] — the native Smart path
     * (Wave 4a) that reaches the band controller / AUTO classifier directly.
     *
     * @param targetMilliWatts optional watts ceiling; passed through to goals
     *   that honour a hard power ceiling ([GoalProfile.hasHardPowerCeiling]).
     */
    fun startWithSelectedGoal(targetMilliWatts: Long? = null) {
        controller.start(_selectedGoal.value, targetMilliWatts)
    }

    /** Stop the daemon and revert all writes. */
    fun stop() {
        controller.stop()
    }
}
