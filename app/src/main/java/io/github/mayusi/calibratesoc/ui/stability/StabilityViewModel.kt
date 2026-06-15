package io.github.mayusi.calibratesoc.ui.stability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.benchmark.BenchRepository
import io.github.mayusi.calibratesoc.data.benchmark.StabilityResult
import io.github.mayusi.calibratesoc.data.benchmark.StabilityRun
import io.github.mayusi.calibratesoc.data.benchmark.StabilityTestRunner
import kotlinx.coroutines.Job
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

    /** Job backing the current in-flight stability run. Null when idle. */
    private var runJob: Job? = null

    private val _result = MutableStateFlow<StabilityResult?>(null)
    val result: StateFlow<StabilityResult?> = _result.asStateFlow()

    val history: StateFlow<List<StabilityRun>> =
        repository.observeStabilityRuns().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun start(loopCount: Int, loopMs: Long) {
        runJob = viewModelScope.launch {
            // runner.run() throws CancellationException if the job is cancelled.
            // In that case the code below is never reached, so no partial result
            // is shown or persisted — which is the desired behaviour for user cancel.
            val result = runner.run(loopCount = loopCount, loopMs = loopMs, killTempC = 95f)
            _result.value = result
            repository.saveStability(result, loopMs)
        }
    }

    /**
     * Cancel a running stability test. Cancellation propagates into
     * StabilityTestRunner's coroutine structure (cpu jobs, sampler job,
     * and the loop itself all check [kotlinx.coroutines.isActive]). The
     * runner's finally block resets state to Idle and closes the CPU
     * dispatcher. No partial result is shown or persisted.
     *
     * BUG 9: also clear _result so a stale previous run is not shown after
     * the user cancels a new run.
     */
    fun cancelStability() {
        runJob?.cancel()
        runJob = null
        _result.value = null   // clear stale result on cancel
    }

    fun clear() {
        _result.value = null
    }

    fun deleteRun(id: Long) {
        viewModelScope.launch {
            repository.deleteStability(id)
        }
    }

    /**
     * Re-insert a previously-deleted stability run (for undo-delete).
     * Re-saves the run using the original [StabilityRun] object captured
     * by the UI before deletion.
     */
    fun reinsertRun(run: StabilityRun) {
        viewModelScope.launch {
            repository.reinsertStability(run)
        }
    }
}
