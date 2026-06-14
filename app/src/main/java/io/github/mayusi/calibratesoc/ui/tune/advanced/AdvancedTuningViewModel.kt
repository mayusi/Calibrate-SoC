package io.github.mayusi.calibratesoc.ui.tune.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Advanced Tuning screen.
 *
 * Design: every write is staged as a (TunableId, String) pair and
 * dispatched through [TunableWriter] — the ONLY write path. Validation
 * via [TunableMetadata] runs client-side before dispatch; if it fails
 * the write is rejected in the ViewModel and the error is surfaced to
 * the UI without touching the kernel.
 *
 * Privilege pre-flight: [Tunables.whyWriteDenied] is exposed as
 * [whyWriteDenied] so the UI can grey out controls with an honest
 * explanation instead of silently failing.
 *
 * Custom sysfs rules are stored in [customRuleHistory] (in-memory list,
 * not persisted) so previously-typed rules can be re-applied in one tap.
 */
@HiltViewModel
class AdvancedTuningViewModel @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val tunableWriter: TunableWriter,
) : ViewModel() {

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    // ── last write result ────────────────────────────────────────────────────

    private val _lastResult = MutableStateFlow<WriteResult?>(null)
    val lastResult: StateFlow<WriteResult?> = _lastResult.asStateFlow()

    fun clearLastResult() { _lastResult.value = null }

    // ── custom sysfs rule history (in-memory, this session only) ────────────

    private val _customRuleHistory = MutableStateFlow<List<CustomSysfsRule>>(emptyList())
    val customRuleHistory: StateFlow<List<CustomSysfsRule>> = _customRuleHistory.asStateFlow()

    data class CustomSysfsRule(
        val path: String,
        val value: String,
        val appliedAtMs: Long,
    )

    // ── privilege helpers ─────────────────────────────────────────────────────

    /** Returns non-null string when a write to [id] would be denied.
     *  The UI should grey the control and show this reason. */
    fun whyWriteDenied(id: TunableId, report: CapabilityReport): String? =
        Tunables.whyWriteDenied(id, report)

    // ── single tunable write ──────────────────────────────────────────────────

    /**
     * Validate [value] against [TunableMetadata] for [id], then write
     * through [TunableWriter] if valid. Result stored in [lastResult].
     *
     * Returns the validation error string immediately (before any IO)
     * when metadata rejects the value so the caller can show it inline.
     */
    fun write(id: TunableId, value: String, reason: String): String? {
        val meta = TunableMetadata.forId(id)
        val validationError = meta.validate(value)
        if (validationError != null) return validationError

        val report = capability.value ?: return "Device capability not yet loaded."
        val denyReason = Tunables.whyWriteDenied(id, report)
        if (denyReason != null) return denyReason

        viewModelScope.launch {
            val result = tunableWriter.write(id = id, value = value, report = report, reason = reason)
            _lastResult.value = result
            capabilityProbe.refresh()
        }
        return null
    }

    /**
     * Apply a custom sysfs rule. Validates the path via
     * [TunableMetadata.validateCustomSysfsPath] before constructing the id.
     * Returns an error string on any validation failure.
     */
    fun writeCustomRule(path: String, value: String): String? {
        val pathError = TunableMetadata.validateCustomSysfsPath(path)
        if (pathError != null) return pathError

        val id = try {
            KernelTunables.customSysfsRule(path)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Invalid sysfs path."
        }

        // Validate value via metadata (RAW_STRING, so always passes — but
        // it will catch any future tightening of the fallthrough case).
        val meta = TunableMetadata.forId(id)
        val valError = meta.validate(value)
        if (valError != null) return valError

        val report = capability.value ?: return "Device capability not yet loaded."
        val denyReason = Tunables.whyWriteDenied(id, report)
        if (denyReason != null) return denyReason

        viewModelScope.launch {
            val result = tunableWriter.write(
                id = id,
                value = value,
                report = report,
                reason = "Custom sysfs rule: $path",
            )
            _lastResult.value = result
            if (result is WriteResult.Success) {
                val rule = CustomSysfsRule(path, value, System.currentTimeMillis())
                _customRuleHistory.value = listOf(rule) +
                    _customRuleHistory.value.filter { it.path != path }
            }
        }
        return null
    }
}
