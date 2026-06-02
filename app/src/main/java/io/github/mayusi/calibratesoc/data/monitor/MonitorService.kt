package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.SysfsProber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * One quirk worth flagging: thermal-zone enumeration is re-run on every
 * sample, because some kernels add hwmon nodes hot (USB-C dock, charger
 * insertion). The cost is a handful of `readlink` ops per zone — well
 * under 1 ms in practice.
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
            val freqs = perCoreFreq.sample()
            val loads = cpuStat.sample()
            val mem = meminfo.sample()
            val gpu = gpuLoad.sample(gpuProbe)
            val batt = battery.sample()
            val zones = sysfsProber.probeThermalZones().map {
                ZoneTemp(zoneId = it.id, label = it.type, tempMilliC = it.currentTempMilliC)
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
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        const val STRESS_INTERVAL_MS = 250L // 4 Hz
    }
}
