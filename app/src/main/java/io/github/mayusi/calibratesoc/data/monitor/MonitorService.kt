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
 */
@Singleton
class MonitorService @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val sysfsProber: SysfsProber,
    private val perCoreFreq: PerCoreFreqSampler,
    private val cpuStat: CpuStatSampler,
    private val meminfo: MeminfoSampler,
    private val gpuLoad: GpuLoadSampler,
    private val battery: BatterySampler,
) {

    fun telemetry(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Telemetry> = flow {
        // Reset the load sampler's baseline on every (re)start so the
        // first sample isn't a delta against ancient state from a prior
        // collect.
        cpuStat.reset()

        // Pull the GPU probe ONCE from the capability report; it doesn't
        // hot-add. The thermal zones DO sometimes appear/disappear, so
        // those re-enumerate each tick (see kdoc above).
        val gpuProbe: GpuProbe? = capabilityProbe.report.value?.gpu

        while (true) {
            val now = System.currentTimeMillis()

            // Run independent I/O-bound samplers concurrently.
            // CpuStatSampler is stateful (delta between calls) so it must not
            // be reordered relative to itself, but running it in parallel with
            // the other samplers is safe — it reads /proc/stat independently.
            // BatterySampler uses Android binder, not sysfs, and is independent.
            val (freqs, loads, mem, gpu, batt, zones) = coroutineScope {
                val dFreqs = async { perCoreFreq.sample() }
                val dLoads = async { cpuStat.sample() }
                val dMem   = async { meminfo.sample() }
                val dGpu   = async { gpuLoad.sample(gpuProbe) }
                val dBatt  = async { battery.sample() }
                // Use the cheap temp-only read — zone list is cached by SysfsProber.
                val dZones = async {
                    sysfsProber.readThermalZoneTemps().map {
                        ZoneTemp(zoneId = it.id, label = it.type, tempMilliC = it.currentTempMilliC)
                    }
                }
                SamplerResults(
                    freqs = dFreqs.await(),
                    loads = dLoads.await(),
                    mem   = dMem.await(),
                    gpu   = dGpu.await(),
                    batt  = dBatt.await(),
                    zones = dZones.await(),
                )
            }

            emit(
                Telemetry(
                    timestampMs = now,
                    perCoreCpuFreqKhz = freqs,
                    perCoreLoadPct = loads,
                    gpuLoadPct = gpu.loadPct,
                    gpuFreqHz = gpu.freqHz,
                    zoneTempsMilliC = zones,
                    ramTotalKb = mem.totalKb,
                    ramAvailableKb = mem.availableKb,
                    batteryTempDeciC = batt.temperatureDeciC,
                    batteryCurrentUa = batt.currentUa,
                    batteryVoltageUv = batt.voltageUv,
                    fanRpm = null, // populated in Phase 4 once fan adapter writers land
                ),
            )

            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /** Scratch holder so [coroutineScope] results can be destructured. */
    private data class SamplerResults(
        val freqs: List<Int>,
        val loads: List<Int>,
        val mem: MeminfoSampler.MemSample,
        val gpu: GpuLoadSampler.Result,
        val batt: BatterySampler.Sample,
        val zones: List<ZoneTemp>,
    )

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        const val STRESS_INTERVAL_MS = 250L // 4 Hz
    }
}
