package io.github.mayusi.calibratesoc.data.tunables

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * Path-builders + factory helpers that turn the raw [CapabilityReport]
 * surface into pre-formed [TunableId]s. Keeps every other module from
 * hand-stitching "/sys/devices/system/cpu/cpufreq/policy${id}/..."
 * strings — that string-building lives here ONCE so a typo can't sneak
 * past code review.
 *
 * The "current value" caches on the wrappers (cpuPolicy.currentMaxKhz
 * etc.) come straight from the probe — they're a snapshot of the moment
 * the probe ran, not a live read. Callers that need fresh data go
 * through the writer's `read()` method.
 */
object Tunables {

    // --- CPU policy ----------------------------------------------------

    fun cpuMinFreq(policyId: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_min_freq",
    )

    fun cpuMaxFreq(policyId: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq",
    )

    fun cpuGovernor(policyId: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_governor",
    )

    fun cpuOnline(cpu: Int) = TunableId(
        kind = TunableKind.SYSFS,
        target = "/sys/devices/system/cpu/cpu$cpu/online",
    )

    // --- GPU -----------------------------------------------------------

    /** Adreno: kgsl-3d0/devfreq/min_freq. Mali: <maliDir>/min_freq.
     *  Caller passes the rootPath from the GpuProbe. */
    fun gpuMinFreq(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/devfreq/min_freq",
    )

    fun gpuMaxFreq(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/devfreq/max_freq",
    )

    fun gpuGovernor(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/devfreq/governor",
    )

    /** Adreno-specific: clamps power level (0=highest). NULL on Mali. */
    fun adrenoMaxPowerLevel(gpuRootPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = "$gpuRootPath/max_pwrlevel",
    )

    // --- Fan / hwmon PWM ----------------------------------------------

    fun pwmDuty(hwmonPwmPath: String) = TunableId(
        kind = TunableKind.SYSFS,
        target = hwmonPwmPath,
    )

    // --- Vendor preset keys -------------------------------------------

    fun settingsSystemKey(key: String) = TunableId(
        kind = TunableKind.SETTINGS_SYSTEM,
        target = key,
    )

    fun vendorIntent(action: String) = TunableId(
        kind = TunableKind.VENDOR_INTENT,
        target = action,
    )

    // --- Helpers for the UI layer -------------------------------------

    /**
     * Pre-flight: would writing this tunable succeed? Returns the
     * privilege-tier reason if not. Lets the Tune UI grey out controls
     * with an explanation instead of letting the user mash a slider
     * that's going to silently no-op.
     *
     * UNLOCK TIER: when [CapabilityReport.sysfsDirectlyWritable] is true
     * (the one-time unlock script ran + cpufreq nodes are chmod 666) AND
     * the target path is one the unlock script actually chmod'd
     * ([isUnlockCoveredNode]), we return null — the node is live-writable
     * via [UnlockedFileWriter] without root.
     *
     * SHIZUKU TIER: when privilege is SHIZUKU AND [shizukuProbedWritable] is
     * true for this exact path, we return null — the per-node probe confirmed
     * shell can write this node on this device. When shizukuProbedWritable is
     * false the honest message says "Shizuku connected but kernel blocks shell
     * writes to this node" rather than a vague "permission denied".
     *
     * @param shizukuProbedWritable Pass [ShizukuNodeCache.isCachedWritable]
     *   for id.target when the privilege tier is SHIZUKU. Null (default) means
     *   "probe not available" — treated as unwritable.
     */
    fun whyWriteDenied(
        id: TunableId,
        report: CapabilityReport,
        shizukuProbedWritable: Boolean? = null,
    ): String? {
        if (id.kind == TunableKind.SETTINGS_SYSTEM || id.kind == TunableKind.VENDOR_INTENT) {
            // The Settings ROW is always writable (given WRITE_SECURE_SETTINGS) — so
            // there is no DENIAL to report here. HONESTY CAVEAT: a successful key
            // write does NOT guarantee a kernel effect. On vendors that subscribe to
            // the key (AYN/Retroid fan_mode/performance_mode) it drives the kernel;
            // on vendors that drive fan/perf via a private binder (e.g. AYANEO) the
            // key write is INERT. So callers must NOT assume liveness from a null
            // here. The live cpufreq path is decided entirely by
            // WriterRegistry.isLiveWritable on the SYSFS node, which never routes a
            // vendor key to a live cpufreq writer.
            return null // no write-denial: the Settings row itself is reachable
        }
        // TunableKind.SYSFS path:
        if (report.sysfsDirectlyWritable && isUnlockCoveredNode(id.target)) {
            // Unlock script has chmod'd this exact node family — live write allowed.
            return null
        }
        return when (report.privilege) {
            io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT -> null
            io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.VENDOR_SETTINGS ->
                "Direct sysfs writes need root. Use Generate script and run it via your device's Settings > Run script as Root."
            io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.SHIZUKU ->
                when (shizukuProbedWritable) {
                    true  -> null // probe confirmed: shell CAN write this node
                    false ->
                        "Shizuku is connected but this device's kernel blocks shell writes to ${id.target} " +
                            "(vendor SELinux policy). Monitoring + vendor settings only for this node."
                    null  ->
                        "Shizuku permission granted but this node has not been probed yet. " +
                            "Run the Shizuku capability check to discover which nodes are shell-writable."
                }
            io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.NONE ->
                "Needs root (Magisk / KernelSU), or use Generate script and run via your device's Settings > Run script as Root."
        }
    }

