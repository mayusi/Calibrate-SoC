package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.SysfsProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
) {

    fun telemetry(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Telemetry> = flow {
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
    }
}
