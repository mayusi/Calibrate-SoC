package io.github.mayusi.calibratesoc.data.autotdp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The clean control surface the UI binds to. It:
 *   - Exposes [state] — a [StateFlow<AutoTdpRunState>] the UI observes.
 *   - Routes [start]/[stop] to [AutoTdpService] via explicit [Context.startForegroundService].
 *   - Receives state updates FROM [AutoTdpService] via [updateState] (the service
 *     is injected with the same singleton and calls this to push telemetry).
 *
 * The UI NEVER pokes [AutoTdpService] directly — it only calls methods here.
 * The service NEVER exposes its own StateFlow — it pushes through [updateState].
 *
 * [AutoTdpSampler] also reads [state] to know when to run the savings measurement.
 */
@Singleton
class AutoTdpController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(AutoTdpRunState())
    val state: StateFlow<AutoTdpRunState> = _state.asStateFlow()

    /**
     * Start the AutoTDP LIVE daemon with the given [config].
     *
     * If the LIVE rung is not available on this device, the service will
     * self-stop and update [state] to [AutoTdpStatus.LIVE_UNAVAILABLE] with
     * a reason the UI can surface.
     */
    fun start(config: AutoTdpProfileConfig) {
        AutoTdpService.start(context, config)
    }

    /**
     * Convenience: start with a common profile shorthand.
     */
    fun start(profile: AutoTdpProfile, targetMilliWatts: Long? = null) {
        start(AutoTdpProfileConfig(profile = profile, targetMilliWatts = targetMilliWatts))
    }

    /**
     * Stop the daemon and revert all writes. The service handles revert
     * internally via TunableWriter.revertAll before it exits.
     */
    fun stop() {
        AutoTdpService.stop(context)
        // Pre-emptively mark as STOPPED in case the service doesn't push
        // a final state update fast enough (e.g. if it's already dead).
        val current = _state.value
        if (current.status == AutoTdpStatus.RUNNING) {
            _state.value = current.copy(status = AutoTdpStatus.STOPPED)
        }
    }

    /** Observe the live run state. UI and [AutoTdpSampler] collect this. */
    fun observeState(): StateFlow<AutoTdpRunState> = state

    // ── Internal API (called by AutoTdpService) ────────────────────────────────

    /**
     * Called by [AutoTdpService] to push a new snapshot of the daemon's
     * internal state. The service and controller share the same Hilt
     * singleton so no IPC is needed.
     */
    internal fun updateState(newState: AutoTdpRunState) {
        _state.value = newState
    }

    /** Convenience used by the service to do a targeted field update. */
    internal fun updateSavings(savings: SavingsResult) {
        _state.value = _state.value.copy(savings = savings)
    }
}
