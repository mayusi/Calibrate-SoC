package io.github.mayusi.calibratesoc.ui.benchmark

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.benchmarkhub.BenchmarkAppRegistry
import io.github.mayusi.calibratesoc.data.benchmarkhub.KnownBenchmarkApp
import io.github.mayusi.calibratesoc.data.scorelog.ExternalScoreEntity
import io.github.mayusi.calibratesoc.data.scorelog.ExternalScoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Benchmark Hub tab.
 *
 * Responsibilities:
 *   - Detect which known benchmark apps are installed (one-shot on creation).
 *   - Provide live list of all user-entered external scores.
 *   - CRUD for score log entries.
 *
 * LEGAL: no score scraping, no accessibility reading. Only install detection
 * via PackageManager and launch intents.
 */
@HiltViewModel
class BenchmarkHubViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scoreRepo: ExternalScoreRepository,
) : ViewModel() {

    /** Install status for each known benchmark app. Computed once at startup. */
    data class AppInstallState(
        val app: KnownBenchmarkApp,
        val installed: Boolean,
    )

    private val _installedApps = MutableStateFlow<List<AppInstallState>>(emptyList())
    val installedApps: StateFlow<List<AppInstallState>> = _installedApps.asStateFlow()

    /** All user-entered external scores, newest first. */
    val scores: StateFlow<List<ExternalScoreEntity>> = scoreRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All distinct benchmark names that have scores logged. */
    val scoredBenchmarks: StateFlow<List<String>> = scoreRepo.observeDistinctNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        detectInstalledApps()
    }

    private fun detectInstalledApps() {
        val pm = context.packageManager
        _installedApps.value = BenchmarkAppRegistry.ALL.map { app ->
            val installed = try {
                pm.getLaunchIntentForPackage(app.packageName) != null
            } catch (_: Exception) {
                false
            }
            AppInstallState(app = app, installed = installed)
        }
    }

    fun saveScore(
        benchmarkName: String,
        packageName: String?,
        scoreValue: Double,
        scoreLabel: String,
        deviceName: String?,
        notedAtMs: Long,
        note: String?,
    ) {
        viewModelScope.launch {
            scoreRepo.save(
                ExternalScoreEntity(
                    benchmarkName = benchmarkName,
                    packageName = packageName,
                    scoreValue = scoreValue,
                    scoreLabel = scoreLabel,
                    deviceName = deviceName?.trim()?.ifEmpty { null },
                    notedAtMs = notedAtMs,
                    note = note?.trim()?.ifEmpty { null },
                )
            )
        }
    }

    fun deleteScore(id: Long) {
        viewModelScope.launch { scoreRepo.delete(id) }
    }
}
