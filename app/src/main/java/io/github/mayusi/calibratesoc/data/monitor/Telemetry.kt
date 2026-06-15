package io.github.mayusi.calibratesoc.data.monitor

/**
 * One sample of live device state. Emitted at the [MonitorService]'s
 * sample rate (1 Hz dashboard, 4 Hz stress mode). All values are
 * snapshots — nothing in here is a rolling average.
 *
 * Missing data is represented with empty lists / nulls rather than
 * sentinels so the UI can render "—" instead of a wrong number.
 *
 * ## cpuLoadSource
 *
 * [cpuLoadSource] indicates how [perCoreLoadPct] was obtained. When it is
 * [CpuLoadReading.Source.FREQ_PROXY] the values are a frequency-ratio
 * approximation, not true jiffie-based utilisation. The HUD renders a "~"
 * prefix and AutoTDP applies a more conservative saturation threshold.
 * When it is [CpuLoadReading.Source.UNAVAILABLE], [perCoreLoadPct] is empty
 * and the engine holds without acting on phantom zeros.
 */
data class Telemetry(
    val timestampMs: Long,
    val perCoreCpuFreqKhz: List<Int>,
    val perCoreLoadPct: List<Int>,
    /** How [perCoreLoadPct] was obtained. Default is UNAVAILABLE for callers
     *  (tests, benchmarks) that construct Telemetry without a real load source. */
    val cpuLoadSource: CpuLoadReading.Source = CpuLoadReading.Source.UNAVAILABLE,
    val gpuLoadPct: Int?,
    val gpuFreqHz: Long?,
    val zoneTempsMilliC: List<ZoneTemp>,
    val ramTotalKb: Long,
    val ramAvailableKb: Long,
    val batteryTempDeciC: Int?,
    val batteryCurrentUa: Long?,
    val batteryVoltageUv: Long?,
    val fanRpm: Int?,
)

data class ZoneTemp(
    val zoneId: Int,
    val label: String,
    val tempMilliC: Int,
)

/** Convenience: instantaneous power draw in milliwatts, or null when
 *  either input is missing. Sign convention: positive = discharging. */
val Telemetry.batteryDrawMilliW: Long?
    get() {
        val ua = batteryCurrentUa ?: return null
        val uv = batteryVoltageUv ?: return null
        // µA * µV / 1_000_000 = µW; then /1_000 = mW.
        // BatteryManager reports current as negative when discharging on
        // some OEMs and positive on others; we normalise to "positive =
        // discharging" by flipping sign when the value is negative.
        val absUa = if (ua < 0) -ua else ua
        return (absUa * uv) / 1_000_000_000L
    }

/** True when [perCoreLoadPct] values came from a real /proc/stat read
 *  (either root or direct). False for freq-proxy or unavailable. */
val Telemetry.hasTrueLoadData: Boolean
    get() = cpuLoadSource == CpuLoadReading.Source.ROOT_PROC_STAT ||
            cpuLoadSource == CpuLoadReading.Source.DIRECT_PROC_STAT
