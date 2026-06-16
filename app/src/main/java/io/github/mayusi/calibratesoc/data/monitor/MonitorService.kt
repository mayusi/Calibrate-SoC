package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.SysfsProber
import io.github.mayusi.calibratesoc.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telemetry source. Stays as a plain Hilt singleton in Phase 3a — it
 * becomes a foreground service in Phase 3b when the floating overlay
 * needs it to survive backgrounding.
 *
 * Sample cadence is parameterised because the benchmark module wants
 * 4 Hz during stress runs while the idle dashboard is happy at 1 Hz.
 *
 * Thermal zone topology is cached in [SysfsProber] (30-second TTL).
 * The hot tick calls [SysfsProber.readThermalZoneTemps] which only
 * re-reads the temperature files using the cached paths — no directory
 * enumeration per sample. A hot-added zone (USB-C dock insertion) will
 * be picked up within the next TTL window.
 *
 * Independent samplers run concurrently via [coroutineScope] + [async]
 * so their sysfs reads overlap on Dispatchers.IO thread pool, cutting
 * per-tick wall time roughly proportional to the number of samplers.
 *
 * ## CPU load source
 *
 * CPU load is obtained via [CpuLoadSourceSelector] which implements a
 * three-rung fallback chain:
 *   1. Root /proc/stat via PServerWriter (true jiffie-based load, bypasses
 *      Android 12+ proc_stat hidepid restrictions).
 *   2. Direct /proc/stat from the app UID (works on Android 11 and below).
 *   3. scaling_cur_freq ÷ scaling_max_freq as a coarse proxy.
 * The [CpuLoadReading.source] is stored in the emitted [Telemetry] so the
 * HUD can render a "~" indicator when freq-proxy data is shown.
 *
 * ## Shared default-interval stream (battery win)
 *
 * The 1 Hz ([DEFAULT_INTERVAL_MS]) stream is a SINGLE process-shared hot flow
 * ([sharedDefaultTelemetry]). Every default-interval subscriber — Dashboard,
 * AutoTDP, Tunes, Advanced, the HUD assembler, the throttle/boost services, the
 * session recorder, the advisory controller — observes the SAME flow, so the
 * sysfs-polling loop (per-core freq, GPU, meminfo, every thermal zone, battery,
 * cooling devices) runs ONCE no matter how many screens are open. The upstream
 * is started lazily and stopped [SHARE_STOP_TIMEOUT_MS] after the last collector
 * leaves (`SharingStarted.WhileSubscribed`), so an idle, fully-backgrounded app
 * polls nothing.
 *
 * The [STRESS_INTERVAL_MS] (4 Hz benchmark) variant is intentionally NOT shared:
 * benchmark/stability runs are short-lived, explicitly scoped, and want a fresh
 * cold loop each time, so [telemetry] returns a cold flow for any non-default
 * interval.
 */
