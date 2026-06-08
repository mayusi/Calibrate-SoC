package io.github.mayusi.calibratesoc.ui.stability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.benchmark.BenchRepository
import io.github.mayusi.calibratesoc.data.benchmark.StabilityResult
import io.github.mayusi.calibratesoc.data.benchmark.StabilityRun
import io.github.mayusi.calibratesoc.data.benchmark.StabilityTestRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the Stability Test (sustained GPU stress) sub-screen.
 *
 * Results are persisted to Room via [BenchRepository]; [history] is the
 * reverse-chronological list of past runs, live-updated as new runs land.
 */
@HiltViewModel
class StabilityViewModel @Inject constructor(
    private val runner: StabilityTestRunner,
    private val repository: BenchRepository,
) : ViewModel() {

    val runnerState: StateFlow<StabilityTestRunner.State> = runner.state

    private val _result = MutableStateFlow<StabilityResult?>(null)
    val result: StateFlow<StabilityResult?> = _result.asStateFlow()

    val history: StateFlow<List<StabilityRun>> =
        repository.observeStabilityRuns().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun start(loopCount: Int, loopMs: Long) {
        viewModelScope.launch {
            val result = runner.run(loopCount = loopCount, loopMs = loopMs, killTempC = 95f)
            _result.value = result
            repository.saveStability(result, loopMs)
        }
    }

    fun clear() {
        _result.value = null
    }

    fun deleteRun(id: Long) {
        viewModelScope.launch {
            repository.deleteStability(id)
        }
    }
}
