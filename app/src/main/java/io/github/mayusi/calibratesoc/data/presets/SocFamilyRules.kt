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
 * Preset taxonomy (use-case-driven, 6 built-ins):
 *   Cool & Quiet — Max Battery   : light 2D/menus/retro, silent fan, 2x battery
 *   Light Emulation (N64/PSP/DC) : moderate CPU systems, cool & quiet
 *   PS2 / GameCube — Sustained   : heavy emu, capped below throttle point
 *   Switch / Heavy — Performance : Switch/demanding, leave throttle margin
 *   Anti-Throttle — Sustained Max: highest OPP step sustainable indefinitely
 *   Stock (undo tune)            : reset to kernel max, cores can idle again
 *
 * Cluster role notation (asymmetric capping):
 *   prime   = policy whose highest OPP is the largest (one core, power hog)
 *   gold    = middle cluster(s)
 *   little  = lowest OPP cluster
 *
 * Verification tier:
 *   Adreno + Mali = GENERIC_KNOWN_FAMILY
 *   everything else = GENERIC_UNKNOWN_FAMILY (extra safety gate)
 *
 * BUG FIX (v0.1.10): `powersave` governor is permanently REMOVED from all
 * preset governor lists. `powersave` PINS the CPU to its lowest OPP at all
 * times — under emulator load this causes stuttering and audio crackle
 * because the kernel is prohibited from scaling up even on 100% load.
 * Use `conservative` for battery presets instead: it defaults low but
 * scales up on real load, giving idle-battery savings without stutter.
 */
object SocFamilyRules {

    /**
     * Fractional caps per use-case preset, indexed by [PresetKind].
     *
     * Three values per kind: little cluster, gold/mid cluster, prime cluster.
     * When a device has only 2 clusters, the generator maps:
     *   - 2-cluster: low → little caps, high → prime caps (no gold)
     *   - 1-cluster: uses little caps for everything
     *
     * These fractions are applied to each cluster's own top OPP, then
     * snapped to the nearest real OPP step. The generator never writes
     * a frequency that isn't in availableFreqsKhz.
     */
    data class ClusterCaps(
        /** Fraction of the little cluster's top OPP. */
        val littlePct: Float,
        /** Fraction of the gold/mid cluster's top OPP. */
        val goldPct: Float,
        /** Fraction of the prime cluster's top OPP (most aggressive cap
         *  on battery presets because prime has the steepest power curve). */
        val primePct: Float,
        /** Fraction of GPU top OPP. */
        val gpuPct: Float,
    )

    enum class PresetKind {
        COOL_AND_QUIET,       // ~50-55% / ~50% / ~45%  — menus, 2D retro, video
        LIGHT_EMULATION,      // ~65-70% / ~60-65% / ~55-60% — N64/PSP/DC
        PS2_GC_SUSTAINED,     // ~68-75% all clusters    — capped below throttle
        SWITCH_HEAVY,         // ~88-92% / ~88-92% / ~88-92% — Switch/demanding
        ANTI_THROTTLE,        // ~77-83% tuned           — highest sustainable OPP
        STOCK,                // 100%                    — undo tune / reset
    }

