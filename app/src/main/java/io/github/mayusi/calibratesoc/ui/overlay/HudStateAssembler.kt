package io.github.mayusi.calibratesoc.ui.overlay

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.util.khzToMhz
import io.github.mayusi.calibratesoc.data.util.mwFromUaUv
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
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
                val f = assembleTelemetryFields(t)
                _state.value = _state.value.copy(
                    cpuMaxMhz = f.cpuMaxMhz,
                    cpuLoadPct = f.cpuLoadPct,
                    perCoreMhz = f.perCoreMhz,
                    perCoreLoadPct = t.perCoreLoadPct,
                    gpuMhz = f.gpuMhz,
                    gpuLoadPct = t.gpuLoadPct,
                    cpuTempC = f.cpuTempC,
                    cpuPeakTempC = f.cpuPeakTempC,
                    gpuTempC = f.gpuTempC,
                    batteryTempC = f.batteryTempC,
                    maxTempC = f.maxTempC,
                    batteryW = f.batteryW,
                    ramUsedPct = f.ramUsedPct,
                    loadIsProxy = f.loadIsProxy,
                    coolingDeviceMaxState = t.coolingDeviceMaxState,
                    zones = f.zones,
                )
                // Feed session recorder (Mode A: HUD-driven).
                if (sessionRecorder.isRecording.value) {
                    sessionRecorder.feedHudSample(
                        absoluteTimestampMs = t.timestampMs,
                        fps = _state.value.gameFps,
                        cpuMaxMhz = f.cpuMaxMhz,
                        gpuMhz = f.gpuMhz,
                        cpuTempC = f.cpuTempC,
                        gpuTempC = f.gpuTempC,
                        batteryW = f.batteryW,
                        cpuLoadPct = f.cpuLoadPct,
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
                val effect = autoState.effect
                // capDelta MHz from the effect's DERIVED kHz delta (always honest).
                val capDeltaMhz = effect?.capDeltaKhz?.let { it / 1000 }
                // Session energy: effect carries mWh; HUD shows Wh. MEASURED-only —
                // null until a completed probe backs it, so we never show a fake number.
                val sessionWh = effect?.sessionEnergySavedMilliWh?.let { it / 1000.0 }
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
                    autoTdpHoldReason = autoState.holdReason,
                    autoTdpLastAppliedEpochMs = autoState.lastAppliedEpochMs,
                    autoTdpCapDeltaMhz = capDeltaMhz,
                    autoTdpSessionWh = sessionWh,
                    autoTdpDecisions = autoState.decisions,
                    // Wave 4b: Smart goal + detected context passthrough
                    autoTdpGoal = autoState.activeGoal,
                    autoTdpDetectedContext = autoState.detectedContext,
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

    /** Called by [HudPrefs] profile observer. */
    fun feedProfile(profile: HudProfile) {
        _state.value = _state.value.copy(profile = profile)
    }

    /** Flash/clear the lastActionMessage. */
    fun setActionMessage(msg: String?) {
        _state.value = _state.value.copy(lastActionMessage = msg)
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

    /**
     * Battery state-of-charge percent (0–100), or null when the sensor is
     * unavailable. Fed by [OverlayService] from a sticky ACTION_BATTERY_CHANGED
     * receiver — NEVER from a per-tick binder read in a composable (honours the
     * 0.1.33 ANR rule and the Dashboard's PERF-2 pattern). Null stays null so the
     * HUD shows "—", never a fabricated default.
     */
    fun feedBatteryPct(pct: Int?) {
        _state.value = _state.value.copy(batteryPct = pct?.coerceIn(0, 100))
    }

    /**
     * Derived HUD telemetry fields. Pure value holder so the per-tick mapping can
     * be unit-tested on the JVM without a coroutine/Android harness.
     */
    data class TelemetryFields(
        val cpuMaxMhz: Int,
        val cpuLoadPct: Int,
        val perCoreMhz: List<Int>,
        val gpuMhz: Int?,
        val cpuTempC: Float?,
        val cpuPeakTempC: Float?,
        val gpuTempC: Float?,
        val batteryTempC: Float?,
        val maxTempC: Float,
        val batteryW: Double?,
        val ramUsedPct: Int?,
        val loadIsProxy: Boolean,
        val zones: List<Pair<String, Float>>,
    )

    companion object {
        /**
         * Pure telemetry → HUD-field mapping. Extracted from the collect loop so
         * the load-bearing honesty rules are unit-testable:
         *
         *  - [TelemetryFields.cpuTempC] is the AVERAGE cpu-zone temp (steady gauge),
         *    while [TelemetryFields.cpuPeakTempC] is the MAX cpu-zone temp — the HUD
         *    renders the PEAK so the hottest core is never hidden behind an average.
         *  - [TelemetryFields.gpuTempC] prefers a gpu/kgsl THERMAL ZONE, and FALLS
         *    BACK to the engine's [Telemetry.gpuDieTempMilliC] when no such zone
         *    exists (die-only devices) so GPU temp isn't blank.
         *  - Every field stays null when its sensor is absent — never a sentinel.
         */
        @JvmStatic
        fun assembleTelemetryFields(t: Telemetry): TelemetryFields {
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
            // Prefer a real gpu/kgsl thermal zone; fall back to the engine's GPU
            // die-temp probe so die-only devices (no gpu thermal_zone) aren't blank.
            val gpuZoneTempC = t.zoneTempsMilliC
                .filter {
                    it.label.contains("gpu", ignoreCase = true) ||
                        it.label.contains("kgsl", ignoreCase = true)
                }
                .maxOfOrNull { it.tempMilliC / 1000f }
            val gpuTempC = gpuZoneTempC ?: t.gpuDieTempMilliC?.let { it / 1000f }
            // Power honesty (RP6 fix): some devices' /sys/.../current_now reads a
            // literal 0 while the device is actively running — a true 0 W draw is
            // physically impossible there, so 0 means "unreadable", not "idle".
            // Treat 0 (and null) current as UNAVAILABLE → batteryW stays null → the
            // HUD renders "--", never a misleading "0.0W". A non-zero current
            // (either sign) is a real measurement and produces a real wattage.
            val batteryW: Double? = run {
                val ua = t.batteryCurrentUa ?: return@run null
                val uv = t.batteryVoltageUv ?: return@run null
                val absUa = if (ua < 0) -ua else ua
                if (absUa == 0L) return@run null
                val mw = absUa.mwFromUaUv(uv)
                mw / 1000.0
            }
            val ramUsedPct = if (t.ramTotalKb > 0) {
                (100.0 * (t.ramTotalKb - t.ramAvailableKb) / t.ramTotalKb).toInt()
                    .coerceIn(0, 100)
            } else null
            val batteryTempC = t.batteryTempDeciC?.let { it / 10f }
            val loadIsProxy = t.cpuLoadSource == CpuLoadReading.Source.FREQ_PROXY
            return TelemetryFields(
                cpuMaxMhz = cpuMaxKhz.khzToMhz(),
                cpuLoadPct = cpuLoadPct,
                perCoreMhz = t.perCoreCpuFreqKhz.map { it / 1000 },
                gpuMhz = gpuMhz,
                cpuTempC = cpuTempC,
                cpuPeakTempC = cpuPeakTempC,
                gpuTempC = gpuTempC,
                batteryTempC = batteryTempC,
                maxTempC = t.zoneTempsMilliC.maxOfOrNull { it.tempMilliC / 1000f } ?: 0f,
                batteryW = batteryW,
                ramUsedPct = ramUsedPct,
                loadIsProxy = loadIsProxy,
                zones = t.zoneTempsMilliC.map { it.label to it.tempMilliC / 1000f },
            )
        }
    }
}
