package io.github.mayusi.calibratesoc.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.insights.PerPackageHistory
import io.github.mayusi.calibratesoc.data.insights.computePerPackageHistory
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportDao
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.SessionRepository
import io.github.mayusi.calibratesoc.data.session.SessionStatsAggregator
import io.github.mayusi.calibratesoc.data.session.SessionSummary
import io.github.mayusi.calibratesoc.data.session.computeSessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val sessionReportDao: SessionReportDao,
) : ViewModel() {

    private val _session = MutableStateFlow<GameSession?>(null)
    val session: StateFlow<GameSession?> = _session.asStateFlow()

    /** Full summary including p1LowFps (recomputed from samples). */
    private val _fullSummary = MutableStateFlow<SessionSummary?>(null)
    val fullSummary: StateFlow<SessionSummary?> = _fullSummary.asStateFlow()

    /**
     * Heuristic thermal throttle events for the current session.
     * Empty list when: session has too few samples, FPS was not available,
     * or no throttle+FPS-dip coincidence was detected.
     * Always empty (never null) so the UI can render directly without null-checking.
     */
    private val _throttleEvents =
        MutableStateFlow<List<SessionStatsAggregator.ThermalThrottleEvent>>(emptyList())
    val throttleEvents: StateFlow<List<SessionStatsAggregator.ThermalThrottleEvent>> =
        _throttleEvents.asStateFlow()

    /** Pre-built one-line plain-language throttle summary sentence, or null when no events. */
    private val _throttleSummary = MutableStateFlow<String?>(null)
    val throttleSummary: StateFlow<String?> = _throttleSummary.asStateFlow()

    /**
     * Per-game aggregate history for the package of the loaded session.
     *
     * Null when:
     *  - The session has no packageName.
     *  - Fewer than 2 sessions exist for this package (honesty: no trends to show).
     *
     * Populated from [SessionReportDao] (which has [SessionReport.autoTdpSavedMwh]
     * and [SessionReport.energyMwh] needed for the savings headline).
     */
    private val _perGameHistory = MutableStateFlow<PerPackageHistory?>(null)
    val perGameHistory: StateFlow<PerPackageHistory?> = _perGameHistory.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            val s = repository.getById(id) ?: return@launch
            _session.value = s
            // Recompute with p1LowFps from the full sample list.
            _fullSummary.value = computeSessionSummary(s.samples)
            // Detect heuristic throttle events.
            val events = SessionStatsAggregator.detectThrottleEvents(s)
            _throttleEvents.value = events
            _throttleSummary.value = SessionStatsAggregator.buildThrottleSummary(events)

            // Feature B2: per-game history from session reports.
            val pkg = s.packageName
            if (pkg != null) {
                val allReports = sessionReportDao.observeAll().first()
                    .map { it.toDomain() }
                val history = computePerPackageHistory(allReports, pkg)
                // Only expose when ≥2 sessions exist — single session has no trend.
                _perGameHistory.value = if ((history?.sessionCount ?: 0) >= 2) history else null
            }
        }
    }
}
