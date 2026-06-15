package io.github.mayusi.calibratesoc.ui.tune.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import io.github.mayusi.calibratesoc.data.script.ScriptGenerateResult
import io.github.mayusi.calibratesoc.data.thermal.FanCurveModel
import io.github.mayusi.calibratesoc.data.thermal.PredictiveThrottleGuard
import io.github.mayusi.calibratesoc.data.thermal.ThrottleForecast
import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Advanced Tuning screen.
 *
 * ## Privilege-tier modes
 *
 * ### ROOT tier
 * Every write dispatches immediately through [TunableWriter] → [RootWriter].
 * The screen behaves as it always has: Apply = live kernel change.
 *
 * ### SYSFS_UNLOCKED tier (unlock script ran, cpufreq nodes are chmod 666)
 * Nodes covered by the unlock script ([Tunables.isUnlockCoveredNode]) are
 * live-writable via [UnlockedFileWriter] — [Tunables.whyWriteDenied] returns
 * null for those. The screen writes them immediately just like ROOT.
 * Nodes NOT covered (procfs, cgroups) switch to script-builder mode for
 * those specific controls.
 *
 * ### AYN_SETTINGS / NONE / SHIZUKU (stock, no chmod) — SCRIPT-BUILDER MODE
 * Controls become ENABLED for value selection.  Each interaction calls
 * [stageAdvancedKnob] instead of [write]; the value lands in [pendingAdvanced].
 * The user then taps "Generate Script" ([generateAdvancedScript]) which
 * builds a [Preset] from [pendingAdvanced].extraSysfs and dispatches through
 * [AynScriptGenerator] → [AynScriptDeployer].
 *
 * Custom sysfs rules: on stock, [writeCustomRule] also stages into pendingAdvanced.
 *
 * HIGH/DANGEROUS knobs still show a confirm dialog before staging (dialog
 * text is re-worded to "add to script" in script-builder mode).
 */
