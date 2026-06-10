package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.benchmark.BenchConfig
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchRepository
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.benchmark.BenchmarkRunner
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sort order for the benchmark history list.
 */
enum class BenchSortOrder { NEWEST, HIGHEST_SCORE }

/**
 * State for the Benchmark screen. Backs the run list, in-flight run
 * status, and the side-by-side compare selection.
 */
@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val runner: BenchmarkRunner,
    private val repo: BenchRepository,
    private val probe: CapabilityProbe,
) : ViewModel() {

    /** Live capability report — needed by BenchRating to derive SoC class + ceiling. */
    val capability: StateFlow<CapabilityReport?> = probe.report

    val history: StateFlow<List<BenchRun>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runnerState: StateFlow<BenchmarkRunner.State> = runner.state

    /** Up to two selected runs for the compare drawer. */
    private val _compareSelection = MutableStateFlow<List<Long>>(emptyList())
    val compareSelection: StateFlow<List<Long>> = _compareSelection.asStateFlow()

    /** Sort order for the history list. */
    private val _sortOrder = MutableStateFlow(BenchSortOrder.NEWEST)
    val sortOrder: StateFlow<BenchSortOrder> = _sortOrder.asStateFlow()

    fun setSortOrder(order: BenchSortOrder) {
        _sortOrder.value = order
    }

    fun toggleSelection(id: Long) {
        val current = _compareSelection.value
        _compareSelection.value = when {
            id in current -> current - id
            current.size >= 2 -> listOf(current.last(), id) // drop the oldest
            else -> current + id
        }
    }

    fun clearSelection() {
        _compareSelection.value = emptyList()
    }

    fun runBenchmark(flavor: BenchFlavor, name: String? = null) {
        viewModelScope.launch {
            val resolved = name?.trim()?.takeIf { it.isNotEmpty() }
            val run = if (resolved != null) {
                runner.run(
                    flavor = flavor,
                    config = BenchConfig(),
                    appVersion = BuildConfig.VERSION_NAME,
                    name = resolved,
                )
            } else {
                runner.run(
                    flavor = flavor,
                    config = BenchConfig(),
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
            // 0L id == not yet persisted. Outcomes other than COMPLETED
            // still get saved so the user can see what happened (and why
            // the temp cap fired, e.g.).
            repo.save(run)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }
}
