package io.github.mayusi.calibratesoc.ui.hardware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.baseline.BaselineDegradation
import io.github.mayusi.calibratesoc.data.baseline.DegradationReport
import io.github.mayusi.calibratesoc.data.baseline.FactoryBaselineRecorder
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.hardware.HardwareReport
import io.github.mayusi.calibratesoc.data.hardware.HardwareScanner
import io.github.mayusi.calibratesoc.data.hardware.MemoryBandwidthTester
import io.github.mayusi.calibratesoc.data.hardware.NetworkSpeedTester
import io.github.mayusi.calibratesoc.data.hardware.NetworkTestResult
import io.github.mayusi.calibratesoc.data.hardware.StorageSpeedTester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hardware-tab state. Holds the static [HardwareReport] (rescanned
 * on demand) plus the running/completed state of each speed test.
 *
 * Speed tests are independent jobs — the user can kick storage and
 * network in parallel without one blocking the other. RAM bandwidth
 * is fast enough to run inline with the storage test.
 */
@HiltViewModel
class HardwareViewModel @Inject constructor(
    private val scanner: HardwareScanner,
    private val storageTester: StorageSpeedTester,
    private val memoryTester: MemoryBandwidthTester,
    private val networkTester: NetworkSpeedTester,
    private val capabilityProbe: CapabilityProbe,
    private val baselineRecorder: FactoryBaselineRecorder,
) : ViewModel() {

    private val _report = MutableStateFlow<HardwareReport?>(null)
    val report: StateFlow<HardwareReport?> = _report.asStateFlow()

    private val _testState = MutableStateFlow(TestState())
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _degradationReport = MutableStateFlow<DegradationReport?>(null)
    /** Baseline-degradation analysis; null while loading or when no baseline exists. */
    val degradationReport: StateFlow<DegradationReport?> = _degradationReport.asStateFlow()

    init {
        rescan()
        refreshDegradation()
    }

    /** Re-run the baseline comparison against the latest capability snapshot. */
    fun refreshDegradation() {
        viewModelScope.launch {
            val baseline = baselineRecorder.existing() ?: return@launch
            val current = capabilityProbe.report.value ?: return@launch
            _degradationReport.value = BaselineDegradation.analyze(baseline, current)
        }
    }

    fun rescan() {
        viewModelScope.launch {
            _report.value = scanner.scan()
        }
    }

    fun runStorageTest() {
        viewModelScope.launch {
            _testState.update { it.copy(storageRunning = true, storageError = null) }
            val outcome = runCatching { storageTester.run() }
            val result = outcome.getOrNull()
            _testState.update {
                it.copy(
                    storageRunning = false,
                    storageError = outcome.exceptionOrNull()
                        ?.let { e -> e.message ?: "Permission denied" },
                )
            }
            // Merge speed numbers back into the first storage volume.
            _report.update { current ->
                val r = current ?: return@update current
                val first = r.storage.firstOrNull() ?: return@update r
                val updated = first.copy(
                    seqReadMBps = result?.seqReadMBps,
                    seqWriteMBps = result?.seqWriteMBps,
                    randomReadIOPS = result?.randomReadIOPS,
                    randomWriteIOPS = result?.randomWriteIOPS,
                )
                r.copy(storage = listOf(updated) + r.storage.drop(1))
            }
        }
    }

    fun runMemoryTest() {
        viewModelScope.launch {
            _testState.update { it.copy(memoryRunning = true, memoryError = null) }
            val outcome = runCatching { memoryTester.run() }
            val mbps = outcome.getOrDefault(0.0)
            _testState.update {
                it.copy(
                    memoryRunning = false,
                    memoryError = outcome.exceptionOrNull()
                        ?.let { e -> e.message ?: "Permission denied" },
                )
            }
            _report.update { current ->
                val r = current ?: return@update current
                r.copy(memory = r.memory.copy(measuredBandwidthMBps = mbps))
            }
        }
    }

    fun runNetworkTest() {
        viewModelScope.launch {
            _testState.update { it.copy(networkRunning = true, networkError = null) }
            val outcome = runCatching { networkTester.run() }
            val result = outcome.getOrNull()
            _testState.update {
                it.copy(
                    networkRunning = false,
                    networkResult = result,
                    networkError = outcome.exceptionOrNull()
                        ?.let { e -> e.message ?: "Permission denied" },
                )
            }
        }
    }

    data class TestState(
        val storageRunning: Boolean = false,
        val memoryRunning: Boolean = false,
        val networkRunning: Boolean = false,
        val networkResult: NetworkTestResult? = null,
        val storageError: String? = null,
        val networkError: String? = null,
        val memoryError: String? = null,
    )
}
