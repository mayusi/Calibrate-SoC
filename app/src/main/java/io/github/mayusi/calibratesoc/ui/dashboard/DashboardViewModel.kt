package io.github.mayusi.calibratesoc.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard state container. Pulls one CapabilityReport on init (for the
 * "what controls are reachable" badge + chart axis labels) and collects
 * the live telemetry Flow into a rolling history buffer the chart can
 * render without re-allocating every tick.
 *
 * The history is capped at [HISTORY_SAMPLES] (60 = one minute at 1 Hz)
 * so memory doesn't grow unbounded if the user leaves the dashboard
 * open. Vico re-renders the whole series each transaction; trimming
 * here also keeps the chart's x-axis range stable.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    capabilityProbe: CapabilityProbe,
    monitorService: MonitorService,
    tuneHistoryStore: TuneHistoryStore,
) : ViewModel() {

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /**
     * Name of the most recently applied tune preset, or null when the
     * history is empty (= stock / factory state).
     */
    val lastAppliedPreset: StateFlow<String?> = tuneHistoryStore.entries
        .map { entries -> entries.firstOrNull()?.presetName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Rolling history of recent samples; head = oldest, tail = newest. */
    private val _history = MutableStateFlow<List<Telemetry>>(emptyList())
    val history: StateFlow<List<Telemetry>> = _history.asStateFlow()

    /** Latest single sample for non-chart widgets (current MHz badge,
     *  battery W readout, etc.). Convenient derived state. */
    val latest: StateFlow<Telemetry?> = _history
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Make sure the capability report is loaded; the monitor service
        // needs it for GPU probe path lookup. Idempotent.
        viewModelScope.launch { capabilityProbe.refresh() }
        viewModelScope.launch {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                val current = _history.value
                _history.value = if (current.size >= HISTORY_SAMPLES) {
                    current.drop(current.size - HISTORY_SAMPLES + 1) + sample
                } else {
                    current + sample
                }
            }
        }
    }

    companion object {
        const val HISTORY_SAMPLES = 60
    }
}