@HiltViewModel
class AdvancedTuningViewModel @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val tunableWriter: TunableWriter,
    private val scriptGenerator: AynScriptGenerator,
    private val scriptDeployer: AynScriptDeployer,
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val monitorService: MonitorService,
) : ViewModel() {

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    // ── last write result (live-write mode only) ─────────────────────────────

    private val _lastResult = MutableStateFlow<WriteResult?>(null)
    val lastResult: StateFlow<WriteResult?> = _lastResult.asStateFlow()

    fun clearLastResult() { _lastResult.value = null }

    // ── script-builder staging (pending staged knobs path→value) ─────────────

    private val _pendingAdvanced = MutableStateFlow<Map<String, String>>(emptyMap())
    val pendingAdvanced: StateFlow<Map<String, String>> = _pendingAdvanced.asStateFlow()

    /** Count of staged knobs for the "Generate Script (N)" CTA badge. */
    val pendingAdvancedCount: StateFlow<Int> = MutableStateFlow(0).also { counter ->
        viewModelScope.launch {
            _pendingAdvanced.collect { counter.value = it.size }
        }
    }

    fun clearPendingAdvanced() { _pendingAdvanced.value = emptyMap() }

    // ── last script deploy result ─────────────────────────────────────────────

    private val _lastDeploy = MutableStateFlow<AynScriptDeployer.Deployed?>(null)
    val lastDeploy: StateFlow<AynScriptDeployer.Deployed?> = _lastDeploy.asStateFlow()

    fun clearLastDeploy() { _lastDeploy.value = null }

    // ── custom sysfs rule history (in-memory, this session only) ────────────

    private val _customRuleHistory = MutableStateFlow<List<CustomSysfsRule>>(emptyList())
    val customRuleHistory: StateFlow<List<CustomSysfsRule>> = _customRuleHistory.asStateFlow()

    data class CustomSysfsRule(
        val path: String,
        val value: String,
        val appliedAtMs: Long,
    )

    // ── thermal guard toggle ──────────────────────────────────────────────────

    /**
     * Whether the predictive thermal guard is enabled by the user.
     * When true, the UI shows the guard's last forecast prominently.
     * The guard is ADVISORY in this surface — it does not write tunables
     * itself (that happens in the daemon layer); here it surfaces information.
     */
    private val _thermalGuardEnabled = MutableStateFlow(false)
    val thermalGuardEnabled: StateFlow<Boolean> = _thermalGuardEnabled.asStateFlow()

    /**
     * Latest [PredictiveThrottleGuard.ThrottleForecast] — updated once per
     * telemetry tick when the guard is enabled. Null when disabled or when
     * the window is too small.
     */
    private val _throttleForecast = MutableStateFlow<ThrottleForecast?>(null)
    val throttleForecast: StateFlow<ThrottleForecast?> = _throttleForecast.asStateFlow()

    /**
     * Whether the device has a fan that our model can control.
     * Derived from [CapabilityReport.fan] on first report arrival.
     */
    private val _fanCurveModel = MutableStateFlow<FanCurveModel?>(null)
    val fanCurveModel: StateFlow<FanCurveModel?> = _fanCurveModel.asStateFlow()

    // Telemetry window for thermal guard (kept in-VM for simplicity, MAX_WINDOW entries)
    private val thermalWindow = ArrayDeque<PredictiveThrottleGuard.TelemetryPoint>()

    init {
        // Build the FanCurveModel from the first capability report.
        capabilityProbe.report
            .onEach { report ->
                if (report != null) {
                    _fanCurveModel.value = FanCurveModel.fromReport(report)
                }
            }
            .launchIn(viewModelScope)

        // Feed telemetry into the thermal guard window when guard is enabled.
        viewModelScope.launch {
            monitorService.telemetry().collect { t ->
                if (_thermalGuardEnabled.value) {
                    val pt = PredictiveThrottleGuard.TelemetryPoint.from(t)
                    thermalWindow.addLast(pt)
                    while (thermalWindow.size > MAX_THERMAL_WINDOW) thermalWindow.removeFirst()
                    val forecast = PredictiveThrottleGuard.predict(thermalWindow.toList())
                    _throttleForecast.value = forecast
                }
            }
        }
    }

    fun setThermalGuardEnabled(enabled: Boolean) {
        _thermalGuardEnabled.value = enabled
        if (!enabled) {
            thermalWindow.clear()
            _throttleForecast.value = null
        }
    }

    companion object {
        private const val MAX_THERMAL_WINDOW = 30
    }

    // ── mode helpers ──────────────────────────────────────────────────────────

    /**
     * True when the screen should operate in SCRIPT-BUILDER mode:
     *   - no root AND
     *   - the target knob is not live-writable (not unlock-covered or unlock not run)
     *
     * Used by the UI to decide whether control interactions call
     * [stageAdvancedKnob] or [write].
     */
    fun isScriptBuilderMode(id: TunableId, report: CapabilityReport): Boolean {
        if (report.privilege == PrivilegeTier.ROOT) return false
        // If whyWriteDenied returns null the node is live-writable (unlock covered it).
        return Tunables.whyWriteDenied(id, report) != null
    }

    /**
     * True when a node is completely unreachable from this app — neither
     * live-writable NOR scriptable via the extraSysfs pipeline.
     *
     * These are: cgroup paths (/dev/stune, /dev/cpuctl) and
     * /sys/class/thermal/ — the UI renders them as read-only info rows.
     */
    fun isRootOnlyNode(id: TunableId): Boolean {
        val path = id.target
        return path.startsWith("/dev/stune/") ||
            path.startsWith("/dev/cpuctl/") ||
            path.startsWith("/sys/class/thermal/")
    }

    // ── privilege helpers ─────────────────────────────────────────────────────

    /** Returns non-null string when a live write to [id] would be denied. */
    fun whyWriteDenied(id: TunableId, report: CapabilityReport): String? =
        Tunables.whyWriteDenied(id, report)

    // ── single tunable write (live-write mode) ─────────────────────────────────

    /**
     * Validate [value] against [TunableMetadata] for [id], then write
     * through [TunableWriter] if valid. Result stored in [lastResult].
     *
     * Returns the validation error string immediately (before any IO)
     * when metadata rejects the value.
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

    // ── script-builder staging ─────────────────────────────────────────────────

    /**
     * Stage a knob into [pendingAdvanced] for inclusion in the next
     * Generate Script call. Validates via [TunableMetadata] before staging;
     * returns an error string on validation failure (same API contract as [write]).
     *
     * Does NOT write anything to the kernel.
     */
    fun stageAdvancedKnob(id: TunableId, value: String): String? {
        // Block root-only nodes from staging — they won't work in a script either.
        if (isRootOnlyNode(id)) {
            return "This knob can only be changed with root access — it cannot be included in a generated script."
        }

        val meta = TunableMetadata.forId(id)
        val validationError = meta.validate(value)
        if (validationError != null) return validationError

        // Path-level validation (custom sysfs rules already do this, but apply universally).
        val pathError = TunableMetadata.validateCustomSysfsPath(id.target)
        if (pathError != null) return pathError

        _pendingAdvanced.value = _pendingAdvanced.value + mapOf(id.target to value)
        return null
    }

    /**
     * Remove a previously-staged knob from [pendingAdvanced].
     * No-op if the knob was not staged.
     */
    fun unstageAdvancedKnob(id: TunableId) {
        _pendingAdvanced.value = _pendingAdvanced.value - id.target
    }

    /**
     * Generate a script from [pendingAdvanced] and deploy it via [AynScriptDeployer].
     * Builds a synthetic Preset with all staged knobs as extraSysfs entries.
     * Result reported via [lastDeploy].
     */
    fun generateAdvancedScript() {
        val staged = _pendingAdvanced.value
        if (staged.isEmpty()) return
        val report = capability.value ?: return
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)

        val ts = System.currentTimeMillis() / 1000
        val preset = Preset(
            id = "advanced_custom_$ts",
            name = "Advanced Tuning (custom)",
            description = "Custom knobs staged in Advanced Tuning — ${staged.size} knob(s).",
            verification = VerificationTier.USER_CUSTOM,
            extraSysfs = staged,
        )

        viewModelScope.launch {
            when (val result = scriptGenerator.generate(preset, report, adapter)) {
                is ScriptGenerateResult.Ok -> _lastDeploy.value = scriptDeployer.deploy(preset, result.script)
                is ScriptGenerateResult.Rejected -> { /* advanced-tuning preset is device-local; rejection is a no-op */ }
            }
        }
    }

    // ── custom sysfs rule ──────────────────────────────────────────────────────

    /**
     * In LIVE mode (root / unlock covered): apply immediately.
     * In SCRIPT-BUILDER mode (stock): stage into [pendingAdvanced].
     *
     * Returns an error string on validation failure.
     */
    fun writeCustomRule(path: String, value: String): String? {
        val pathError = TunableMetadata.validateCustomSysfsPath(path)
        if (pathError != null) return pathError

        val id = try {
            KernelTunables.customSysfsRule(path)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Invalid sysfs path."
        }

        val meta = TunableMetadata.forId(id)
        val valError = meta.validate(value)
        if (valError != null) return valError

        val report = capability.value ?: return "Device capability not yet loaded."

        // Determine mode for this specific path.
        val denyReason = Tunables.whyWriteDenied(id, report)
        return if (denyReason == null) {
            // Live write path (root / unlock-covered).
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
            null
        } else {
            // Script-builder mode: stage it.
            val stagingError = stageAdvancedKnob(id, value)
            if (stagingError == null) {
                val rule = CustomSysfsRule(path, value, System.currentTimeMillis())
                _customRuleHistory.value = listOf(rule) +
                    _customRuleHistory.value.filter { it.path != path }
            }
            stagingError
        }
    }
}
