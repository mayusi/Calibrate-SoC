package io.github.mayusi.calibratesoc.ui.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Read-only view of the HUD's in-memory event log. The OverlayService
 * is the writer; this VM just observes for the Dashboard's logs sheet.
 */
@HiltViewModel
class HudLogsViewModel @Inject constructor(
    private val log: HudEventLog,
) : ViewModel() {

    val entries: StateFlow<List<HudEventLog.Entry>> = log.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = log.clear()
}
