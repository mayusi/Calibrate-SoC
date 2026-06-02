package io.github.mayusi.calibratesoc.ui.capability

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceReportExporter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 1 diagnostic ViewModel. Triggers a probe on init and on user-
 * requested refresh. Holds the latest [CapabilityReport] plus the matched
 * [DeviceAdapter] (or null if generic) so the UI can render both.
 */
@HiltViewModel
class CapabilityReportViewModel @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val exporter: DeviceReportExporter,
) : ViewModel() {

    fun buildReportShareIntent(): Intent? {
        val report = capabilityProbe.report.value ?: return null
        return exporter.buildShareIntent(report)
    }

    /**
     * Write the report straight to /sdcard/CalibrateSoC/device_report_<model>.json.
     * Used when adding new handheld support — the dev pulls the file
     * via adb instead of going through the system share sheet. Returns
     * absolute path or null if the write failed (rare — only when the
     * external storage is read-only).
     */
    fun saveReportToDisk(): String? {
        val report = capabilityProbe.report.value ?: return null
        return exporter.writeToDisk(report)
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val report = capabilityProbe.refresh()
            val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            _state.value = UiState.Ready(report, adapter)
        }
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val report: CapabilityReport, val adapter: DeviceAdapter?) : UiState
    }
}
