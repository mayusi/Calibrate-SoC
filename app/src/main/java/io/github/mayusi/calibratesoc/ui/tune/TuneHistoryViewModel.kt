package io.github.mayusi.calibratesoc.ui.tune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wraps [TuneHistoryStore] for the [TuneHistoryScreen]. Read-mostly —
 * the store is appended-to from a half-dozen sites (Tune apply, HUD
 * chip, vendor key write, etc.); the screen just renders the log.
 */
@HiltViewModel
class TuneHistoryViewModel @Inject constructor(
    private val store: TuneHistoryStore,
) : ViewModel() {

    val entries: StateFlow<List<TuneHistoryEntry>> = store.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() {
        viewModelScope.launch { store.clear() }
    }
}
