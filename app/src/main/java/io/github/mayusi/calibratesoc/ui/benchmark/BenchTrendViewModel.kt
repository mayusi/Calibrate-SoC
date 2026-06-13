package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchRepository
import io.github.mayusi.calibratesoc.data.benchmark.BenchTrend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Metric selector for the trend view. */
enum class TrendMetric(val label: String) {
    OVERALL("Overall"),
    CPU("CPU"),
    GPU("GPU"),
    MEMORY("Memory"),
}

/**
 * ViewModel for the Trends tab. Observes the repository's run stream,
 * computes [BenchTrend.TrendResult] reactively whenever the history changes
 * or the user changes the flavor / metric selector.
 *
 * Deliberately separate from [BenchmarkViewModel] to keep it focused and
 * to avoid inflating BenchmarkViewModel further.
 */
@HiltViewModel
class BenchTrendViewModel @Inject constructor(
    private val repo: BenchRepository,
) : ViewModel() {

    // ─── User-selected filters ────────────────────────────────────────────

    private val _selectedFlavor = MutableStateFlow<BenchFlavor?>(null)
    val selectedFlavor: StateFlow<BenchFlavor?> = _selectedFlavor.asStateFlow()

    private val _selectedMetric = MutableStateFlow(TrendMetric.OVERALL)
    val selectedMetric: StateFlow<TrendMetric> = _selectedMetric.asStateFlow()

    fun selectFlavor(flavor: BenchFlavor) {
        _selectedFlavor.value = flavor
    }

    fun selectMetric(metric: TrendMetric) {
        _selectedMetric.value = metric
    }

    // ─── Reactive trend result ────────────────────────────────────────────

    /**
     * The computed [BenchTrend.TrendResult] for the currently selected flavor,
     * or null when no flavor is selected yet (first emission auto-selects
     * the flavor with the most runs via [BenchTrend.defaultFlavor]).
     */
    val trendResult: StateFlow<BenchTrend.TrendResult?> =
        combine(repo.observeAll(), _selectedFlavor) { allRuns, explicitFlavor ->
            // Auto-select on first load: pick the flavor with most runs.
            val flavor = explicitFlavor
                ?: BenchTrend.defaultFlavor(allRuns)
                    ?.also { auto -> _selectedFlavor.value = auto }
                ?: return@combine null
            BenchTrend.compute(allRuns, flavor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
