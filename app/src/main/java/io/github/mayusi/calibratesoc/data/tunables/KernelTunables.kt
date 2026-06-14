package io.github.mayusi.calibratesoc.data.tunables

/**
 * Path-builders for the extended kernel-manager taxonomy. Every helper
 * here produces a [TunableId] that routes through [TunableWriter] so the
 * snapshot-then-write invariant holds automatically for ALL new knobs.
 *
 * Organisation mirrors the tunable categories:
 *   CPU core online, governor tunables, time-in-state (read-only)
 *   GPU power level, throttling, idle, devfreq governor tunables
 *   Thermal zone mode, trip points, cooling device state
 *   Memory / bus devfreq
 *   I/O scheduler, readahead, nr_requests
 *   VM sysctls
 *   Kernel scheduler sysctls (lower priority)
 *   SchedTune / uclamp / input boost
 *   Custom sysfs rule (any /sys|/proc path)
 *
 * The companion to this file is [TunableMetadata] which holds the risk
 * levels, value kinds, and validation rules for each knob category.
 */
object KernelTunables {

    // =========================================================================
    // CPU
    // =========================================================================

    /**
     * CPU core online/offline control. cpu0 MUST NOT be offlined — the
     * caller and [TunableMetadata] both enforce this; we only build the
     * path here.
     */
    fun cpuOnline(cpu: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/devices/system/cpu/cpu$cpu/online",
    )

    /**
     * A single per-governor tunable file. Probe discovers these
     * dynamically by listing the governor's sub-directory; each file
     * becomes its own TunableId so the snapshot+revert machinery handles
     * every tunable individually.
     *
     * Example: cpuGovernorTunable(4, "schedutil", "rate_limit_us")
     *   → /sys/devices/system/cpu/cpufreq/policy4/schedutil/rate_limit_us
     */
    fun cpuGovernorTunable(policyId: Int, governor: String, tunableName: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/devices/system/cpu/cpufreq/policy$policyId/$governor/$tunableName",
    )

    /**
     * Time-in-state is READ-ONLY monitoring — no TunableId for writes.
     * The probe reads it; the path is surfaced here for the monitoring UI.
     */
    fun cpuTimeInStatePath(policyId: Int): String =
        "/sys/devices/system/cpu/cpufreq/policy$policyId/stats/time_in_state"

    // =========================================================================
    // GPU (Adreno kgsl)
    // =========================================================================

    /**
     * Adreno power level min (0 = highest performance, numLevels-1 = lowest).
     * Inverted compared to frequency — lower index = faster.
     */
    fun adrenoMinPowerLevel(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/min_pwrlevel",
    )

    /** Adreno power level max — already present in Tunables as adrenoMaxPowerLevel. */
    fun adrenoMaxPowerLevel(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/max_pwrlevel",
    )

    fun adrenoDefaultPowerLevel(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/default_pwrlevel",
    )

    /**
     * GPU thermal throttling gate. Writing "0" disables GPU thermal
     * protection — HIGH risk, flagged accordingly in [TunableMetadata].
     */
    fun gpuThrottling(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/throttling",
    )

    /** Forces GPU clocks on (prevents idle gating). LOW-MED risk. */
    fun gpuForceClkOn(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/force_clk_on",
    )

    /** GPU idle timeout in milliseconds before clock-gating. LOW-MED risk. */
    fun gpuIdleTimer(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/idle_timer",
    )

