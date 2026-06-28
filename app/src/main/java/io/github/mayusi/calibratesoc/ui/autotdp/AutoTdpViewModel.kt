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
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.ChargingBundle
import io.github.mayusi.calibratesoc.data.autotdp.ChargingBundlePrefs
import io.github.mayusi.calibratesoc.data.autotdp.ChargingTuneTrigger
import io.github.mayusi.calibratesoc.data.autotdp.IdleChargeTrigger
import io.github.mayusi.calibratesoc.data.autotdp.PerAppEfficiencyMap
import io.github.mayusi.calibratesoc.data.autotdp.SweepProgress
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyAdvisor
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyPlan
import io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapability
import io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityProbe
import io.github.mayusi.calibratesoc.data.monitor.BatteryChargeReader
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val chargingTuneTrigger: ChargingTuneTrigger,
    private val chargingBundlePrefs: ChargingBundlePrefs,
    private val perAppEfficiencyMap: PerAppEfficiencyMap,
    private val batteryChargeReader: BatteryChargeReader,
    private val userPrefs: UserPrefs,
    private val efficiencyAdvisor: EfficiencyAdvisor,
    private val undervoltCapabilityProbe: UndervoltCapabilityProbe,
    private val writerRegistry: WriterRegistry,
) : ViewModel() {

    // ── Exposed state ─────────────────────────────────────────────────────────

    val runState: StateFlow<AutoTdpRunState> = controller.state

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /** Resolved rung: LIVE / SCRIPT / ADVISORY. Derived from capability + run state.
     *
     *  The "is this LIVE?" decision is delegated to [WriterRegistry.isLiveWritable]
     *  against the ACTUAL prime-cluster cpufreq node — the SAME single source of
     *  truth the AutoTdpService daemon uses ([AutoTdpService.liveUnavailableReason]).
     *  This is what lets a Shizuku-only device (no root, no PServer, no chmod) reach
     *  LIVE when the per-node write-probe confirmed shell can write scaling_max_freq,
     *  the key generalization for AYANEO / GPD / any no-root handheld. */
    val rung: StateFlow<AutoTdpRung> = combine(
        capabilityProbe.report,
        controller.state,
    ) { report, state -> resolveRung(report, state, primeFreqLiveWritable(report)) }
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

    /**
     * True when the device does not expose a readable battery charge counter
     * (BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER returned -1/unavailable), so
     * the required-cap preview cannot be computed from a REAL remaining-capacity.
     * The UI MUST surface this as an honest "estimate unavailable" note instead of
     * showing a number backed by a fabricated constant.
     */
    private val _batteryCapacityUnavailable = MutableStateFlow(false)
    val batteryCapacityUnavailable: StateFlow<Boolean> = _batteryCapacityUnavailable.asStateFlow()

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

    /** Whether the PServer unlock CTA should be shown. Derived from capability.
     *  Now consistent with the generalized live check: a device that is already
     *  LIVE via ANY path (incl. Shizuku-probed) does not show the PServer CTA. */
    val showPServerUnlockCta: StateFlow<Boolean> = capabilityProbe.report
        .map { shouldShowPServerUnlockCta(it, primeFreqLiveWritable(it)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Vendor-neutral "how to reach LIVE" ladder, shown when AutoTDP is NOT live.
     * Ordered best-default-first: Grant Shizuku → Run unlock script → Enable root.
     * If a vendor binder path probes transactable (AYN PServer today), that rung
     * lights up automatically. Honest per-device: each rung carries whether it is
     * actually available/done on THIS device. Null when AutoTDP is already live.
     */
    val unlockLadder: StateFlow<UnlockLadder?> = capabilityProbe.report
        .map { report -> buildUnlockLadder(report, primeFreqLiveWritable(report)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Idle/charge trigger toggle ─────────────────────────────────────────────

    val idleChargeTriggerEnabled: StateFlow<Boolean> = userPrefs.idleChargeTriggerEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Charging auto-profile ─────────────────────────────────────────────────

    /** Master opt-in for the charging auto-profile (Component: ChargingTuneTrigger). */
    val chargingProfileEnabled: StateFlow<Boolean> = chargingBundlePrefs.chargingProfileEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The currently-configured charging bundle (goal + fan + refresh rate). */
    val chargingBundle: StateFlow<ChargingBundle> = chargingBundlePrefs.bundle
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChargingBundle.DEFAULT)

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

    /**
     * True while the async capability refresh triggered by the START button
     * is in flight. Prevents double-taps and drives the "Starting…" button label.
     */
    private val _startingUp = MutableStateFlow(false)
    val startingUp: StateFlow<Boolean> = _startingUp.asStateFlow()

    /**
     * Non-null when the last START attempt failed before the daemon could
     * be launched (e.g. capability probe returned no CPU policies).
     * The UI surfaces this as a persistent notice. Cleared when the user
     * presses START again or when the daemon transitions to RUNNING.
     */
    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    /** Ensures only one concurrent startManual coroutine runs at a time. */
    private val startMutex = Mutex()

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

        // Start the charging auto-profile trigger (fan + refresh + goal on plug-in).
        chargingTuneTrigger.start()

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
        // Also propagate abort notices via _startError for FIX 4.
        controller.state
            .onEach { state ->
                when (state.status) {
                    AutoTdpStatus.RUNNING -> {
                        // Daemon started successfully — clear any lingering start error.
                        _startError.value = null
                    }
                    AutoTdpStatus.LIVE_UNAVAILABLE -> {
                        _manuallyOn.value = false
                        // Surface the daemon's PServer unavailability reason (FIX 4).
                        _startError.value =
                            "AutoTDP stopped: live kernel writes weren't available" +
                            (state.liveUnavailableReason?.let { " — $it" } ?: "") +
                            ". Re-run Advanced unlock or try again."
                    }
                    AutoTdpStatus.KILLED_BY_SAFETY -> {
                        _manuallyOn.value = false
                        // Surface the thermal/battery kill reason (FIX 4).
                        _startError.value =
                            "AutoTDP stopped: device hit its thermal/battery safety limit" +
                            (state.killReason?.let { " ($it)" } ?: "") +
                            ". Let it cool, then restart."
                    }
                    AutoTdpStatus.WRITE_DENIED -> {
                        _manuallyOn.value = false
                        // Surface the write-denied detail (FIX 4).
                        _startError.value =
                            "AutoTDP stopped: a kernel write was denied" +
                            (state.writeFailure?.let { " — $it" } ?: "") +
                            ". Try the unlock script or root."
                    }
                    AutoTdpStatus.STOPPED -> {
                        _manuallyOn.value = false
                        // User-requested stop — no error notice needed.
                    }
                    AutoTdpStatus.IDLE -> Unit
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
        // Stop the charging auto-profile trigger (reverts bundle under NonCancellable).
        chargingTuneTrigger.stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Manual toggle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when the user taps the main On/Off toggle (LIVE rung only).
     * Sets manual-precedence so triggers cannot override while ON.
     *
     * FIX 1: if the capability report hasn't been probed yet (user opened the
     * AutoTDP screen very fast), this used to silently `return` and leave the
     * daemon never started while `_manuallyOn` was already flipped to true.
     *
     * Fixed: when `report` is null we refresh it asynchronously under a mutex
     * (prevents double-tap races), show a "Starting…" state during the probe,
     * and only proceed to `controller.start` when a usable report arrives.
     * If the refresh still yields no CPU policies we surface an error notice.
     */
    fun setManualEnabled(
        on: Boolean,
        goal: GoalProfile? = null,
        targetMilliWatts: Long? = null,
    ) {
        if (!on) {
            _manuallyOn.value = false
            _startingUp.value = false
            _startError.value = null
            controller.stop()
            return
        }

        // Fast path: report already cached.
        val cached = capabilityProbe.report.value
        if (cached != null) {
            _manuallyOn.value = true
            _startError.value = null
            val caps = TdpCaps.from(cached)
            val config = buildConfig(caps, goal, targetMilliWatts)
            controller.start(config)
            return
        }

        // Slow path: report not yet available — refresh first, then start.
        // The mutex prevents a second tap from launching a parallel probe.
        viewModelScope.launch {
            if (!startMutex.tryLock()) return@launch   // already probing
            _startingUp.value = true
            _manuallyOn.value = true
            _startError.value = null
            try {
                val report = capabilityProbe.refresh()
                val caps = TdpCaps.from(report)
                if (caps.bigPolicyId < 0 && caps.bigClusterOppStepsKhz.isEmpty()) {
                    // No usable CPU policies — cannot start the daemon honestly.
                    _manuallyOn.value = false
                    _startError.value =
                        "AutoTDP could not start: no CPU policies found after probing this device. " +
                        "Check that the capability report is not empty."
                    return@launch
                }
                val config = buildConfig(caps, goal, targetMilliWatts)
                controller.start(config)
            } catch (t: Throwable) {
                _manuallyOn.value = false
                _startError.value =
                    "AutoTDP could not start: capability probe failed (${t.message}). Try again."
            } finally {
                _startingUp.value = false
                startMutex.unlock()
            }
        }
    }

    /** Clears the persistent start-error notice. */
    fun clearStartError() {
        _startError.value = null
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
        // Always recompute the preview: the input is now surfaced by the Battery-Saver
        // GOAL MODE (see AutoTdpScreen), not the removed legacy BATTERY_TARGET profile,
        // so we no longer gate the recompute on _selectedProfile.
        computeBatteryTarget()
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

            // Real remaining capacity from BatteryManager's charge counter (µAh → mAh).
            // HONESTY (FIX 2): this used to be a hardcoded 3000 mAh placeholder, which
            // made the REQUIRED CAP preview wrong on every device (an Odin ~5000 mAh got
            // a cap ~67% too low). We now read the device's real remaining charge. When
            // the device does not expose the counter (returns null), we DO NOT substitute
            // a fake constant — we flag the preview as unavailable so the UI can be honest.
            val remainingUah = batteryChargeReader.readChargeCounterUah()
            val remainingMah = remainingMahFromChargeCounter(remainingUah)
            if (remainingMah == null) {
                _batteryCapacityUnavailable.value = true
                _batteryTargetResult.value = null
                return@launch
            }
            _batteryCapacityUnavailable.value = false
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

    // ── Charging auto-profile setters ─────────────────────────────────────────

    fun setChargingProfileEnabled(enabled: Boolean) {
        viewModelScope.launch {
            chargingBundlePrefs.setChargingProfileEnabled(enabled)
        }
    }

    fun setChargingAutoTdpGoal(goal: GoalProfile) {
        viewModelScope.launch {
            chargingBundlePrefs.setAutoTdpGoal(goal)
        }
    }

    /** Pass null to disable fan control while charging. */
    fun setChargingFanMode(mode: Int?) {
        viewModelScope.launch {
            chargingBundlePrefs.setFanMode(mode)
        }
    }

    /** Pass null to leave display refresh rate untouched while charging. */
    fun setChargingRefreshRateHz(hz: Float?) {
        viewModelScope.launch {
            chargingBundlePrefs.setRefreshRateHz(hz)
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

    /**
     * Honest pre-start LIVE check: can AutoTDP's critical node families actually
     * be written on this device, via ANY live write path (root / Shizuku-probed /
     * PServer / unlock-chmod)?
     *
     * Delegates entirely to [WriterRegistry.isLiveWritable] — the single source of
     * truth that encodes the full tier-resolution logic INCLUDING the Shizuku
     * per-node probe cache. We deliberately mirror the EXACT node families the
     * [AutoTdpService] daemon gates on ([AutoTdpService.liveUnavailableReason]):
     *   - cpu0/online   (core-parking family proxy)
     *   - prime-cluster scaling_max_freq  (the big-cluster cap — AutoTDP's core lever)
     * so the rung the user sees BEFORE pressing START agrees with what the daemon
     * will actually do AFTER. A device where scaling_max_freq is writable but
     * cpu/online is not would self-stop with LIVE_UNAVAILABLE, so we honestly do
     * NOT show LIVE for it up front.
     *
     * Returns false when the report is null (not yet probed). This is what makes a
     * Shizuku-only device (no root, no PServer, no chmod) reach LIVE: when the
     * no-op write probe confirmed shell can write these exact nodes,
     * isLiveWritable routes them to ShizukuWriter (not NoopWriter) → true.
     */
    private fun primeFreqLiveWritable(report: CapabilityReport?): Boolean {
        if (report == null) return false
        // Core-parking family proxy: cpu0/online tells us whether the cpu/online
        // family is writable at all (cpu0 itself is never parked).
        //
        // EXCEPTION — AYANEO vendor-binder live path: the binder cannot drive cpu/online
        // (no core-parking command), only the CPU cluster CAP, governor, GPU max, and fan.
        // AutoTDP runs LIVE on the cap path (the engine skips the park lever when no core is
        // parkable), so on a binder-live AYANEO the cap check alone gates LIVE. This MUST
        // mirror AutoTdpService.liveUnavailableReason so the rung shown before START agrees
        // with what the daemon does after.
        if (!report.ayaneoBinderLive) {
            val onlineId = Tunables.cpuOnline(0)
            if (!writerRegistry.isLiveWritable(onlineId, report)) return false
        }
        // HIGH-3: gate on the EXACT node the daemon actuates — cpuMaxFreq(caps.bigPolicyId),
        // derived from the SAME TdpCaps the engine uses — NOT maxByOrNull{availableFreqsKhz}
        // (which selects prime policy7 on a 3-cluster AYANEO while the cap is written to the
        // gold policy3). Kept symmetric with AutoTdpService.liveUnavailableReason so the rung
        // shown before START agrees with the node the daemon writes after.
        val caps = io.github.mayusi.calibratesoc.data.autotdp.TdpCaps.from(report)
        if (report.cpuPolicies.isNotEmpty()) {
            val freqId = Tunables.cpuMaxFreq(caps.bigPolicyId)
            if (!writerRegistry.isLiveWritable(freqId, report)) return false
        }
        return true
    }

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

    /**
     * Build the daemon start config.
     *
     * Smart path (Wave 4b wiring fix): when [goal] is non-null, the user picked a GOAL
     * MODE chip, so build the config via [AutoTdpProfileConfig.forGoal] — that carries
     * the 5-mode [GoalProfile] (incl. AUTO) end-to-end to the engine's `goalOverride`,
     * which is the ONLY path that reaches the Smart band controller / AUTO classifier.
     * (Before this, START always built a legacy profile config, so the daemon ran
     * BALANCED regardless of the selected goal.)
     *
     * The watts ceiling is honoured for any goal that carries a hard power ceiling
     * ([GoalProfile.hasHardPowerCeiling], i.e. BATTERY_SAVER): we pass through the
     * explicit [targetMilliWatts] when supplied, else derive one from the battery-target
     * preview the same way the legacy BATTERY_TARGET path did.
     *
     * Legacy path: when [goal] is null we fall back to the old profile-driven config
     * (kept for any caller that has not migrated, e.g. trigger-driven starts).
     */
    private fun buildConfig(
        caps: TdpCaps,
        goal: GoalProfile? = null,
        targetMilliWatts: Long? = null,
    ): AutoTdpProfileConfig {
        // Derive a watts budget from the battery-target preview (legacy behaviour).
        val derivedBudgetMw: Long? = _batteryTargetResult.value?.mappedCapKhz?.let { capKhz ->
            val fraction = capKhz.toDouble() / (caps.bigClusterOppStepsKhz.maxOrNull() ?: 1).toDouble()
            val currentDraw = latestTelemetry?.batteryDrawMilliW ?: 5000L
            (fraction * currentDraw).toLong()
        }

        if (goal != null) {
            // Only goals with a hard power ceiling consume a watts budget.
            val budget = if (goal.hasHardPowerCeiling) (targetMilliWatts ?: derivedBudgetMw) else null
            return AutoTdpProfileConfig.forGoal(goal, budget)
        }

        // Legacy profile-driven path (no Smart goal selected).
        val profile = _selectedProfile.value
        val targetMw = if (profile == AutoTdpProfile.BATTERY_TARGET) derivedBudgetMw else null
        return AutoTdpProfileConfig(profile = profile, targetMilliWatts = targetMw)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Pure static helpers (testable without Android)
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Pure rung-resolution logic. Determines which AutoTDP rung is active
         * based on the capability report, current run state, and whether the
         * prime-cluster cpufreq node is actually live-writable.
         *
         * LIVE  : at least one write path can VERIFIABLY write the prime-cluster
         *         scaling_max_freq (+ the cpu/online family) on this device —
         *         [primeFreqLiveWritable], computed via [WriterRegistry.isLiveWritable].
         *         This is vendor-AGNOSTIC: it is true for ROOT, the unlock-chmod
         *         direct-sysfs tier, the AYN PServer tier, AND a Shizuku-only device
         *         whose per-node write-probe confirmed shell can write these exact
         *         nodes. The Shizuku case is the key generalization — a no-root
         *         AYANEO / GPD / generic Android handheld now reaches LIVE with no
         *         vendor binder at all. (PServer is just an optimization on top.)
         * SCRIPT: no live write path for the prime cap, but script generation is
         *         available (any tier can generate + run a script externally).
         * ADVISORY: fallback — report not yet available; monitor + advice only.
         *
         * Note: LIVE_UNAVAILABLE from the service means the daemon itself confirmed it
         * cannot write even though the probe said it should. We honour the daemon's verdict
         * and route to SCRIPT.
         *
         * @param primeFreqLiveWritable The honest live-writability of the prime cpufreq
         *   node, computed by the instance helper from [WriterRegistry.isLiveWritable]
         *   so this pure function stays testable without injecting the registry.
         */
        fun resolveRung(
            report: CapabilityReport?,
            state: AutoTdpRunState,
            primeFreqLiveWritable: Boolean,
        ): AutoTdpRung {
            if (report == null) return AutoTdpRung.ADVISORY
            // Daemon confirmed live writes not available for this session.
            if (state.status == AutoTdpStatus.LIVE_UNAVAILABLE) return AutoTdpRung.SCRIPT
            return when {
                primeFreqLiveWritable -> AutoTdpRung.LIVE
                // NONE / SHIZUKU(unprobed/denied) / VENDOR_SETTINGS can all still
                // generate a script and run it via the vendor app / root-script runner.
                else -> AutoTdpRung.SCRIPT
            }
        }

        /**
         * Returns true when this device *could* reach the LIVE rung via the AYN
         * PServer binder after the one-time unlock, but has not yet done so.
         *
         * Condition: AYN/Odin vendor app present (confirms PServer likely exists on this
         * device) AND PServer is NOT yet whitelisted for us (pserverSysfsLive == false)
         * AND we're not already live via ANY path ([primeFreqLiveWritable] — which now
         * includes the Shizuku-probed path, so a Shizuku-live AYN device won't be nagged
         * to unlock PServer it doesn't need).
         *
         * This drives the AYN-specific PServer rung of the vendor-neutral unlock ladder.
         * The ladder itself ([buildUnlockLadder]) is shown on all vendors.
         *
         * @param primeFreqLiveWritable Honest live-writability of the prime cpufreq node
         *   (from [WriterRegistry.isLiveWritable]).
         */
        fun shouldShowPServerUnlockCta(
            report: CapabilityReport?,
            primeFreqLiveWritable: Boolean,
        ): Boolean {
            if (report == null) return false
            // Already LIVE by some path (root / Shizuku-probed / direct sysfs / PServer)
            // — no PServer CTA needed.
            if (primeFreqLiveWritable) return false
            // Show CTA only on devices where PServer is plausibly present.
            // langerhansOdinTools detects the OdinTools app (strong signal for Odin family).
            // aynGameAssistant covers the AYN game-assistant lineup.
            return report.vendorApps.aynGameAssistant || report.vendorApps.langerhansOdinTools
        }

        /**
         * Builds the vendor-neutral "how to reach LIVE" ladder. Returns null when
         * AutoTDP is already live (no ladder needed) or the report isn't ready.
         *
         * The ladder is ordered best-default-first and is HONEST per-device — each
         * rung carries whether it is available / already done on THIS device:
         *
         *   1. SHIZUKU  — Grant Shizuku [no root, no reboot — the best default].
         *                 Marked DONE when Shizuku is bound + granted. (On a device
         *                 where the node-probe then confirms writes, AutoTDP is
         *                 already live and this whole ladder is hidden.)
         *   2. UNLOCK_SCRIPT — Run the unlock script once via the device's root-script
         *                 runner. Grants the app's perms + (on AYN/Odin) the PServer
         *                 whitelist — works on Enforcing SELinux, NO Permissive needed.
         *                 Available wherever a vendor settings "Run script as Root" path
         *                 exists; the AYN PServer whitelist is the strongest form of this.
         *                 (Force SELinux / Permissive is NOT part of this rung — it's a
         *                 separate last resort that can break emulators, never pushed here.)
         *   3. ROOT     — Enable root mode (Magisk/KernelSU) [full, vendor-independent].
         *                 Marked AVAILABLE when root is present but not opted-in.
         *
         * Plus the vendor binder path (AYN PServer) lights up automatically when it
         * probes transactable — surfaced via [shouldShowPServerUnlockCta], which is
         * the same script as rung 2 on AYN.
         *
         * This is intentionally pure (no Android) so it is unit-testable.
         */
        fun buildUnlockLadder(
            report: CapabilityReport?,
            primeFreqLiveWritable: Boolean,
        ): UnlockLadder? {
            if (report == null) return null
            // Already live by some path — no ladder.
            if (primeFreqLiveWritable) return null

            val shizukuDone = report.shizuku.running && report.shizuku.permissionGranted
            val shizukuStep = UnlockStep(
                kind = UnlockStepKind.SHIZUKU,
                state = when {
                    // Granted but the node-probe didn't confirm writes (vendor SELinux
                    // denial) — Shizuku is bound yet this device blocks shell cpufreq
                    // writes, so it is NOT a live path here. Mark BLOCKED honestly.
                    shizukuDone -> UnlockStepState.DONE_BUT_INSUFFICIENT
                    report.shizuku.installed -> UnlockStepState.AVAILABLE
                    else -> UnlockStepState.AVAILABLE
                },
            )

            // The unlock-script path exists wherever a vendor "Run script as Root"
            // runner is plausibly present. We light it on any vendor handheld OR a
            // device exposing a known handheld key; on a fully-generic device it is
            // still offered but flagged as requiring an external root-script runner.
            val hasVendorScriptRunner = report.vendorApps.anyVendorPerfApp ||
                report.vendorApps.langerhansOdinTools ||
                report.device.knownHandheldKey != null
            val unlockStep = UnlockStep(
                kind = UnlockStepKind.UNLOCK_SCRIPT,
                state = if (hasVendorScriptRunner) UnlockStepState.AVAILABLE
                        else UnlockStepState.AVAILABLE_NEEDS_EXTERNAL_RUNNER,
            )

            val rootPresent = report.rootKind != RootKind.NONE
            val rootStep = UnlockStep(
                kind = UnlockStepKind.ROOT,
                state = when {
                    report.privilege == PrivilegeTier.ROOT -> UnlockStepState.DONE
                    rootPresent -> UnlockStepState.AVAILABLE        // present but not opted-in
                    else -> UnlockStepState.AVAILABLE_NEEDS_INSTALL // user must install Magisk/KernelSU
                },
            )

            return UnlockLadder(
                steps = listOf(shizukuStep, unlockStep, rootStep),
                vendorBinderPathAvailable =
                    report.vendorApps.aynGameAssistant || report.vendorApps.langerhansOdinTools,
            )
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
         * Convert a battery charge-counter reading (µAh, from
         * [BatteryChargeReader.readChargeCounterUah]) into the remaining capacity in
         * mAh that [BatteryTarget.capForTarget] expects, or null when the device does
         * not expose a usable reading.
         *
         * HONESTY (FIX 2): this replaces a hardcoded 3000 mAh placeholder. A null/<=0
         * reading returns null so the caller can show an HONEST "estimate unavailable"
         * note rather than feeding a fabricated constant into the cap math. A valid
         * reading is floored to ≥ 1 mAh so an odd near-empty value can't yield a
         * zero/negative-energy computation. Pure — unit-tested without Android.
         *
         * @return remaining capacity in mAh (≥ 1), or null when unavailable.
         */
        fun remainingMahFromChargeCounter(chargeCounterUah: Long?): Int? {
            if (chargeCounterUah == null) return null
            if (chargeCounterUah <= 0L) return null
            return (chargeCounterUah / 1000L).toInt().coerceAtLeast(1)
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

/**
 * Vendor-neutral ladder of ways to reach the LIVE AutoTDP rung, ordered
 * best-default-first. Shown only when AutoTDP is not already live.
 *
 * [steps] — the ordered rungs (Shizuku → unlock-script → root).
 * [vendorBinderPathAvailable] — true when a vendor binder path (AYN PServer
 *   today) is plausibly present, so the dedicated PServer unlock CTA also shows
 *   as the strongest form of the unlock-script rung.
 */
data class UnlockLadder(
    val steps: List<UnlockStep>,
    val vendorBinderPathAvailable: Boolean,
)

/** One rung of the [UnlockLadder]. */
data class UnlockStep(
    val kind: UnlockStepKind,
    val state: UnlockStepState,
)

/** Which kind of unlock path a [UnlockStep] represents. */
enum class UnlockStepKind {
    /** Grant Shizuku — no root, no reboot. The best default. */
    SHIZUKU,
    /** Run the unlock script once via the device's root-script runner. */
    UNLOCK_SCRIPT,
    /** Enable root mode (Magisk/KernelSU) — full, vendor-independent. */
    ROOT,
}

/** Honest per-device availability of an [UnlockStep] on THIS device. */
enum class UnlockStepState {
    /** This path is done and sufficient for live writes. */
    DONE,
    /** Done, but it did NOT unlock live cpufreq on this device (e.g. Shizuku
     *  granted yet the kernel still denies shell writes). Try the next rung. */
    DONE_BUT_INSUFFICIENT,
    /** Available now — the user can take this step on-device. */
    AVAILABLE,
    /** Available, but needs an external root-script runner (no vendor runner
     *  detected on this generic device). */
    AVAILABLE_NEEDS_EXTERNAL_RUNNER,
    /** Available, but the user must first install Magisk/KernelSU. */
    AVAILABLE_NEEDS_INSTALL,
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
