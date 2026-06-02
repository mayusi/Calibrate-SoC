package io.github.mayusi.calibratesoc.ui.stability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.benchmark.StabilityResult
import io.github.mayusi.calibratesoc.data.benchmark.StabilityTestRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the Stability Test (sustained GPU stress) sub-screen.
 *
 * v1: stability results are in-memory only — they are not persisted to
 * Room and disappear when the ViewModel is cleared.
 */
@HiltViewModel
class StabilityViewModel @Inject constructor(
    private val runner: StabilityTestRunner,
) : ViewModel() {

    val runnerState: StateFlow<StabilityTestRunner.State> = runner.state

    private val _result = MutableStateFlow<StabilityResult?>(null)
    val result: StateFlow<StabilityResult?> = _result.asStateFlow()

    fun start(loopCount: Int, loopMs: Long) {
        viewModelScope.launch {
            _result.value = runner.run(loopCount, loopMs)
        }
    }

    fun clear() {
        _result.value = null
    }
}
