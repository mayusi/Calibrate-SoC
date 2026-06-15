package io.github.mayusi.calibratesoc.ui.autotdp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfileConfig
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpScriptBuilder
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.BatteryTarget
import io.github.mayusi.calibratesoc.data.autotdp.CurvePoint
import io.github.mayusi.calibratesoc.data.autotdp.CurveResult
import io.github.mayusi.calibratesoc.data.autotdp.EfficiencyCurveSweep
import io.github.mayusi.calibratesoc.data.autotdp.IdleChargeTrigger
import io.github.mayusi.calibratesoc.data.autotdp.PerAppEfficiencyMap
import io.github.mayusi.calibratesoc.data.autotdp.SweepProgress
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyAdvisor
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyPlan
import io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapability
import io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityProbe
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [AutoTdpScreen].
 *
 * Owns:
 *  - Rung decision (LIVE / SCRIPT / ADVISORY) derived from CapabilityReport + AutoTdpRunState.
 *  - Profile picker state and Battery-Target mode input.
 *  - Efficiency curve sweep orchestration (step-cap + collect points).
 *  - Script generation + deploy routing for the SCRIPT rung.
 *  - Companion toggles: idle/charge trigger opt-in, per-app map exposure.
 *  - **Trigger wiring**: observes [IdleChargeTrigger.requestedProfile] and the per-app
 *    map snapshot; resolves precedence; calls [AutoTdpController.start]/[stop].
 *
 * Trigger precedence (highest → lowest):
 *  1. Manual ON — the user explicitly turned AutoTDP on from the UI. Triggers are ignored
 *     until the user turns it off or it dies via safety/write-denied.
 *  2. Per-app trigger — the latest known foreground app has a bound AutoTdpProfile.
 *     Applies only when manual is OFF and idle/charge is not demanding a floor.
 *  3. Idle/charge trigger — EFFICIENCY floor when screen-off or charging.
 *     Applies only when manual is OFF.
 *  4. Idle (nothing) — controller stays IDLE / STOPPED.
 *
 * The trigger wiring is intentionally in the ViewModel (not a separate service) because:
 *  - The VM lives as long as the screen; on first launch it starts the IdleChargeTrigger.
 *  - A persistent background wiring would need a separate service; we defer that to a
 *    future phase. This is the seam the design left intentionally.
 */
