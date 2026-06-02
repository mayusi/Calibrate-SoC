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
     */
    fun whyWriteDenied(id: TunableId, report: CapabilityReport): String? {
        return when (id.kind) {
            TunableKind.SETTINGS_SYSTEM, TunableKind.VENDOR_INTENT -> null // always reachable
            TunableKind.SYSFS -> when (report.privilege) {
                io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT -> null
                io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.AYN_SETTINGS ->
                    "Direct sysfs writes need root. Use Generate AYN script and run it via Odin Settings."
                io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.SHIZUKU ->
                    "Shizuku kernel writes pending UserService support — use Generate AYN script for now."
                io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.NONE ->
                    "Needs root (Magisk / KernelSU), or use Generate AYN script and run via Odin Settings."
            }
        }
    }
}
