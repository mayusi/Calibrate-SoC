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

    /**
     * Called by [AutoTdpSampler] when a probe cycle completes. Sets the measured
     * [SavingsResult] AND patches the current [AutoTdpEffect]'s MEASURED fields
     * (power, temp, fps, source) so the proof-of-effect bundle reflects the probe
     * atomically.
     *
     * HONESTY: temp/fps deltas are only surfaced when the probe measured them
     * (the [ProbeResult] carries null otherwise). The power fields + [EffectSource]
     * are recomputed via [AutoTdpEffect.from] off the gated savings. The DERIVED
     * fields (cap delta, parked cores, GPU floor) are preserved from the existing
     * effect — they were set by the daemon tick.
     */
    internal fun updateProbeResult(probe: ProbeResult) {
        val current = _state.value
        val existing = current.effect
        val patchedEffect = if (existing != null) {
            // Recompute the MEASURED power fields + source off the gated savings,
            // preserving the DERIVED fields the daemon tick already set.
            existing.copy(
                powerSavedMw = if (probe.savings.enoughData) probe.savings.deltaMw else null,
                powerSavedPct = if (probe.savings.enoughData) probe.savings.deltaPct else null,
                tempDeltaC = probe.tempDeltaC,
                fpsDelta = probe.fpsDelta,
                effectSource = if (probe.savings.enoughData) {
                    EffectSource.MEASURED
                } else {
                    EffectSource.ESTIMATED
                },
                sessionEnergySavedMilliWh = sessionEnergyMilliWh(probe.savings, current),
            )
        } else {
            existing
        }
        _state.value = current.copy(savings = probe.savings, effect = patchedEffect)
    }

    /**
     * Integrate session energy saved (mWh) from a measured saving over the elapsed
     * session window. Null when the saving isn't measured or the session start is
     * unknown. Pure helper (epochs read from [state], not the clock).
     */
    private fun sessionEnergyMilliWh(savings: SavingsResult, state: AutoTdpRunState): Double? {
        if (!savings.enoughData) return null
        val start = state.sessionStartEpochMs ?: return null
        val now = state.lastAppliedEpochMs ?: return null
        val elapsedMs = now - start
        if (elapsedMs <= 0L) return null
        val hours = elapsedMs.toDouble() / 3_600_000.0
        return savings.deltaMw.toDouble() * hours
    }
}