    /**
     * Returns true when [path] is a node family that the unlock script
     * (`AdvancedPermissionsScript.deploy()`) chmod 666s. Used by
     * [whyWriteDenied] to route chmod'd nodes to [UnlockedFileWriter]
     * instead of [NoopWriter] on the "unlocked but no root" tier.
     *
     * Keep in sync with the chmod blocks in AdvancedPermissionsScript.
     *
     * NOT included (script-only or root-only):
     *   procfs (/proc/sys/vm, /proc/sys/kernel) — SELinux denial
     *   cgroup (/dev/stune, /dev/cpuctl) — script route unverified
     *   thermal (/sys/class/thermal) — never (hardware damage risk)
     */
    fun isUnlockCoveredNode(path: String): Boolean {
        return path.matches(Regex("/sys/devices/system/cpu/cpufreq/policy\\d+/scaling_(max|min)_freq")) ||
            path.matches(Regex("/sys/devices/system/cpu/cpufreq/policy\\d+/scaling_governor")) ||
            path.matches(Regex("/sys/devices/system/cpu/cpu\\d+/online")) ||
            // CPU governor tunable dirs: policy*/schedutil/*, policy*/walt/*, policy*/interactive/*
            path.matches(Regex("/sys/devices/system/cpu/cpufreq/policy\\d+/[^/]+/[^/]+")) ||
            // GPU devfreq (kgsl or mali)
            path.matches(Regex("/sys/class/kgsl/kgsl-3d0/devfreq/(min_freq|max_freq|governor)")) ||
            path.matches(Regex("/sys/class/devfreq/.*mali.*/\\w+")) ||
            path.matches(Regex("/sys/class/kgsl/kgsl-3d0/(max_gpuclk|min_pwrlevel|max_pwrlevel)")) ||
            // Adreno extras
            path.matches(Regex("/sys/class/kgsl/kgsl-3d0/(throttling|force_clk_on|idle_timer|default_pwrlevel)")) ||
            // DDR/bus devfreq
            path.matches(Regex("/sys/class/devfreq/[^/]+/(min_freq|max_freq|governor)")) ||
            // I/O block devices
            path.matches(Regex("/sys/block/[^/]+/queue/(scheduler|read_ahead_kb|nr_requests)")) ||
            // Input boost
            path.matches(Regex("/sys/module/cpu_boost/parameters/(input_boost_freq|input_boost_ms)"))
    }
}