    /**
     * Return cluster caps for a given [PresetKind] and SoC family.
     * Mali and unknown families use slightly more conservative defaults
     * because we have less thermal data on their power curves.
     */
    fun capsFor(kind: PresetKind, family: GpuFamily): ClusterCaps = when (kind) {
        PresetKind.COOL_AND_QUIET -> when (family) {
            GpuFamily.ADRENO -> ClusterCaps(
                littlePct = 0.54f, goldPct = 0.50f, primePct = 0.45f, gpuPct = 0.45f,
            )
            else -> ClusterCaps(
                littlePct = 0.56f, goldPct = 0.52f, primePct = 0.50f, gpuPct = 0.48f,
            )
        }
        PresetKind.LIGHT_EMULATION -> when (family) {
            GpuFamily.ADRENO -> ClusterCaps(
                littlePct = 0.68f, goldPct = 0.63f, primePct = 0.57f, gpuPct = 0.62f,
            )
            else -> ClusterCaps(
                littlePct = 0.70f, goldPct = 0.65f, primePct = 0.60f, gpuPct = 0.65f,
            )
        }
        PresetKind.PS2_GC_SUSTAINED -> when (family) {
            GpuFamily.ADRENO -> ClusterCaps(
                littlePct = 0.72f, goldPct = 0.70f, primePct = 0.70f, gpuPct = 0.72f,
            )
            else -> ClusterCaps(
                littlePct = 0.75f, goldPct = 0.73f, primePct = 0.72f, gpuPct = 0.74f,
            )
        }
        PresetKind.SWITCH_HEAVY -> when (family) {
            GpuFamily.ADRENO -> ClusterCaps(
                littlePct = 0.90f, goldPct = 0.90f, primePct = 0.90f, gpuPct = 0.92f,
            )
            else -> ClusterCaps(
                littlePct = 0.90f, goldPct = 0.90f, primePct = 0.90f, gpuPct = 0.90f,
            )
        }
        PresetKind.ANTI_THROTTLE -> when (family) {
            // On SD8Gen2 (kalama): prime ~2592/3187 = 0.814, gold ~1920/2803 = 0.685,
            // little ~1555/2016 = 0.771 — leave ≥1 OPP step below max so the
            // governor has headroom before it hits the thermal trip.
            GpuFamily.ADRENO -> ClusterCaps(
                littlePct = 0.78f, goldPct = 0.77f, primePct = 0.81f, gpuPct = 0.82f,
            )
            else -> ClusterCaps(
                littlePct = 0.80f, goldPct = 0.80f, primePct = 0.80f, gpuPct = 0.82f,
            )
        }
        PresetKind.STOCK -> ClusterCaps(
            // Stock always targets 100% — handled specially by the generator
            // to use hardwareLimitsKhz.highKhz rather than a % of availableFreqs.
            littlePct = 1.0f, goldPct = 1.0f, primePct = 1.0f, gpuPct = 1.0f,
        )
    }

    /**
     * Governor preference per preset, in order of likelihood. We walk
     * the list and pick the first one that exists in
     * scaling_available_governors for the device.
     *
     * IMPORTANT: `powersave` is permanently excluded from ALL lists.
     * `powersave` pins the CPU to its minimum OPP at all times, causing
     * emulator stuttering and audio crackle under load. For low-power
     * workloads, `conservative` is the correct choice: it defaults low
     * but scales up on demand.
     */
    fun preferredGovernors(family: GpuFamily): GovernorMap = when (family) {
        GpuFamily.ADRENO -> GovernorMap(
            // `walt` is Qualcomm's scheduler-aware governor on SD8Elite.
            // `conservative` scales up on load, defaults low — correct for
            // battery saving WITHOUT pinning clocks.
            coolAndQuiet = listOf("conservative", "schedutil", "walt"),
            lightEmulation = listOf("schedutil", "walt", "conservative"),
            ps2GcSustained = listOf("walt", "schedutil"),
            switchHeavy = listOf("walt", "performance", "schedutil"),
            antiThrottle = listOf("walt", "schedutil"),
            stock = listOf("schedutil", "walt"),
        )
        else -> GovernorMap(
            coolAndQuiet = listOf("conservative", "schedutil"),
            lightEmulation = listOf("schedutil", "conservative"),
            ps2GcSustained = listOf("schedutil"),
            switchHeavy = listOf("performance", "schedutil"),
            antiThrottle = listOf("schedutil"),
            stock = listOf("schedutil"),
        )
    }

    fun verificationTierFor(family: GpuFamily): VerificationTier = when (family) {
        GpuFamily.ADRENO, GpuFamily.MALI -> VerificationTier.GENERIC_KNOWN_FAMILY
        GpuFamily.POWERVR_OR_MALI_MTK,
        GpuFamily.XCLIPSE,
        GpuFamily.UNKNOWN -> VerificationTier.GENERIC_UNKNOWN_FAMILY
    }

    data class GovernorMap(
        val coolAndQuiet: List<String>,
        val lightEmulation: List<String>,
        val ps2GcSustained: List<String>,
        val switchHeavy: List<String>,
        val antiThrottle: List<String>,
        val stock: List<String>,
    )
}
