package io.github.mayusi.calibratesoc.data.presets

import io.github.mayusi.calibratesoc.data.capability.GpuFamily

/**
 * Per-SoC-family tuning heuristics. Layered on top of the generic
 * OPP-table arithmetic — a Snapdragon prime cluster gets weighted
 * differently from a Mali Dimensity little cluster because their power
 * curves don't look the same at equivalent freq percentages.
 *
 * These are NOT device-specific overrides (those live in DeviceAdapter).
 * These are family-wide defaults that improve the generic presets on
 * silicon we've at least seen a few examples of.
 *
 * Verification tier:
 *   - Adreno + Mali = GENERIC_KNOWN_FAMILY (well-documented Linux kernel paths)
 *   - everything else = GENERIC_UNKNOWN_FAMILY (the safety gate fires)
 */
object SocFamilyRules {

    /** Fraction of the highest available freq to cap at for each preset.
     *  Conservative defaults — actual under/overclock numbers are
     *  device-specific and live in the bundled adapter when known. */
    data class Caps(
        val batterySaverPct: Float,
        val balancedPct: Float,
        val performancePct: Float,
        // Max keeps the kernel's stock max. Overclocking beyond
        // cpuinfo_max_freq is a per-device thing (depends on whether
        // the kernel was patched to expose higher OPPs) and lives in
        // community presets, not here.
    )

    /**
     * Return per-cluster caps. Some families benefit from asymmetric
     * tuning — e.g. Snapdragon's prime core scales power steeper than
     * the little cluster, so we cap prime more aggressively at
     * Battery Saver.
     */
    fun capsForCluster(family: GpuFamily, isHighestCluster: Boolean): Caps =
        when (family) {
            GpuFamily.ADRENO -> Caps(
                batterySaverPct = if (isHighestCluster) 0.45f else 0.55f,
                balancedPct = if (isHighestCluster) 0.75f else 0.80f,
                performancePct = 0.95f,
            )
            GpuFamily.MALI -> Caps(
                batterySaverPct = if (isHighestCluster) 0.50f else 0.60f,
                balancedPct = if (isHighestCluster) 0.80f else 0.85f,
                performancePct = 0.95f,
            )
            GpuFamily.POWERVR_OR_MALI_MTK -> Caps(
                batterySaverPct = if (isHighestCluster) 0.50f else 0.60f,
                balancedPct = if (isHighestCluster) 0.80f else 0.85f,
                performancePct = 0.95f,
            )
            GpuFamily.XCLIPSE,
            GpuFamily.UNKNOWN -> Caps(
                // Conservative defaults for silicon we haven't profiled.
                // Battery Saver leaves more headroom; we'd rather
                // under-deliver power savings than crash a kernel we
                // don't fully understand.
                batterySaverPct = 0.55f,
                balancedPct = 0.85f,
                performancePct = 0.95f,
            )
        }

    /** Governor preference per preset, in order of likelihood. We walk
     *  the list and pick the first one that exists in
     *  scaling_available_governors for the device. */
    fun preferredGovernors(family: GpuFamily): GovernorMap = GovernorMap(
        // 'walt' is Qualcomm's scheduler-aware governor used on
        // Snapdragon 8 Elite (Odin 3 firmware) — it's the right pick
        // for both balanced and battery-saver workloads because it
        // already does aggressive freq scaling based on load.
        batterySaver = listOf("powersave", "conservative", "walt", "schedutil"),
        balanced = listOf("walt", "schedutil", "ondemand"),
        performance = listOf("performance", "walt", "schedutil"),
    )

    fun verificationTierFor(family: GpuFamily): VerificationTier = when (family) {
        GpuFamily.ADRENO, GpuFamily.MALI -> VerificationTier.GENERIC_KNOWN_FAMILY
        GpuFamily.POWERVR_OR_MALI_MTK,
        GpuFamily.XCLIPSE,
        GpuFamily.UNKNOWN -> VerificationTier.GENERIC_UNKNOWN_FAMILY
    }

    data class GovernorMap(
        val batterySaver: List<String>,
        val balanced: List<String>,
        val performance: List<String>,
    )
}