    /**
     * GPU devfreq governor-specific tunable. Same dynamic-probe pattern
     * as cpuGovernorTunable: each file in the governor sub-dir is its
     * own TunableId.
     *
     * Example: gpuDevfreqGovernorTunable("/sys/class/kgsl/kgsl-3d0", "msm-adreno-tz", "upthreshold")
     *   → /sys/class/kgsl/kgsl-3d0/devfreq/msm-adreno-tz/upthreshold
     */
    fun gpuDevfreqGovernorTunable(
        gpuRootPath: String,
        governor: String,
        tunableName: String,
    ) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/devfreq/$governor/$tunableName",
    )

    // =========================================================================
    // Thermal
    // =========================================================================

    /**
     * Thermal zone mode: "enabled" / "disabled". Disabling a zone removes
     * its thermal throttle — HIGH risk.
     */
    fun thermalZoneMode(zoneId: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/class/thermal/thermal_zone$zoneId/mode",
    )

    /**
     * Thermal trip point temperature in millidegrees Celsius. Writing a
     * HIGHER value raises the throttle threshold — DANGEROUS.
     */
    fun thermalTripPointTemp(zoneId: Int, tripIndex: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/class/thermal/thermal_zone$zoneId/trip_point_${tripIndex}_temp",
    )

    /**
     * Cooling device current state. 0 = uncapped (removes thermal cap).
     * HIGH risk.
     */
    fun coolingDeviceCurState(deviceId: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/class/thermal/cooling_device$deviceId/cur_state",
    )

    // =========================================================================
    // Memory / bus devfreq
    // =========================================================================

    fun devfreqMinFreq(devfreqDevice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/class/devfreq/$devfreqDevice/min_freq",
    )

    fun devfreqMaxFreq(devfreqDevice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/class/devfreq/$devfreqDevice/max_freq",
    )

    fun devfreqGovernor(devfreqDevice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/class/devfreq/$devfreqDevice/governor",
    )

    // =========================================================================
    // I/O
    // =========================================================================

    /**
     * Block device I/O scheduler. Active scheduler is enclosed in
     * [brackets] in the file; write the bare scheduler name (no brackets).
     */
    fun ioScheduler(blockDevice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/block/$blockDevice/queue/scheduler",
    )

    fun ioReadAheadKb(blockDevice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/block/$blockDevice/queue/read_ahead_kb",
    )

    fun ioNrRequests(blockDevice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/block/$blockDevice/queue/nr_requests",
    )

    // =========================================================================
    // VM sysctls  (/proc/sys/vm/...)
    // Note: /proc/sys writes go through the same RootWriter file-write
    // path as /sys — RootWriter uses `printf %s value > path` without
    // any sysfs-prefix restriction, so /proc paths work identically.
    // =========================================================================

    fun vmSwappiness() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/vm/swappiness",
    )

    fun vmVfsCachePressure() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/vm/vfs_cache_pressure",
    )

    fun vmDirtyRatio() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/vm/dirty_ratio",
    )

    fun vmDirtyBackgroundRatio() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/vm/dirty_background_ratio",
    )

    // =========================================================================
    // Kernel scheduler sysctls  (/proc/sys/kernel/sched_*)
    // Lower priority — path-builders + probe only; mark lower-priority in
    // TunableMetadata.
    // =========================================================================

    fun schedMigrationCostNs() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/kernel/sched_migration_cost_ns",
    )

    fun schedMinGranularityNs() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/kernel/sched_min_granularity_ns",
    )

    fun schedWakeupGranularityNs() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/proc/sys/kernel/sched_wakeup_granularity_ns",
    )

    // =========================================================================
    // SchedTune / UCLAMP / boost (cgroup — root only)
    // =========================================================================

    /**
     * schedtune.boost for a cgroup slice (0–100). Used on older kernels
     * that expose /dev/stune; newer kernels replaced this with cpu.uclamp.
     * The probe in [SysfsProber.probeSchedBoostInterface] determines which
     * interface exists.
     *
     * /dev/stune writes go through RootWriter unchanged — a /dev file is
     * still just a file write on Android.
     */
    fun schedtuneBoost(slice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/dev/stune/$slice/schedtune.boost",
    )

    fun schedtunePreferIdle(slice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/dev/stune/$slice/schedtune.prefer_idle",
    )

    /** cpu.uclamp.min — newer kernels (cpuctl/top-app etc.). */
    fun uclampMin(slice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/dev/cpuctl/$slice/cpu.uclamp.min",
    )

    fun uclampMax(slice: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/dev/cpuctl/$slice/cpu.uclamp.max",
    )

    // =========================================================================
    // Input boost  (/sys/module/cpu_boost/parameters/)
    // =========================================================================

    /**
     * Per-cluster input boost frequency. Value is a space/colon separated
     * map (e.g. "0:1209600 4:0 7:0"). Probe checks whether the module
     * parameters directory exists.
     */
    fun inputBoostFreq() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/module/cpu_boost/parameters/input_boost_freq",
    )

    fun inputBoostMs() = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/module/cpu_boost/parameters/input_boost_ms",
    )

    // =========================================================================
    // Custom sysfs rule (user-supplied path)
    // =========================================================================

    /**
     * Power-user escape hatch: any /sys or /proc path becomes a tunable.
     * Validation in [TunableMetadata.validateCustomSysfsPath] enforces
     * the /sys|/proc prefix and rejects paths that look like device nodes
     * or procfs entries that should not be written (e.g. /proc/sysrq-trigger).
     *
     * @throws IllegalArgumentException if the path fails basic safety checks.
     *   Callers that want a soft error instead of an exception should call
     *   [TunableMetadata.validateCustomSysfsPath] first.
     */
    fun customSysfsRule(path: String): TunableId {
        TunableMetadata.validateCustomSysfsPath(path)
            ?.let { error -> throw IllegalArgumentException(error) }
        return TunableId(kind = TunableKind.SYSFS, target = path)
    }

    // =========================================================================
    // Known cgroup slices (passed to schedtuneBoost / uclampMin etc.)
    // =========================================================================

    object Slices {
        const val TOP_APP = "top-app"
        const val FOREGROUND = "foreground"
        const val BACKGROUND = "background"
        const val SYSTEM_BACKGROUND = "system-background"
    }
}
