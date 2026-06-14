package io.github.mayusi.calibratesoc.data.presets

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.devicedb.CommunityPreset
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.remote.RemoteContentRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal preset generator. Given a [CapabilityReport], produces the
 * full list of presets the user can choose from on THIS device:
 *
 *   1. Community Tuned — bundled per-device recipes (TheOldTaylor's
 *      Odin 3 underclocks, anything else we've curated). Always wins
 *      when present.
 *   2. Remote community presets — fetched from `content/presets.json`
 *      on the main branch via [RemoteContentRepository]. Surfaced as
 *      [VerificationTier.GENERIC_UNKNOWN_FAMILY] ("Community (unverified)")
 *      so they go through the EXTRA "unknown device — I accept the risk"
 *      confirm gate before Apply. Never auto-applied.
 *   3. Built-in algorithmic — Battery Saver / Balanced / Performance /
 *      Max, generated from the OPP table the kernel reports + SoC-family
 *      heuristics. Works on ANY device, including ones we've never
 *      seen, because everything is derived from kernel-published values.
 *   4. User Custom — saved via the Tune UI. (Phase 4 follow-up.)
 *
 * Design intent: we never hard-code "Snapdragon 8 Elite top freq = 4320
 * MHz". The kernel tells us its OPP table; we cap a percentage of the
 * highest entry. That's what makes the same code work on an unknown
 * MediaTek phone, a Tegra Switch homebrew device, or a brand-new AYN
 * model we haven't researched yet.
 */
@Singleton
class PresetGenerator @Inject constructor(
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val remoteContent: RemoteContentRepository,
) {

    /** Full preset list ready to render in the Tune UI, ordered:
     *  Community Tuned first, then remote community, then the four built-ins. */
    fun presetsFor(report: CapabilityReport): List<Preset> {
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        return buildList {
            addAll(communityPresetsFor(adapter))
            addAll(remoteCommunityPresets())
            addAll(builtinPresetsFor(report, adapter))
        }
    }

    // --- Community Tuned (bundled) ------------------------------------

    private fun communityPresetsFor(adapter: DeviceAdapter?): List<Preset> {
        if (adapter == null) return emptyList()
        return adapter.communityPresets.map { c -> c.toPreset(adapter.key) }
    }

    // --- Remote community presets ------------------------------------

    /**
     * Returns remote community presets (if any) fetched from the OTA channel.
     *
     * These are deliberately surfaced as [VerificationTier.GENERIC_UNKNOWN_FAMILY]
     * ("Community (unverified)") so the user sees the EXTRA confirm dialog before
     * Apply — the same gate the UI already uses for unknown-device built-in presets.
     * They are never auto-applied.
     *
     * The [RemoteContentRepository] already validated + sanitized every string
     * field. We re-enforce the verification tier here to guarantee it regardless
     * of what the JSON contained.
     */
    private fun remoteCommunityPresets(): List<Preset> =
        remoteContent.remotePresets().map { p ->
            // Force GENERIC_UNKNOWN_FAMILY so the extra confirm gate is ALWAYS
            // shown for remote content, even if the JSON claimed a higher tier.
            p.copy(verification = VerificationTier.GENERIC_UNKNOWN_FAMILY)
        }

    private fun CommunityPreset.toPreset(adapterKey: String): Preset = Preset(
        id = "${adapterKey}_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
        name = name,
        description = description,
        verification = VerificationTier.COMMUNITY_TUNED,
        sourceUrl = sourceUrl,
        cpuPolicyMaxKhz = cpuPolicyMaxKhz.mapKeys { it.key.toInt() },
    )

    // --- Built-in algorithmic -----------------------------------------

    private fun builtinPresetsFor(report: CapabilityReport, adapter: DeviceAdapter?): List<Preset> {
        val policies = report.cpuPolicies
        if (policies.isEmpty()) return emptyList()

        val family = report.soc.gpuFamily
        val tier = SocFamilyRules.verificationTierFor(family)
        // Highest cluster = the policy whose top OPP is biggest. Used
        // to weight "this is the prime/big cluster" asymmetric capping.
        val highestPolicyId = policies.maxByOrNull { it.availableFreqsKhz.lastOrNull() ?: 0 }?.policyId
        val governors = SocFamilyRules.preferredGovernors(family)
        val available = policies.flatMap { it.availableGovernors }.toSet()

        // GPU caps mirror CPU pcts. The GPU probe gives us the actual
        // OPP list — same snap-to-step semantics so we never write an
        // in-between freq the kernel would reject.
        val gpu = report.gpu

        return listOf(
            algorithmicPreset(
                id = "builtin_battery_saver",
                name = "Battery Saver",
                description = batterySaverDescription(family, tier),
                tier = tier,
                family = family,
                policies = policies,
                highestPolicyId = highestPolicyId,
                gpu = gpu,
                gpuPct = 0.50f,
                picker = { caps, _ -> caps.batterySaverPct },
                governorChoice = governors.batterySaver,
                availableGovernors = available,
            ),
            algorithmicPreset(
                id = "builtin_balanced",
                name = "Balanced",
                description = "Default-style tune. Caps each cluster around the kernel's middle OPP, governor set to a schedutil/ondemand-class scheduler.",
                tier = tier,
                family = family,
                policies = policies,
                highestPolicyId = highestPolicyId,
                gpu = gpu,
                gpuPct = 0.80f,
                picker = { caps, _ -> caps.balancedPct },
                governorChoice = governors.balanced,
                availableGovernors = available,
            ),
            algorithmicPreset(
                id = "builtin_performance",
                name = "Performance",
                description = "Caps near the kernel's max OPP, governor pinned to performance where available. Higher temps and battery draw; better sustained throughput.",
                tier = tier,
                family = family,
                policies = policies,
                highestPolicyId = highestPolicyId,
                gpu = gpu,
                gpuPct = 0.95f,
                picker = { caps, _ -> caps.performancePct },
                governorChoice = governors.performance,
                availableGovernors = available,
            ),
            maxPreset(
                tier = tier,
                policies = policies,
                gpu = gpu,
                governorChoice = governors.performance,
                availableGovernors = available,
            ),
        )
    }

    private fun algorithmicPreset(
        id: String,
        name: String,
        description: String,
        tier: VerificationTier,
        family: io.github.mayusi.calibratesoc.data.capability.GpuFamily,
        policies: List<CpuPolicyProbe>,
        highestPolicyId: Int?,
        gpu: io.github.mayusi.calibratesoc.data.capability.GpuProbe?,
        gpuPct: Float,
        picker: (SocFamilyRules.Caps, CpuPolicyProbe) -> Float,
        governorChoice: List<String>,
        availableGovernors: Set<String>,
    ): Preset {
        val maxes = mutableMapOf<Int, Int>()
        val mins = mutableMapOf<Int, Int>()
        val govs = mutableMapOf<Int, String>()
        for (policy in policies) {
            val freqs = policy.availableFreqsKhz
            if (freqs.isEmpty()) continue
            val caps = SocFamilyRules.capsForCluster(
                family = family,
                isHighestCluster = policy.policyId == highestPolicyId,
            )
            val target = (freqs.last() * picker(caps, policy)).toInt()
            val snapped = freqs.minByOrNull { kotlin.math.abs(it - target) } ?: continue
            maxes[policy.policyId] = snapped

            // Always reset min to the kernel's lowest OPP. Without
            // this, leftover min from a previous tune (e.g. a HUD ±
            // stepper that pinned min=max) stays in effect and the
            // cores can never idle. That was the bug that made
            // Balanced look like "performance" — min carried over
            // from the previous write, jamming cores at multi-GHz.
            mins[policy.policyId] = freqs.min()

            val pickedGovernor = governorChoice.firstOrNull { it in availableGovernors }
            if (pickedGovernor != null) govs[policy.policyId] = pickedGovernor
        }

        // GPU cap: same percentage-of-OPP-top approach, snapped to the
        // GPU's actual frequency table.
        val (gpuMax, gpuGov) = computeGpuCap(gpu, gpuPct)

        return Preset(
            id = id,
            name = name,
            description = description,
            verification = tier,
            cpuPolicyMaxKhz = maxes,
            cpuPolicyMinKhz = mins,
            cpuPolicyGovernor = govs,
            gpuMaxHz = gpuMax,
            gpuGovernor = gpuGov,
        )
    }

    private fun computeGpuCap(
        gpu: io.github.mayusi.calibratesoc.data.capability.GpuProbe?,
        pct: Float,
    ): Pair<Long?, String?> {
        if (gpu == null) return null to null
        val freqs = gpu.availableFreqsHz
        if (freqs.isEmpty()) return null to null
        val sorted = freqs.sorted()
        val target = (sorted.last() * pct).toLong()
        val snapped = sorted.minByOrNull { kotlin.math.abs(it - target) }
        // For GPU we leave the governor alone unless the kernel publishes
        // a recognisable one — Adreno's msm-adreno-tz scales much better
        // than `performance` for real workloads, so blindly forcing
        // "performance" would HURT thermals on most devices.
        val gov = gpu.availableGovernors
            .firstOrNull { it in setOf("msm-adreno-tz", "simple_ondemand", gpu.currentGovernor) }
            ?.takeIf { pct >= 0.85f }
        return snapped to gov
    }

    private fun maxPreset(
        tier: VerificationTier,
        policies: List<CpuPolicyProbe>,
        gpu: io.github.mayusi.calibratesoc.data.capability.GpuProbe?,
        governorChoice: List<String>,
        availableGovernors: Set<String>,
    ): Preset {
        // "Max" returns each cluster's max to the kernel's published top
        // OPP. It's the "undo my underclock" preset. It deliberately does
        // NOT push beyond cpuinfo_max_freq — overclocking past the OPP
        // table is a per-device community knowledge thing, not a generic
        // algorithmic move.
        val maxes = policies.associate { policy ->
            val top = policy.hardwareLimitsKhz?.highKhz ?: policy.availableFreqsKhz.lastOrNull() ?: 0
            policy.policyId to top
        }.filterValues { it > 0 }
        // Always reset min to OPP-lowest so cores can idle. Without
        // this, applying Max after a Balanced/Performance left the
        // min pinned high and Max didn't fix it — leading to the
        // "cores can never idle, device cooks" failure mode.
        val mins = policies.associate { policy ->
            policy.policyId to (policy.availableFreqsKhz.minOrNull() ?: 0)
        }.filterValues { it > 0 }
        val gpuMax = gpu?.availableFreqsHz?.maxOrNull()

        val pickedGovernor = governorChoice.firstOrNull { it in availableGovernors }
        val govs = if (pickedGovernor != null) {
            policies.associate { it.policyId to pickedGovernor }
        } else emptyMap()

        return Preset(
            id = "builtin_max",
            name = "Max (stock ceiling)",
            description = "Returns every cluster (and the GPU) to the kernel's published max frequency. Use this to undo an underclock without rebooting.",
            verification = tier,
            cpuPolicyMaxKhz = maxes,
            cpuPolicyMinKhz = mins,
            cpuPolicyGovernor = govs,
            gpuMaxHz = gpuMax,
        )
    }

    private fun batterySaverDescription(
        family: io.github.mayusi.calibratesoc.data.capability.GpuFamily,
        tier: VerificationTier,
    ): String {
        val familyNote = when (tier) {
            VerificationTier.GENERIC_KNOWN_FAMILY -> "Caps each cluster to roughly half its top OPP; governor set to powersave when available."
            VerificationTier.GENERIC_UNKNOWN_FAMILY ->
                "$family is not in our profiled family list — caps are conservative defaults from the kernel's own OPP table."
            else -> "Caps to ~50% of each cluster's top OPP."
        }
        return familyNote
    }
}
