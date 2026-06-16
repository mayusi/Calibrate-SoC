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
    // ── Smart-AutoTDP Wave 1 seams (all optional; populated by later waves) ──────
    //
    // These fields default to null/absent so every existing caller (the monitor,
    // tests, benchmarks) constructs Telemetry exactly as before. The Smart AutoTDP
    // band controller consumes them when present and falls back to the legacy
    // [zoneTempsMilliC] / package-less behaviour when they are absent. Wave 2 wires
    // the real probes (GPU die temp, cooling-device state) into these slots without
    // any further engine change.
    //
    /**
     * Foreground app package name (the classifier ANCHOR). Null when unknown.
     * Supplied by the daemon from [ForegroundAppWatcher]; the pure engine never
     * reaches for Android context to obtain it.
     */
    val foregroundPackage: String? = null,
    /**
     * GPU die temperature in milli-°C (e.g. kgsl-3d0/temp). Null when not yet
     * probed. When present the thermal pre-empt path prefers this over the skin
     * zones; when absent it falls back to the hottest [zoneTempsMilliC] entry.
     * WAVE 2 populates this.
     */
    val gpuDieTempMilliC: Int? = null,
    /**
     * Maximum cooling_device cur_state observed this tick (across all cooling
     * devices). A value greater than 0 means the kernel is actively throttling NOW
     * (immediate-tighten signal). Null when not probed. WAVE 2 populates this.
     */
    val coolingDeviceMaxState: Int? = null,
    /**
     * Real measured frame-rate × 10 (e.g. 599 = 59.9 fps), or null when no real FPS
     * source is available (the common case). Used ONLY as a don't-tighten-below-
     * playable floor, NEVER for classification. Carried × 10 to avoid a float.
     */
    val realFpsX10: Int? = null,
    /**
     * True only when [realFpsX10] came from a genuine SurfaceFlinger/vsync source.
     * The engine reads FPS exclusively when this is true (honesty: never act on a
     * fabricated frame-rate). WAVE 2+ sets this; defaults false.
     */
    val isRealFps: Boolean = false,
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