@HiltViewModel
class AutoTdpViewModel @Inject constructor(
    private val controller: AutoTdpController,
    private val capabilityProbe: CapabilityProbe,
    private val monitorService: MonitorService,
    private val sweepCoordinator: EfficiencyCurveSweep,
    private val scriptBuilder: AutoTdpScriptBuilder,
    private val scriptDeployer: AynScriptDeployer,
    private val advancedPermissionsScript: AdvancedPermissionsScript,
    private val idleChargeTrigger: IdleChargeTrigger,
    private val perAppEfficiencyMap: PerAppEfficiencyMap,
    private val userPrefs: UserPrefs,
    private val efficiencyAdvisor: EfficiencyAdvisor,
    private val undervoltCapabilityProbe: UndervoltCapabilityProbe,
) : ViewModel() {

    // ── Exposed state ─────────────────────────────────────────────────────────

    val runState: StateFlow<AutoTdpRunState> = controller.state

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /** Resolved rung: LIVE / SCRIPT / ADVISORY. Derived from capability + run state. */
    val rung: StateFlow<AutoTdpRung> = combine(
        capabilityProbe.report,
        controller.state,
    ) { report, state -> resolveRung(report, state) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AutoTdpRung.ADVISORY)

    // ── Profile picker ────────────────────────────────────────────────────────

    private val _selectedProfile = MutableStateFlow(AutoTdpProfile.BALANCED)
    val selectedProfile: StateFlow<AutoTdpProfile> = _selectedProfile.asStateFlow()

    /** Hours input for BATTERY_TARGET mode. */
    private val _targetHours = MutableStateFlow(3.0)
    val targetHours: StateFlow<Double> = _targetHours.asStateFlow()

    /** Result from BatteryTarget.capForTarget — computed when BATTERY_TARGET is selected. */
    private val _batteryTargetResult = MutableStateFlow<BatteryTarget.BatteryTargetResult?>(null)
    val batteryTargetResult: StateFlow<BatteryTarget.BatteryTargetResult?> = _batteryTargetResult.asStateFlow()

    // ── Efficiency Curve Finder ────────────────────────────────────────────────

    private val _sweepProgress = MutableStateFlow<SweepUiState>(SweepUiState.Idle)
    val sweepProgress: StateFlow<SweepUiState> = _sweepProgress.asStateFlow()

    /** The last successful knee result (fed into the profile as the calibrated cap). */
    private val _kneeKhz = MutableStateFlow<Int?>(null)
    val kneeKhz: StateFlow<Int?> = _kneeKhz.asStateFlow()

    // ── EfficiencyAdvisor plan ────────────────────────────────────────────────

    /**
     * Latest [EfficiencyPlan] from [EfficiencyAdvisor]. Null until the first
     * capability report is available. Updated whenever the sweep completes or
     * the capability report changes.
     */
    private val _efficiencyPlan = MutableStateFlow<EfficiencyPlan?>(null)
    val efficiencyPlan: StateFlow<EfficiencyPlan?> = _efficiencyPlan.asStateFlow()

    // ── UndervoltCapability tier ──────────────────────────────────────────────

    /**
     * Detected undervolt capability tier. Null until first capability report.
     * Drives honest UI disclosure (KNEE_EQUIVALENT vs REAL_VOLTAGE_TABLE vs READ_ONLY).
     */
    private val _undervoltCapability = MutableStateFlow<UndervoltCapability?>(null)
    val undervoltCapability: StateFlow<UndervoltCapability?> = _undervoltCapability.asStateFlow()

    // ── Script rung ───────────────────────────────────────────────────────────

    private val _lastScriptDeploy = MutableStateFlow<AynScriptDeployer.Deployed?>(null)
    val lastScriptDeploy: StateFlow<AynScriptDeployer.Deployed?> = _lastScriptDeploy.asStateFlow()

    // ── Unlock CTA (PServer whitelist deploy) ─────────────────────────────────

    /**
     * Result of the most recent Advanced Unlock script deploy.
     * Shown as a CTA card on AYN/Odin devices where PServer is present but
     * not yet whitelisted. After the user runs the script once, the next
     * [CapabilityProbe.refresh] will set [pserverSysfsLive=true] and the
     * rung banner will flip to LIVE.
     */
    private val _lastUnlockDeploy = MutableStateFlow<AdvancedPermissionsScript.Deployed?>(null)
    val lastUnlockDeploy: StateFlow<AdvancedPermissionsScript.Deployed?> = _lastUnlockDeploy.asStateFlow()

    /** Whether the PServer unlock CTA should be shown. Derived from capability. */
    val showPServerUnlockCta: StateFlow<Boolean> = capabilityProbe.report
        .map { shouldShowPServerUnlockCta(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Idle/charge trigger toggle ─────────────────────────────────────────────

    val idleChargeTriggerEnabled: StateFlow<Boolean> = userPrefs.idleChargeTriggerEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Per-app map ───────────────────────────────────────────────────────────

    val perAppMap: StateFlow<Map<String, AutoTdpProfile>> = perAppEfficiencyMap.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Manual-control tracking ───────────────────────────────────────────────

    /**
     * True when the user explicitly turned AutoTDP ON via the UI toggle.
     * While true, trigger signals are ignored (manual precedence wins).
     */
    private val _manuallyOn = MutableStateFlow(false)
    val manuallyOn: StateFlow<Boolean> = _manuallyOn.asStateFlow()

    private var sweepJob: Job? = null
    private var latestTelemetry: io.github.mayusi.calibratesoc.data.monitor.Telemetry? = null

    // ── Live telemetry snapshot for "Live now" mini-grid ─────────────────────

    /**
     * Compact snapshot of the fields shown in the "Live now" mini-grid inside
     * [RunningStateContent]. All fields are nullable; the UI renders "–" for nulls.
     *
     * [cpuMaxMhz]   — max frequency across all online cores (kHz → MHz).
     * [gpuMhz]      — GPU frequency in MHz (Hz / 1_000_000). Null when unavailable.
     * [hottestTempC] — hottest thermal zone in °C (milliC / 1000). Null when no zones.
     * [batteryDrawW] — instantaneous battery draw in W (mW / 1000.0). Null when unavailable.
     */
    data class LiveSnapshot(
        val cpuMaxMhz: Int?,
        val gpuMhz: Int?,
        val hottestTempC: Int?,
        val batteryDrawW: Double?,
    )

    private val _liveSnapshot = MutableStateFlow<LiveSnapshot?>(null)
    /** Latest live telemetry snapshot. Null until the first telemetry sample arrives. */
    val liveSnapshot: StateFlow<LiveSnapshot?> = _liveSnapshot.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    //  Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    init {
        // Start the idle/charge trigger listener so it emits screen-off/charge events.
        idleChargeTrigger.start()

        // Subscribe to trigger signals and apply them when manual is OFF.
        idleChargeTrigger.requestedProfile
            .onEach { requested -> applyTriggerSignal(requested) }
            .launchIn(viewModelScope)

        // Keep a rolling latest telemetry pointer (used by EfficiencyCurveSweep
        // and BatteryTarget computation) without a second parallel collector.
        // Also extract the live snapshot for the "Live now" mini-grid.
        viewModelScope.launch {
            monitorService.telemetry().collect { t ->
                latestTelemetry = t
                _liveSnapshot.value = buildLiveSnapshot(t)
            }
        }

        // Reflect LIVE_UNAVAILABLE → drop manuallyOn so the toggle resets.
        controller.state
            .onEach { state ->
                if (state.status == AutoTdpStatus.LIVE_UNAVAILABLE ||
                    state.status == AutoTdpStatus.STOPPED ||
                    state.status == AutoTdpStatus.KILLED_BY_SAFETY ||
                    state.status == AutoTdpStatus.WRITE_DENIED
                ) {
                    _manuallyOn.value = false
                }
            }
            .launchIn(viewModelScope)

        // Build the EfficiencyAdvisor plan whenever the capability report arrives.
        // Also probe the undervolt capability tier on first report.
        capabilityProbe.report
            .onEach { report ->
                if (report != null) {
                    val caps = TdpCaps.from(report)
                    val freqCapWritable = report.sysfsDirectlyWritable || report.pserverSysfsLive ||
                        report.privilege == PrivilegeTier.ROOT
                    viewModelScope.launch(Dispatchers.IO) {
                        val uvCap = undervoltCapabilityProbe.probe(freqCapWritable)
                        _undervoltCapability.value = uvCap
                        // Build initial plan (no curve yet)
                        _efficiencyPlan.value = efficiencyAdvisor.buildPlan(
                            report = report,
                            caps = caps,
                            curveResult = null,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        // Stop listening for screen events when UI is gone.
        idleChargeTrigger.stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Manual toggle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when the user taps the main On/Off toggle (LIVE rung only).
     * Sets manual-precedence so triggers cannot override while ON.
     */
    fun setManualEnabled(on: Boolean) {
        _manuallyOn.value = on
        if (on) {
            val report = capabilityProbe.report.value ?: return
            val caps = TdpCaps.from(report)
            val config = buildConfig(caps)
            controller.start(config)
        } else {
            controller.stop()
        }
    }

    fun selectProfile(profile: AutoTdpProfile) {
        _selectedProfile.value = profile
        // Re-compute battery target preview when profile changes to/from BATTERY_TARGET
        if (profile == AutoTdpProfile.BATTERY_TARGET) {
            computeBatteryTarget()
        }
    }

    fun setTargetHours(hours: Double) {
        _targetHours.value = hours
        if (_selectedProfile.value == AutoTdpProfile.BATTERY_TARGET) {
            computeBatteryTarget()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Battery Target computation
    // ─────────────────────────────────────────────────────────────────────────

    private fun computeBatteryTarget() {
        viewModelScope.launch {
            val report = capabilityProbe.report.value ?: return@launch
            val caps = TdpCaps.from(report)
            val t = latestTelemetry ?: return@launch
            val drawMw = t.batteryDrawMilliW ?: return@launch
            val voltMv = (t.batteryVoltageUv ?: 0L).let { (it / 1000L).toInt() }
            if (voltMv <= 0) return@launch
            // BatteryManager BATTERY_PROPERTY_CHARGE_COUNTER is not available here without
            // context injection; we use 3000 mAh as a fallback placeholder. The real value
            // requires BatteryManager access which the service tier should inject.
            // TODO: inject BatteryManager or BatteryEstimate to get real remaining mAh.
            val remainingMah = 3000
            val result = BatteryTarget.capForTarget(
                targetHours = _targetHours.value,
                remainingCapacityMah = remainingMah,
                batteryVoltageMv = voltMv,
                currentDrawMw = drawMw,
                caps = caps,
            )
            _batteryTargetResult.value = result
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Efficiency Curve Finder
    // ─────────────────────────────────────────────────────────────────────────

    fun startEfficiencySweep() {
        if (sweepJob?.isActive == true) return
        val report = capabilityProbe.report.value ?: run {
            _sweepProgress.value = SweepUiState.Failed("No capability report — probe the device first.")
            return
        }
        val caps = TdpCaps.from(report)
        if (caps.bigClusterOppStepsKhz.isEmpty()) {
            _sweepProgress.value = SweepUiState.Failed("No OPP steps found for the big cluster.")
            return
        }
        _sweepProgress.value = SweepUiState.Running(stepIndex = 0, totalSteps = caps.bigClusterOppStepsKhz.size, currentCapKhz = 0)

        sweepJob = viewModelScope.launch {
            sweepCoordinator.sweep(
                caps = caps,
                telemetrySource = { latestTelemetry },
            ).collect { progress ->
                when (progress) {
                    is SweepProgress.Measuring -> _sweepProgress.value = SweepUiState.Running(
                        stepIndex = progress.stepIndex,
                        totalSteps = progress.totalSteps,
                        currentCapKhz = progress.capKhz,
                    )
                    is SweepProgress.PointCollected -> Unit // running state already shown
                    is SweepProgress.Finished -> {
                        _sweepProgress.value = SweepUiState.Done(progress.result)
                        _kneeKhz.value = progress.result.knee?.capKhz
                        // Refresh the EfficiencyAdvisor plan with the measured curve result.
                        val report = capabilityProbe.report.value
                        if (report != null) {
                            val caps = TdpCaps.from(report)
                            _efficiencyPlan.value = efficiencyAdvisor.buildPlan(
                                report = report,
                                caps = caps,
                                curveResult = progress.result,
                            )
                        }
                    }
                    is SweepProgress.Aborted -> {
                        _sweepProgress.value = SweepUiState.Failed(progress.reason)
                    }
                }
            }
        }
    }

    fun cancelSweep() {
        sweepJob?.cancel()
        sweepJob = null
        _sweepProgress.value = SweepUiState.Idle
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SCRIPT rung: generate + deploy
    // ─────────────────────────────────────────────────────────────────────────

    fun generateEfficiencyScript() {
        viewModelScope.launch {
            val report = capabilityProbe.report.value ?: return@launch
            val caps = TdpCaps.from(report)
            val script = scriptBuilder.buildEfficiencyScript(
                caps = caps,
                profile = _selectedProfile.value,
                report = report,
                kneeKhz = _kneeKhz.value,
            )
            val preset = scriptBuilder.buildEfficiencyPreset(
                caps = caps,
                profile = _selectedProfile.value,
                report = report,
                kneeKhz = _kneeKhz.value,
            )
            val deployed = scriptDeployer.deploy(preset, script)
            _lastScriptDeploy.value = deployed
        }
    }

    fun clearLastScriptDeploy() {
        _lastScriptDeploy.value = null
    }

    // ── Unlock CTA actions ────────────────────────────────────────────────────

    /**
     * Deploys the Advanced Unlock script (PServer whitelist + chmod) and
     * surfaces the result so the UI can guide the user to run it once.
     * After the user runs the script, they should tap "Check again" to
     * trigger [CapabilityProbe.refresh] and update the rung.
     */
    fun deployUnlockScript() {
        viewModelScope.launch {
            val deployed = withContext(Dispatchers.IO) {
                advancedPermissionsScript.deploy()
            }
            _lastUnlockDeploy.value = deployed
        }
    }

    fun clearUnlockDeploy() {
        _lastUnlockDeploy.value = null
    }

    /**
     * Re-runs the capability probe so the rung recalculates immediately
     * after the user runs the unlock script.
     */
    fun refreshCapability() {
        viewModelScope.launch {
            capabilityProbe.refresh()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Companion toggles
    // ─────────────────────────────────────────────────────────────────────────

    fun setIdleChargeTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPrefs.setIdleChargeTriggerEnabled(enabled)
        }
    }

    fun setPerAppProfile(packageName: String, profile: AutoTdpProfile?) {
        viewModelScope.launch {
            perAppEfficiencyMap.setProfileForApp(packageName, profile)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Trigger wiring (integration glue — Component 7 + 8 seam)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called whenever the idle/charge trigger emits a new signal.
     * Respects manual-precedence: if the user explicitly turned AutoTDP ON,
     * the trigger is silenced.
     */
    private fun applyTriggerSignal(requestedConfig: AutoTdpProfileConfig?) {
        // Manual ON wins: ignore trigger until user turns off.
        if (_manuallyOn.value) return

        val currentStatus = controller.state.value.status
        if (requestedConfig != null) {
            // Trigger wants EFFICIENCY floor. Start if not already running with this config.
            if (currentStatus != AutoTdpStatus.RUNNING) {
                controller.start(requestedConfig)
            }
        } else {
            // Trigger is inactive. Stop if we were trigger-started (not manually started).
            if (currentStatus == AutoTdpStatus.RUNNING) {
                controller.stop()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLiveSnapshot(t: Telemetry): LiveSnapshot {
        val cpuMaxMhz = t.perCoreCpuFreqKhz.maxOrNull()?.let { it / 1000 }
        val gpuMhz = t.gpuFreqHz?.let { (it / 1_000_000L).toInt() }
        val hottestTempC = t.zoneTempsMilliC.maxByOrNull { it.tempMilliC }?.let { it.tempMilliC / 1000 }
        val batteryDrawW = t.batteryDrawMilliW?.let { it / 1000.0 }
        return LiveSnapshot(
            cpuMaxMhz = cpuMaxMhz,
            gpuMhz = gpuMhz,
            hottestTempC = hottestTempC,
            batteryDrawW = batteryDrawW,
        )
    }

    private fun buildConfig(caps: TdpCaps): AutoTdpProfileConfig {
        val profile = _selectedProfile.value
        val targetMw = if (profile == AutoTdpProfile.BATTERY_TARGET) {
            _batteryTargetResult.value?.mappedCapKhz?.let { capKhz ->
                // Translate OPP cap back to a mW budget estimate
                val fraction = capKhz.toDouble() / (caps.bigClusterOppStepsKhz.maxOrNull() ?: 1).toDouble()
                val currentDraw = latestTelemetry?.batteryDrawMilliW ?: 5000L
                (fraction * currentDraw).toLong()
            }
        } else null
        return AutoTdpProfileConfig(profile = profile, targetMilliWatts = targetMw)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Pure static helpers (testable without Android)
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Pure rung-resolution logic. Determines which AutoTDP rung is active
         * based on the capability report and current run state.
         *
         * LIVE  : sysfs is directly writable (unlock script ran), OR device has ROOT tier,
         *         OR PServer SYSFS live writes are confirmed ([CapabilityReport.pserverSysfsLive]).
         *         PServer is the preferred no-root live path on AYN/Odin devices: after the
         *         one-time whitelist step, it writes every tick with root authority,
         *         no per-boot chmod needed. VERIFIED on Odin 3 (36.5% draw cut, 53% w/ core-park).
         * SCRIPT: no live writes possible but script generation is available (AYN_SETTINGS,
         *         SHIZUKU, or NONE — all can generate and run a script externally).
         * ADVISORY: fallback — no writes at all; monitor + advice only.
         *
         * Note: LIVE_UNAVAILABLE from the service means the daemon itself confirmed it
         * cannot write even though the probe said it should. We honour the daemon's verdict
         * and route to SCRIPT/ADVISORY.
         */
        fun resolveRung(report: CapabilityReport?, state: AutoTdpRunState): AutoTdpRung {
            if (report == null) return AutoTdpRung.ADVISORY
            // Daemon confirmed live writes not available for this session.
            if (state.status == AutoTdpStatus.LIVE_UNAVAILABLE) return AutoTdpRung.SCRIPT
            val liveWritable = report.privilege == PrivilegeTier.ROOT ||
                report.sysfsDirectlyWritable ||
                report.pserverSysfsLive
            return when {
                liveWritable -> AutoTdpRung.LIVE
                // NONE / SHIZUKU / AYN_SETTINGS can all generate a script and run via vendor app.
                else -> AutoTdpRung.SCRIPT
            }
        }

        /**
         * Returns true when this device *could* reach the LIVE rung via PServer after
         * the one-time unlock, but has not yet done so.
         *
         * Condition: AYN/Odin vendor app present (confirms PServer likely exists on this
         * device) AND PServer is NOT yet whitelisted for us (pserverSysfsLive == false)
         * AND we're not already live via another path (root or direct sysfs write).
         *
         * When this returns true, the UI should show the unlock CTA card so the user
         * knows one script run will elevate them to LIVE with no root.
         */
        fun shouldShowPServerUnlockCta(report: CapabilityReport?): Boolean {
            if (report == null) return false
            // Already LIVE by some path — no CTA needed.
            if (report.privilege == PrivilegeTier.ROOT ||
                report.sysfsDirectlyWritable ||
                report.pserverSysfsLive
            ) return false
            // Show CTA only on devices where PServer is plausibly present.
            // langerhansOdinTools detects the OdinTools app (strong signal for Odin family).
            // aynGameAssistant covers the AYN game-assistant lineup.
            return report.vendorApps.aynGameAssistant || report.vendorApps.langerhansOdinTools
        }

        /**
         * Ranks OPP sweep points by perf-per-watt and returns them with a
         * normalised ppW fraction (0.0–1.0 relative to the best point in the list).
         *
         * Pure function — no Android, fully unit-testable.
         *
         * @param points   The raw [CurvePoint] list from a [CurveResult]. May be empty.
         * @param kneeKhz  The knee cap (from [CurveResult.knee]); used to flag the
         *                 "best" bar separately from the normalisation.
         * @return List of [RankedOppPoint] in the same order as [points] (ascending capKhz).
         *         Empty when [points] is empty.
         */
        fun rankOppPoints(
            points: List<CurvePoint>,
            kneeKhz: Int?,
        ): List<RankedOppPoint> {
            if (points.isEmpty()) return emptyList()
            val maxPpw = points.maxOfOrNull { it.perfPerWatt } ?: 0.0
            return points.map { p ->
                RankedOppPoint(
                    capMhz = p.capKhz / 1000,
                    drawMw = p.drawMw,
                    perfPerWatt = p.perfPerWatt,
                    ppwFraction = if (maxPpw > 0.0) (p.perfPerWatt / maxPpw).toFloat()
                                  else 0f,
                    isKnee = p.capKhz == kneeKhz,
                )
            }
        }

        /**
         * Resolve which [AutoTdpProfileConfig] the controller should be running,
         * given the current manual state and trigger signals. Pure — used in tests.
         *
         * @param manuallyOn       Whether the user explicitly turned AutoTDP on.
         * @param manualConfig     The config to use when [manuallyOn] is true.
         * @param idleChargeSignal Signal from [IdleChargeTrigger.requestedProfile].
         * @return The config to apply, or null for "stop / stay idle".
         */
        fun resolveTriggerPrecedence(
            manuallyOn: Boolean,
            manualConfig: AutoTdpProfileConfig?,
            idleChargeSignal: AutoTdpProfileConfig?,
        ): AutoTdpProfileConfig? {
            if (manuallyOn) return manualConfig
            return idleChargeSignal // may be null (idle)
        }
    }
}

/** The AutoTDP rung active on this device for this session. */
enum class AutoTdpRung {
    /** Sysfs is writable: real closed-loop daemon. */
    LIVE,
    /** No live writes: generate a static efficiency script via the existing deploy flow. */
    SCRIPT,
    /** Zero privilege: monitor + advice only. */
    ADVISORY,
}

/** UI state for the efficiency curve sweep. */
sealed interface SweepUiState {
    data object Idle : SweepUiState
    data class Running(val stepIndex: Int, val totalSteps: Int, val currentCapKhz: Int) : SweepUiState
    data class Done(val result: CurveResult) : SweepUiState
    data class Failed(val reason: String) : SweepUiState
}

/**
 * A single OPP step annotated for the efficiency curve bar chart.
 *
 * [capMhz]      — big-cluster cap in MHz (display label on left).
 * [drawMw]      — measured mean draw in mW.
 * [perfPerWatt] — raw perf/W value (used for tooltip / sub-label).
 * [ppwFraction] — normalised 0.0–1.0 relative to the best point; drives bar width.
 * [isKnee]      — true for the knee/best-efficiency point (rendered in emerald).
 */
data class RankedOppPoint(
    val capMhz: Int,
    val drawMw: Long,
    val perfPerWatt: Double,
    val ppwFraction: Float,
    val isKnee: Boolean,
)
