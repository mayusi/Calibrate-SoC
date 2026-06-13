package io.github.mayusi.calibratesoc.data.session

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that manages a recording session. Two sampling modes:
 *
 * MODE A — HUD is running (OverlayService calls [feedHudSample] once per
 *   telemetry tick). This mode captures REAL game FPS because the HUD
 *   already has it from [GameFpsSampler]. This is the preferred path.
 *
 * MODE B — HUD is NOT running. The recorder starts its own 1 Hz telemetry
 *   collection via [MonitorService]. FPS will be null for all samples in
 *   this mode (no access to SurfaceFlinger data outside the HUD context).
 *   The UI surfaces an honest disclaimer when this is the case.
 *
 * Session length is capped at [MAX_SAMPLES] (~3 hours at 1 Hz) to prevent
 * unbounded memory growth. If the cap is hit the session auto-stops and
 * is saved.
 *
 * [elapsedSeconds] is a derived StateFlow updated every second while
 * recording, intended for the HUD's elapsed timer badge.
 */
@Singleton
class SessionRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitorService: MonitorService,
    private val repository: SessionRepository,
    private val tuneHistoryStore: TuneHistoryStore,
) {

    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Public state ---

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    // --- Internal state ---

    private val buffer = mutableListOf<SessionSample>()
    private var startedAtMs = 0L
    private var standaloneJob: Job? = null
    private var timerJob: Job? = null

    // Whether mode A (HUD-driven) or B (standalone) is active.
    // In mode A, feedHudSample() is called by OverlayService.
    // In mode B, we run our own telemetry coroutine.
    private var hudDriven = false

    // --- HUD-driven path (Mode A) ---

    /**
     * Called by [OverlayService] every telemetry tick when the HUD is
     * running. The HUD already owns both the telemetry values and the
     * real game FPS — we stash the sample and compute elapsed in-place
     * relative to [startedAtMs].
     *
     * No-op if not recording. Thread-safe: @Singleton + all mutations on
     * the same IO dispatcher.
     */
    fun feedHudSample(
        absoluteTimestampMs: Long,
        fps: Int?,
        cpuMaxMhz: Int,
        gpuMhz: Int?,
        cpuTempC: Float?,
        gpuTempC: Float?,
        batteryW: Double?,
        cpuLoadPct: Int,
    ) {
        if (!_isRecording.value) return
        hudDriven = true
        val sample = SessionSample(
            elapsedMs = absoluteTimestampMs - startedAtMs,
            fps = fps?.toFloat(),
            cpuMaxMhz = cpuMaxMhz,
            gpuMhz = gpuMhz,
            cpuTempC = cpuTempC,
            gpuTempC = gpuTempC,
            batteryW = batteryW,
            cpuLoadPct = cpuLoadPct,
        )
        synchronized(buffer) {
            buffer.add(sample)
            _sampleCount.value = buffer.size
        }
        if (buffer.size >= MAX_SAMPLES) {
            // Cap hit — auto-stop to avoid unbounded memory growth.
            recorderScope.launch { stop(reason = "cap") }
        }
    }

    // --- Start / Stop ---

    /**
     * Start a new session. If the HUD is running, mode A (HUD-driven)
     * will activate automatically as OverlayService calls [feedHudSample].
     * If the HUD is NOT running, we start standalone mode B.
     *
     * [hudIsRunning] = true when called from OverlayService; false when
     * called from the Dashboard fallback button.
     */
    fun start(hudIsRunning: Boolean = false) {
        if (_isRecording.value) return
        synchronized(buffer) { buffer.clear() }
        startedAtMs = System.currentTimeMillis()
        hudDriven = hudIsRunning
        _isRecording.value = true
        _elapsedSeconds.value = 0L
        _sampleCount.value = 0

        // Timer tick regardless of mode.
        timerJob = recorderScope.launch {
            while (_isRecording.value) {
                delay(1_000)
                _elapsedSeconds.value = (System.currentTimeMillis() - startedAtMs) / 1_000
            }
        }

        // Mode B: standalone telemetry collection (no real FPS).
        if (!hudIsRunning) {
            standaloneJob = recorderScope.launch {
                monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { t ->
                    if (!_isRecording.value) return@collect
                    val elapsed = System.currentTimeMillis() - startedAtMs
                    val cpuMaxKhz = t.perCoreCpuFreqKhz.maxOrNull() ?: 0
                    val cpuLoadPct = if (t.perCoreLoadPct.isEmpty()) 0
                        else t.perCoreLoadPct.average().toInt()
                    val gpuMhz = t.gpuFreqHz?.let { (it / 1_000_000L).toInt() }
                    val cpuTempC = t.zoneTempsMilliC
                        .filter { it.label.startsWith("cpu", ignoreCase = true) }
                        .map { it.tempMilliC / 1000f }
                        .takeIf { it.isNotEmpty() }
                        ?.average()?.toFloat()
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
                    val sample = SessionSample(
                        elapsedMs = elapsed,
                        fps = null, // FPS is unavailable without the HUD
                        cpuMaxMhz = cpuMaxKhz / 1000,
                        gpuMhz = gpuMhz,
                        cpuTempC = cpuTempC,
                        gpuTempC = gpuTempC,
                        batteryW = batteryW,
                        cpuLoadPct = cpuLoadPct,
                    )
                    synchronized(buffer) {
                        buffer.add(sample)
                        _sampleCount.value = buffer.size
                    }
                    if (buffer.size >= MAX_SAMPLES) {
                        stop(reason = "cap")
                    }
                }
            }
        }
    }

    /**
     * Stop the recording, compute summary, persist via [SessionRepository].
     * Safe to call multiple times (no-op when not recording).
     *
     * [reason] is informational ("user", "hud_stop", "cap").
     */
    suspend fun stop(reason: String = "user") {
        if (!_isRecording.value) return
        _isRecording.value = false
        timerJob?.cancel()
        timerJob = null
        standaloneJob?.cancel()
        standaloneJob = null

        val endedAtMs = System.currentTimeMillis()
        val durationMs = endedAtMs - startedAtMs
        val capturedSamples: List<SessionSample>
        synchronized(buffer) {
            capturedSamples = buffer.toList()
            buffer.clear()
        }

        if (capturedSamples.isEmpty()) {
            // Nothing to save (recording was immediately stopped, e.g. a
            // mis-tap). Don't persist a ghost session.
            _elapsedSeconds.value = 0L
            _sampleCount.value = 0
            return
        }

        val summary = computeSessionSummary(capturedSamples)
        val fpsAvailable = capturedSamples.any { it.fps != null }

        // Best-effort: try to capture the foreground app label.
        val appLabel = runCatching { resolveAppLabel() }.getOrNull()

        // Best-effort: last-applied tune preset.
        val profileName = runCatching {
            tuneHistoryStore.entries.firstOrNull()?.firstOrNull()?.presetName
        }.getOrNull()

        val session = GameSession(
            id = 0L, // Room assigns the real id
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            appLabel = appLabel,
            profileName = profileName,
            samples = capturedSamples,
            summary = summary,
            fpsAvailableDuringSampling = fpsAvailable,
        )
        repository.save(session)
        _elapsedSeconds.value = 0L
        _sampleCount.value = 0
    }

    /**
     * Best-effort foreground app name lookup via UsageStatsManager.
     * Returns null gracefully when PACKAGE_USAGE_STATS is not granted.
     */
    private fun resolveAppLabel(): String? {
        val um = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        if (context.packageManager.checkPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS,
                context.packageName,
            ) != PackageManager.PERMISSION_GRANTED
        ) return null

        val now = System.currentTimeMillis()
        val events = um.queryEvents(now - 60_000L, now)
        val ev = android.app.usage.UsageEvents.Event()
        var lastPkg: String? = null
        while (events.getNextEvent(ev)) {
            if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = ev.packageName
            }
        }
        val pkg = lastPkg?.takeIf { it != context.packageName } ?: return null
        return runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        }.getOrNull()
    }

    companion object {
        /** 3 hours at 1 Hz = 10 800 samples. Safety cap. */
        const val MAX_SAMPLES = 10_800
    }
}
