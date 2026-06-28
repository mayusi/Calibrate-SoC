package io.github.mayusi.calibratesoc.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.insights.GameRecommendation
import io.github.mayusi.calibratesoc.data.insights.GameRecommender
import io.github.mayusi.calibratesoc.data.insights.InsightsAggregator
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportDao
import io.github.mayusi.calibratesoc.data.monitor.BatteryChargeReader
import io.github.mayusi.calibratesoc.data.monitor.BatteryEstimate
import io.github.mayusi.calibratesoc.data.monitor.EstimateBasis
import io.github.mayusi.calibratesoc.data.monitor.FALLBACK_VOLTAGE_V
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.computeBatteryEstimate
import io.github.mayusi.calibratesoc.data.monitor.smoothedPowerMilliW
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.session.SessionRecorder
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import io.github.mayusi.calibratesoc.ui.insights.InsightsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Dashboard state container. Pulls one CapabilityReport on init (for the
 * "what controls are reachable" badge + chart axis labels) and collects
 * the live telemetry Flow into a rolling history buffer the chart can
 * render without re-allocating every tick.
 *
 * The history is capped at [HISTORY_SAMPLES] (60 = one minute at 1 Hz)
 * so memory doesn't grow unbounded if the user leaves the dashboard
 * open. Vico re-renders the whole series each transaction; trimming
 * here also keeps the chart's x-axis range stable.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    capabilityProbe: CapabilityProbe,
    monitorService: MonitorService,
    tuneHistoryStore: TuneHistoryStore,
    private val batteryChargeReader: BatteryChargeReader,
    private val sessionRecorder: SessionRecorder,
    autoTdpController: AutoTdpController,
    private val sessionReportDao: SessionReportDao,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /**
     * Live AutoTDP daemon state. The Dashboard shows a strip when the
     * daemon is not IDLE so the user always knows power management is active.
     */
    val autoTdpState: StateFlow<AutoTdpRunState> = autoTdpController.state

    /** Mirror recorder state for the Dashboard recording card. */
    val isRecording: StateFlow<Boolean> = sessionRecorder.isRecording
    val recordingElapsedSeconds: StateFlow<Long> = sessionRecorder.elapsedSeconds

    fun toggleRecording() {
        viewModelScope.launch {
            if (sessionRecorder.isRecording.value) {
                sessionRecorder.stop("dashboard_button")
            } else {
                // hudIsRunning = false: the Dashboard can't know if the
                // HUD is running. The recorder will switch to Mode A
                // automatically once OverlayService starts calling
                // feedHudSample(). Mode B (standalone) starts here.
                sessionRecorder.start(hudIsRunning = false)
            }
        }
    }

    /**
     * Active-tune chip state for the Dashboard header.
     *
     * The chip can show one of three things:
     *  - null  → no tune has ever been applied; show "Stock (factory)".
     *  - [ActiveTuneState.Current]  → last apply happened AFTER the current boot;
     *    the kernel state should still reflect it.
     *  - [ActiveTuneState.MayHaveReverted] → last apply happened BEFORE the
     *    current boot; the kernel reverts on every boot (BootRevertReceiver),
     *    so we can't honestly claim it's still active.
     *
     * Signal used: approximate boot-wall-clock time =
     *   System.currentTimeMillis() − SystemClock.elapsedRealtime()
     * If the last TuneHistoryEntry's appliedAtMs < bootWallClockMs, the tune
     * pre-dates this boot → show as "Last applied" rather than "Active".
     *
     * Limitation: elapsedRealtime() counts from kernel boot, so the
     * boot-wall-clock estimate drifts by at most a few seconds (NTP
     * correction). That's fine for our purpose — we only need to know
     * "did the apply happen in this boot session", not the exact second.
     * There's also the case where the user applied a profile with
     * "apply on boot" — in that case BootRevertReceiver re-applies it,
     * so it IS still active post-boot. We don't track this currently;
     * such profiles will show "Last applied" (slightly conservative but
     * never wrong — it was at minimum last applied at that time).
     */
    val activeTuneState: StateFlow<ActiveTuneState?> = tuneHistoryStore.entries
        .map { entries ->
            val last = entries.firstOrNull() ?: return@map null
            // Approximate wall-clock time of current boot.
            val bootWallClockMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (last.appliedAtMs >= bootWallClockMs) {
                ActiveTuneState.Current(last.presetName)
            } else {
                ActiveTuneState.MayHaveReverted(last.presetName)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Rolling history of recent samples; head = oldest, tail = newest. */
    private val _history = MutableStateFlow<List<Telemetry>>(emptyList())
    val history: StateFlow<List<Telemetry>> = _history.asStateFlow()

    /** Latest single sample for non-chart widgets (current MHz badge,
     *  battery W readout, etc.). Convenient derived state. */
    val latest: StateFlow<Telemetry?> = _history
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Live battery time-remaining estimate. Recomputed on every telemetry
     * tick using a short rolling average of recent power draws to dampen
     * instantaneous noise.
     *
     * The estimate is explicitly labelled approximate in the UI — see
     * [BatteryEstimate.basis] and [EstimateBasis] for the honesty logic.
     */
    private val _batteryEstimate = MutableStateFlow(
        BatteryEstimate(hoursRemaining = null, watts = null, basis = EstimateBasis.INSUFFICIENT_DATA),
    )
    val batteryEstimate: StateFlow<BatteryEstimate> = _batteryEstimate.asStateFlow()

    /**
     * Battery state-of-charge in whole percent (0–100), or null when unknown.
     *
     * PERF-2: this used to be read inside the [AtAGlanceCard] Composable via a
     * synchronous `registerReceiver(null, ACTION_BATTERY_CHANGED)` binder call on
     * the composition thread — re-keyed on every 1 Hz telemetry tick, so the UI
     * thread made a blocking binder round-trip every second. The read now lives
     * here, driven by a sticky [BroadcastReceiver]: the OS delivers the current
     * level immediately on register, then pushes an update ONLY when the level
     * actually changes (a few times an hour), not once a second. The Composable
     * just collects this StateFlow.
     */
    private val _batteryPct = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = _batteryPct.asStateFlow()

    // ── Game recommendation ────────────────────────────────────────────────────

    /**
     * Result of a one-tap "Apply" action on the recommendation card.
     * Mirrors [InsightsViewModel.ApplyResult] for a local snackbar.
     */
    sealed interface RecommendApplyResult {
        data object Idle : RecommendApplyResult
        data class Success(val appLabel: String, val profileName: String) : RecommendApplyResult
        data class Error(val reason: String) : RecommendApplyResult
    }

    private val _recommendApplyResult = MutableStateFlow<RecommendApplyResult>(RecommendApplyResult.Idle)
    val recommendApplyResult: StateFlow<RecommendApplyResult> = _recommendApplyResult.asStateFlow()

    fun clearRecommendApplyResult() { _recommendApplyResult.value = RecommendApplyResult.Idle }

    /**
     * Current game recommendation for the foreground (or last-seen foreground) package.
     *
     * Recomputed whenever the foreground package or session reports change.
     * Holds the last non-null foreground package so the card persists when
     * the user navigates back to CalibrateSoC.
     * Null when no recommendation can be honestly made (no data, no KnownGames hit).
     */
    val recommendation: StateFlow<GameRecommendation?> = combine(
        _history.map { samples ->
            // Walk newest-first to find the last non-null foreground package.
            samples.lastOrNull { it.foregroundPackage != null }?.foregroundPackage
        },
        sessionReportDao.observeAll().map { entities -> entities.map { it.toDomain() } },
    ) { fgPkg, reports ->
        val pkg = fgPkg ?: return@combine null
        val appLabel = resolveAppLabel(appContext, pkg)
        val weekStart = InsightsViewModel.weekStartMs()
        val weekEnd = System.currentTimeMillis()
        val weekReports = reports.filter { it.startedAtMs in weekStart..weekEnd }
        val summary = InsightsAggregator.compute(reports, weekReports)
        GameRecommender.recommendFor(pkg, appLabel, summary)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Current profile store, used to show "✓ APPLIED" state on the card.
     */
    val profileStore: StateFlow<io.github.mayusi.calibratesoc.data.profiles.ProfileStore> =
        profileRepository.store
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), profileRepository.snapshot())

    /**
     * Apply the recommended profile to the foreground game package.
     * Preserves all other bundle fields; only updates profileId.
     */
    fun applyRecommendation(packageName: String, appLabel: String, profileName: String) {
        viewModelScope.launch {
            val store = profileRepository.snapshot()
            val profile = store.profiles.firstOrNull { it.name == profileName }
            if (profile == null) {
                _recommendApplyResult.value = RecommendApplyResult.Error(
                    "Profile \"$profileName\" no longer exists — it may have been renamed or deleted. No changes were made.",
                )
                return@launch
            }
            val existing = store.perAppBundles[packageName]
            val updated = (existing ?: PerAppBundle()).copy(profileId = profile.id)
            profileRepository.setBundle(packageName, updated)
            _recommendApplyResult.value = RecommendApplyResult.Success(appLabel, profileName)
        }
    }

    // ── Battery % ──────────────────────────────────────────────────────────────

    /**
     * Sticky ACTION_BATTERY_CHANGED receiver. Registering with a null receiver
     * would only give a one-shot read; a real receiver keeps [_batteryPct] fresh
     * for the lifetime of the screen without polling. Unregistered in [onCleared].
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryPct(intent)
        }
    }

    private fun updateBatteryPct(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        _batteryPct.value = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            null
        }
    }

    init {
        // PERF-2: register the sticky battery receiver. registerReceiver with a
        // sticky action returns the current sticky Intent synchronously, so the
        // first level is available immediately; subsequent changes arrive via
        // onReceive. runCatching guards the rare case where the framework refuses
        // the registration (e.g. headless test context).
        runCatching {
            val sticky = appContext.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            updateBatteryPct(sticky)
        }

        // Make sure the capability report is loaded; the monitor service
        // needs it for GPU probe path lookup. Idempotent.
        viewModelScope.launch { capabilityProbe.refresh() }
        viewModelScope.launch {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                val current = _history.value
                val updated = if (current.size >= HISTORY_SAMPLES) {
                    current.drop(current.size - HISTORY_SAMPLES + 1) + sample
                } else {
                    current + sample
                }
                _history.value = updated

                // Recompute the battery estimate on each tick.
                // Reading the charge counter is a cheap binder call (~0.1 ms).
                val chargeCounterUah = batteryChargeReader.readChargeCounterUah()
                val smoothedMw = smoothedPowerMilliW(updated)
                // Prefer live voltage from telemetry; fall back to nominal 3.85 V.
                val voltageV = sample.batteryVoltageUv?.let { it / 1_000_000.0 }
                    ?: FALLBACK_VOLTAGE_V
                _batteryEstimate.value = computeBatteryEstimate(
                    chargeCounterUah = chargeCounterUah,
                    nominalVoltageV = voltageV,
                    smoothedPowerMilliW = smoothedMw,
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister the battery receiver so it doesn't leak past the ViewModel.
        // runCatching guards a double-unregister or a never-registered receiver
        // (registration may have failed in init).
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
    }

    companion object {
        const val HISTORY_SAMPLES = 60

        /**
         * Best-effort resolution of a package name to a human-readable app label.
         * Returns null on any failure (app uninstalled, PM exception). Never throws.
         * IO-safe: call from a coroutine or withContext(Dispatchers.IO).
         */
        internal fun resolveAppLabel(context: Context, packageName: String): String? =
            runCatching {
                val pm = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
            }.getOrNull()
    }
}

/** Tune chip display state for the Dashboard header. */
sealed interface ActiveTuneState {
    /** The tune was applied during the current boot session — likely still active. */
    data class Current(val name: String) : ActiveTuneState
    /** The tune was applied in a previous boot — kernel has likely reverted since then. */
    data class MayHaveReverted(val name: String) : ActiveTuneState
}