@Singleton
class MonitorService @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val sysfsProber: SysfsProber,
    private val perCoreFreq: PerCoreFreqSampler,
    private val cpuLoadSelector: CpuLoadSourceSelector,
    private val meminfo: MeminfoSampler,
    private val gpuLoad: GpuLoadSampler,
    private val battery: BatterySampler,
    private val gameFpsSampler: io.github.mayusi.calibratesoc.ui.overlay.GameFpsSampler,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /**
     * The ONE shared 1 Hz telemetry stream. Created once at construction and
     * shared across the whole process. `replay = 1` so a fresh subscriber gets
     * the most recent sample immediately instead of waiting up to a full second.
     * `WhileSubscribed` keeps the upstream alive only while at least one collector
     * is active (plus a short grace window), so backgrounding the app stops the
     * polling loop entirely.
     */
    private val sharedDefaultTelemetry: SharedFlow<Telemetry> =
        coldTelemetry(DEFAULT_INTERVAL_MS)
            .shareIn(
                scope = appScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARE_STOP_TIMEOUT_MS),
                replay = 1,
            )

    /**
     * Telemetry stream at the requested [intervalMs].
     *
     * For [DEFAULT_INTERVAL_MS] (the default) this returns the single
     * process-shared hot flow so all subscribers share ONE polling loop. For any
     * other cadence (notably [STRESS_INTERVAL_MS]) it returns a fresh cold flow —
     * benchmark runs want their own short-lived loop.
     *
     * The returned [Flow] honours the same contract as before: collectors receive
     * a [Telemetry] sample roughly every [intervalMs].
     */
    fun telemetry(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Telemetry> =
        if (intervalMs == DEFAULT_INTERVAL_MS) sharedDefaultTelemetry
        else coldTelemetry(intervalMs)

    /**
     * The underlying cold polling loop. One `collect` == one independent
     * sysfs-polling loop, so this is private: default-interval callers MUST go
     * through [sharedDefaultTelemetry] (via [telemetry]) to avoid duplicate polls.
     */
    private fun coldTelemetry(intervalMs: Long): Flow<Telemetry> = flow {
        // Reset the load sampler's baseline on every (re)start so the
        // first sample isn't a delta against ancient state from a prior
        // collect.
        cpuLoadSelector.reset()

        // BUG B FIX: Do NOT capture gpuProbe once at start. The CapabilityReport
        // may still be null when the HUD/daemon starts before the first refresh
        // completes. Re-read capabilityProbe.report.value?.gpu inside each tick
        // so it populates as soon as the report arrives (typically within the
        // first 1-2 seconds). GPU load will correctly become non-zero after the
        // first successful capability refresh rather than staying 0 forever.

        while (true) {
            val now = System.currentTimeMillis()

            // Re-read gpuProbe each tick so it picks up the CapabilityReport
            // as soon as it is ready (the report is null initially and arrives
            // after the first CapabilityProbe.refresh() call completes).
            val gpuProbe: GpuProbe? = capabilityProbe.report.value?.gpu

            // Run independent I/O-bound samplers concurrently.
            // CpuLoadSourceSelector is stateful (delta between calls) so it
            // must not be reordered relative to itself, but running it in parallel
            // with the other samplers is safe — it reads /proc/stat independently.
            // BatterySampler uses Android binder, not sysfs, and is independent.
            // Resolve the GPU sysfs root for the die-temp read (Wave 2). Re-read each
            // tick for the same reason gpuProbe is re-read — the report arrives async.
            val gpuRootPath: String? = gpuProbe?.rootPath

            val (freqs, cpuReading, mem, gpu, batt, zones, extras) = coroutineScope {
                val dFreqs = async { perCoreFreq.sample() }
                val dLoads = async { cpuLoadSelector.sample() }
                val dMem   = async { meminfo.sample() }
                val dGpu   = async { gpuLoad.sample(gpuProbe) }
                val dBatt  = async { battery.sample() }
                // Use the cheap temp-only read — zone list is cached by SysfsProber.
                val dZones = async {
                    sysfsProber.readThermalZoneTemps().map {
                        ZoneTemp(zoneId = it.id, label = it.type, tempMilliC = it.currentTempMilliC)
                    }
                }
                // ── Wave 2 hot-path extras: GPU die temp + max cooling cur_state ──
                // Both reads use SysfsProber's cached device lists so the 39 cooling
                // devices are enumerated once, not per tick.
                val dExtras = async {
                    Wave2Extras(
                        gpuDieTempMilliC = sysfsProber.readGpuDieTempMilliC(gpuRootPath),
                        coolingMaxState  = sysfsProber.readMaxCoolingCurState(),
                    )
                }
                SamplerResults(
                    freqs      = dFreqs.await(),
                    cpuReading = dLoads.await(),
                    mem        = dMem.await(),
                    gpu        = dGpu.await(),
                    batt       = dBatt.await(),
                    zones      = dZones.await(),
                    extras     = dExtras.await(),
                )
            }

            // ── Foreground pkg + real FPS (Wave 2) ───────────────────────────────
            // Read from GameFpsSampler's StateFlows (populated when the HUD/overlay
            // poller is active). Cheap non-blocking .value reads. When the sampler is
            // idle these are null/false and the engine falls back to package-less
            // classification + FPS-null behaviour (honest absence).
            val fgPkg = gameFpsSampler.foregroundPkg.value
            val realFps = gameFpsSampler.fps.value
            val realFpsIsReal = gameFpsSampler.isRealFps.value

            emit(
                Telemetry(
                    timestampMs        = now,
                    perCoreCpuFreqKhz  = freqs,
                    perCoreLoadPct     = cpuReading.loads,
                    cpuLoadSource      = cpuReading.source,
                    gpuLoadPct         = gpu.loadPct,
                    gpuFreqHz          = gpu.freqHz,
                    zoneTempsMilliC    = zones,
                    ramTotalKb         = mem.totalKb,
                    ramAvailableKb     = mem.availableKb,
                    batteryTempDeciC   = batt.temperatureDeciC,
                    batteryCurrentUa   = batt.currentUa,
                    batteryVoltageUv   = batt.voltageUv,
                    // fanRpm: only the generic hwmon fan exposes RPM; the Odin's vendor
                    // fan does not, so this stays null there (honest absence) and is
                    // populated only when a hwmon fan1_input was probed.
                    fanRpm             = capabilityProbe.report.value?.fan?.currentRpm,
                    // ── Wave 2 signals the Smart engine consumes ──────────────────
                    foregroundPackage  = fgPkg,
                    gpuDieTempMilliC   = extras.gpuDieTempMilliC,
                    coolingDeviceMaxState = extras.coolingMaxState,
                    realFpsX10         = if (realFpsIsReal && realFps != null) realFps * 10 else null,
                    isRealFps          = realFpsIsReal && realFps != null,
                ),
            )

            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /** Scratch holder so [coroutineScope] results can be destructured. */
    private data class SamplerResults(
        val freqs: List<Int>,
        val cpuReading: CpuLoadReading,
        val mem: MeminfoSampler.MemSample,
        val gpu: GpuLoadSampler.Result,
        val batt: BatterySampler.Sample,
        val zones: List<ZoneTemp>,
        val extras: Wave2Extras,
    )

    /** Hot-path Wave-2 reads bundled so they can run in one concurrent async. */
    private data class Wave2Extras(
        val gpuDieTempMilliC: Int?,
        val coolingMaxState: Int?,
    )

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        const val STRESS_INTERVAL_MS = 250L // 4 Hz

        /**
         * Grace period the shared default stream keeps polling after the LAST
         * subscriber leaves before it stops the upstream loop. A few seconds
         * absorbs brief subscriber churn (navigating Dashboard → Tunes, a config
         * change) without tearing down and re-priming the samplers, while still
         * guaranteeing the loop stops when the app is genuinely backgrounded.
         */
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
    }
}
