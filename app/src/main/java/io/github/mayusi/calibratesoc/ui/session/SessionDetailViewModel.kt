package io.github.mayusi.calibratesoc.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.SessionRepository
import io.github.mayusi.calibratesoc.data.session.SessionSummary
import io.github.mayusi.calibratesoc.data.session.computeSessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: SessionRepository,
) : ViewModel() {

    private val _session = MutableStateFlow<GameSession?>(null)
    val session: StateFlow<GameSession?> = _session.asStateFlow()

    /** Full summary including p1LowFps (recomputed from samples). */
    private val _fullSummary = MutableStateFlow<SessionSummary?>(null)
    val fullSummary: StateFlow<SessionSummary?> = _fullSummary.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            val s = repository.getById(id) ?: return@launch
            _session.value = s
            // Recompute with p1LowFps from the full sample list.
            _fullSummary.value = computeSessionSummary(s.samples)
        }
    }
}
