package io.github.mayusi.calibratesoc.ui.overlay

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.TempAlertMonitor
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.session.SessionRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the single shared telemetry subscription and assembles [HudUiState]
 * fields from it plus AutoTDP state. Extracted from [OverlayService] to:
 *
 *  1. Fix the double-subscription bug — [OverlayService] previously called
 *     [MonitorService.telemetry] TWICE (once in observeTelemetry, once in
 *     observeAlerts). Both opened independent 1 Hz polling loops.  Now there
 *     is ONE shared hot flow ([sharedTelemetry]) used by both the HUD state
 *     assembly and [TempAlertMonitor].
 *
 *  2. Keep [OverlayService] as a thin host (lifecycle + WindowManager) — all
 *     state-assembly logic lives here.
 *
 * Not a Hilt [Singleton] because it is only alive while the HUD service runs.
 * [OverlayService] creates it in onCreate() and passes its own [serviceScope].
 */
class HudStateAssembler @Inject constructor(
    private val monitorService: MonitorService,
    private val tempAlertMonitor: TempAlertMonitor,
    private val profileRepository: ProfileRepository,
    private val sessionRecorder: SessionRecorder,
    private val hudPrefs: HudPrefs,
    private val autoTdpController: AutoTdpController,
    private val hudEventLog: HudEventLog,
) {
    private val _state = MutableStateFlow(HudUiState())
    val state: StateFlow<HudUiState> = _state.asStateFlow()

    /**
     * Start all observation loops. Must be called once from
     * [OverlayService.onCreate] with the service's coroutine scope.
     *
     * [frameRateSampler] and [gameFpsSampler] are kept in [OverlayService]
     * because they are tied to Android Choreographer / process lifecycle;
     * their outputs are fed in via [feedHudHz] / [feedGameFps].
     */
    fun start(scope: CoroutineScope) {
        // ONE shared cold flow → hot shared flow.  Both the HUD state
        // assembler and TempAlertMonitor subscribe to sharedTelemetry so
        // the underlying sysfs polling happens ONCE, not twice.
        val sharedTelemetry = monitorService
            .telemetry(MonitorService.DEFAULT_INTERVAL_MS)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

        // Assemble telemetry fields into HudUiState.
        scope.launch {
            sharedTelemetry.collect { t ->
                val cpuMaxKhz = t.perCoreCpuFreqKhz.maxOrNull() ?: 0
                val cpuLoadPct = if (t.perCoreLoadPct.isEmpty()) 0
                    else t.perCoreLoadPct.average().toInt()
                val gpuMhz = t.gpuFreqHz?.let { (it / 1_000_000L).toInt() }
                val cpuTemps = t.zoneTempsMilliC
                    .filter { it.label.startsWith("cpu", ignoreCase = true) }
                    .map { it.tempMilliC / 1000f }
                val cpuTempC = if (cpuTemps.isEmpty()) null
                    else cpuTemps.average().toFloat()
                val cpuPeakTempC = cpuTemps.maxOrNull()
                val gpuTempC = t.zoneTempsMilliC
                    .filter {
                        it.label.contains("gpu", ignoreCase = true) ||
                            it.label.contains("kgsl", ignoreCase = true)
                    }
                    .maxOfOrNull { it.tempMilliC / 1000f }
                val batteryW: Double? = run {
                    val ua = t.batteryCurrentUa ?: return@run null
                    val uv = t.batteryVoltageUv ?: return@run null
                    val absUa = if (ua < 0) -ua else ua
                    val mw = (absUa * uv) / 1_000_000_000L
                    mw / 1000.0
                }
                val ramUsedPct = if (t.ramTotalKb > 0) {
                    (100.0 * (t.ramTotalKb - t.ramAvailableKb) / t.ramTotalKb).toInt()
                        .coerceIn(0, 100)
                } else null
                val batteryTempC = t.batteryTempDeciC?.let { it / 10f }
                _state.value = _state.value.copy(
                    cpuMaxMhz = cpuMaxKhz / 1000,
                    cpuLoadPct = cpuLoadPct,
                    perCoreMhz = t.perCoreCpuFreqKhz.map { it / 1000 },
                    perCoreLoadPct = t.perCoreLoadPct,
                    gpuMhz = gpuMhz,
                    gpuLoadPct = t.gpuLoadPct,
                    cpuTempC = cpuTempC,
                    cpuPeakTempC = cpuPeakTempC,
                    gpuTempC = gpuTempC,
                    batteryTempC = batteryTempC,
                    maxTempC = t.zoneTempsMilliC.maxOfOrNull { it.tempMilliC / 1000f } ?: 0f,
                    batteryW = batteryW,
                    ramUsedPct = ramUsedPct,
                    zones = t.zoneTempsMilliC.map { it.label to it.tempMilliC / 1000f },
                )
                // Feed session recorder (Mode A: HUD-driven).
                if (sessionRecorder.isRecording.value) {
                    sessionRecorder.feedHudSample(
                        absoluteTimestampMs = t.timestampMs,
                        fps = _state.value.gameFps,
                        cpuMaxMhz = cpuMaxKhz / 1000,
                        gpuMhz = gpuMhz,
                        cpuTempC = cpuTempC,
                        gpuTempC = gpuTempC,
                        batteryW = batteryW,
                        cpuLoadPct = cpuLoadPct,
                    )
                }
            }
        }

        // Drive TempAlertMonitor off the SAME shared flow — no extra sysfs poll.
        // Retry-with-backoff so a transient failure (e.g. pref-read race on
        // startup) self-heals instead of silently killing the alert path.
        // CancellationException is re-thrown so normal scope shutdown propagates.
        scope.launch {
            val backoffMs = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)
            var attempt = 0
            while (true) {
                val result = runCatching { tempAlertMonitor.observe(sharedTelemetry) }
                result.onFailure { t ->
                    if (t is CancellationException) throw t
                    val delay = backoffMs.getOrElse(attempt) { backoffMs.last() }
                    hudEventLog.add(
                        HudEventLog.Level.ERROR,
                        "TempAlertMonitor stopped (attempt ${attempt + 1}): ${t.message} — retry in ${delay / 1000}s",
                    )
                    attempt++
                    delay(delay)
                }
                // observe() returned normally (e.g. flow completed) — exit loop.
                if (result.isSuccess) break
            }
        }

        // Profile repository → chip list.
        scope.launch {
            profileRepository.store.collect { store ->
                val chips = store.profiles
                    .sortedByDescending { it.createdAtMs }
                    .take(4)
                    .map { it.id to it.name }
                _state.value = _state.value.copy(quickProfiles = chips)
            }
        }

        // Session recorder state.
        scope.launch {
            sessionRecorder.isRecording.collect { recording ->
                _state.value = _state.value.copy(isRecording = recording)
            }
        }
        scope.launch {
            sessionRecorder.elapsedSeconds.collect { secs ->
                _state.value = _state.value.copy(recordingElapsedSeconds = secs)
            }
        }

        // AutoTDP state → HUD fields.
        scope.launch {
            autoTdpController.state.collect { autoState ->
                val running = autoState.status == AutoTdpStatus.RUNNING
                val applied = autoState.appliedState
                val savings = autoState.savings
                _state.value = _state.value.copy(
                    autoTdpStatus = autoState.status,
                    autoTdpRunning = running,
                    autoTdpParkedCores = applied?.parkedPrimeCores ?: emptySet(),
                    autoTdpBigCapMhz = applied?.bigClusterCapKhz?.let { it / 1000 },
                    autoTdpGpuLevel = applied?.gpuFloorLevel,
                    autoTdpReason = autoState.lastReason,
                    autoTdpSavingsMw = savings?.deltaMw?.toInt(),
                    autoTdpSavingsPct = savings?.deltaPct,
                    autoTdpSavingsReady = savings?.enoughData ?: false,
                )
            }
        }
    }

    // ── Feed points called by OverlayService for things tied to Android APIs ──

    /** Called by the Choreographer-backed [HudFrameRateSampler] collector. */
    fun feedHudHz(hz: Int) {
        _state.value = _state.value.copy(hudHz = hz)
    }

    /** Called by the [GameFpsSampler] collector. */
    fun feedGameFps(fps: Int?, pkg: String?, isRealFps: Boolean = false) {
        _state.value = _state.value.copy(
            gameFps = fps,
            gameForegroundPkg = pkg,
            gameFpsIsReal = isRealFps,
        )
    }

    /** Called by [HudPrefs] profile/step/policy observers. */
    fun feedProfile(profile: HudProfile) {
        _state.value = _state.value.copy(profile = profile)
    }

    fun feedStepMhz(step: Int) {
        _state.value = _state.value.copy(stepMhz = step)
    }

    fun feedEnabledPolicies(set: Set<Int>) {
        if (set.isNotEmpty()) _state.value = _state.value.copy(enabledPolicies = set)
    }

    /** Called by the capability probe observer with big-core + write-ability info. */
    fun feedCapability(
        bigCorePolicy: Int?,
        bigCoreCurrentMhz: Int?,
        allPolicies: List<Int>,
        enabledPolicies: Set<Int>,
    ) {
        _state.value = _state.value.copy(
            bigCorePolicy = bigCorePolicy,
            bigCoreCurrentMhz = bigCoreCurrentMhz,
            allPolicies = allPolicies,
            enabledPolicies = if (_state.value.enabledPolicies.isEmpty()) enabledPolicies
                else _state.value.enabledPolicies,
        )
    }

    fun feedCanTuneLive(can: Boolean) {
        _state.value = _state.value.copy(canTuneLive = can)
    }

    /** Update the big-core current MHz after a live step write. */
    fun feedBigCoreMhz(policyId: Int?, mhz: Int?) {
        _state.value = _state.value.copy(
            bigCorePolicy = policyId ?: _state.value.bigCorePolicy,
            bigCoreCurrentMhz = mhz ?: _state.value.bigCoreCurrentMhz,
        )
    }

    /** Flash/clear the lastActionMessage. */
    fun setActionMessage(msg: String?) {
        _state.value = _state.value.copy(lastActionMessage = msg)
    }

    fun updateEnabledPolicies(set: Set<Int>) {
        _state.value = _state.value.copy(enabledPolicies = set)
    }

    fun feedAutoTdpActiveProfile(profile: AutoTdpProfile) {
        _state.value = _state.value.copy(autoTdpActiveProfile = profile)
    }

    fun feedRefreshRateOptions(hzOptions: List<Float>) {
        _state.value = _state.value.copy(availableHzOptions = hzOptions)
    }

    fun feedPinnedHz(hz: Float?) {
        _state.value = _state.value.copy(pinnedHz = hz)
    }

    fun feedHudSizeIndex(index: Int) {
        _state.value = _state.value.copy(hudSizeIndex = index.coerceIn(0, 2))
    }

    fun feedHudOpacity(opacity: Float) {
        _state.value = _state.value.copy(hudOpacity = opacity.coerceIn(0.1f, 1f))
    }
}
