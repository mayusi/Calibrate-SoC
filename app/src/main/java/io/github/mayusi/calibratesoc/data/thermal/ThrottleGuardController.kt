package io.github.mayusi.calibratesoc.data.thermal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clean control surface the UI binds to for the Predictive Throttle Guard. Mirrors
 * [io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController].
 *
 * The UI NEVER pokes [ThrottleGuardService] directly — it only calls [start]/[stop]
 * here and observes [state].
 */
@Singleton
class ThrottleGuardController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(ThrottleGuardState())
    val state: StateFlow<ThrottleGuardState> = _state.asStateFlow()

    /** Start the standalone throttle-guard loop. The service handles the LIVE gate. */
    fun start() {
        ThrottleGuardService.start(context)
    }

    /** Stop the guard and revert any active pre-emptive cap (service reverts on exit). */
    fun stop() {
        ThrottleGuardService.stop(context)
        val current = _state.value
        if (current.status == ThrottleGuardStatus.RUNNING) {
            _state.value = current.copy(status = ThrottleGuardStatus.STOPPED, activeCapKhz = null)
        }
    }

    fun observeState(): StateFlow<ThrottleGuardState> = state

    // ── Internal API (called by ThrottleGuardService) ──────────────────────────

    internal fun updateState(newState: ThrottleGuardState) {
        _state.value = newState
    }
}
