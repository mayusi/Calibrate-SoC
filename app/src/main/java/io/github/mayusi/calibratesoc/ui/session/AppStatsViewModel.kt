package io.github.mayusi.calibratesoc.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.session.SessionRepository
import io.github.mayusi.calibratesoc.data.session.SessionStatsAggregator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Per-App Performance Dashboard (Feature 2).
 *
 * Observes all saved sessions via [SessionRepository] and produces a
 * [List<SessionStatsAggregator.AppSessionStats>] sorted by total playtime.
 * Computation is delegated to the pure [SessionStatsAggregator.aggregateByApp]
 * function so it stays unit-testable without ViewModelScope.
 *
 * The state auto-updates whenever a new session is saved or one is deleted,
 * because [SessionRepository.observeAll] is a Room Flow.
 */
@HiltViewModel
class AppStatsViewModel @Inject constructor(
    repository: SessionRepository,
) : ViewModel() {

    val appStats: StateFlow<List<SessionStatsAggregator.AppSessionStats>> =
        repository.observeAll()
            .map { sessions -> SessionStatsAggregator.aggregateByApp(sessions) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
