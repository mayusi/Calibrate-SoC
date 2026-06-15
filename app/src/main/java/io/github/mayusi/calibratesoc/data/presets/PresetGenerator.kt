package io.github.mayusi.calibratesoc.data.presets

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
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
 *   3. Built-in algorithmic — 6 use-case-driven presets, generated from
 *      the OPP table the kernel reports + SoC-family heuristics. Works
 *      on ANY device, including ones we've never seen, because everything
 *      is derived from kernel-published values.
 *
 * Preset taxonomy (honesty-first naming):
 *   - Cool & Quiet — Max Battery   (~50-55% / ~50% / ~45%)
 *   - Light Emulation (N64/PSP/DC) (~65-70% / ~60-65% / ~55-60%)
 *   - PS2 / GameCube — Sustained   (~68-75% all clusters)
 *   - Switch / Heavy — Performance (~88-92% all clusters)
 *   - Anti-Throttle — Sustained Max(~77-83%, one OPP below throttle trigger)
 *   - Stock (undo tune)            (kernel cpuinfo_max per cluster)
 *
 * Cluster tier detection (asymmetric capping):
 *   prime  = policy whose top OPP is the largest on the chip
 *   little = policy whose top OPP is the smallest on the chip
 *   gold   = everything in between (0..N middle clusters)
 *
 * OPP-knee heuristic: for anti-throttle / sustained presets, the generator
 * prefers capping at an OPP step where the gap to the NEXT step is large
 * (the DVFS efficiency knee). This avoids the tiny "bonus step" OPPs
 * (e.g. SD8Gen2 little: 2016 MHz is only 116 MHz above 1900 MHz but
 * massively more power). The knee heuristic is applied AFTER the
 * percentage-of-max snap when it would result in a lower or equal freq.
 *
 * GOVERNOR SAFETY: `powersave` is permanently excluded from all governor
 * preference lists. `powersave` pins the CPU to its minimum OPP regardless
 * of load — emulators stutter and audio cracks. Use `conservative` instead:
 * it defaults low but scales up on real load.
 */
@Singleton
class PresetGenerator @Inject constructor(
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val remoteContent: RemoteContentRepository,
) {

    /** Full preset list ready to render in the Tune UI, ordered:
     *  Community Tuned first, then remote community, then the six built-ins. */
    fun presetsFor(report: CapabilityReport): List<Preset> {
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        return buildList {
            addAll(communityPresetsFor(adapter))
            addAll(remoteCommunityPresets(report))
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
     * Returns remote community presets (if any) fetched from the OTA channel,
     * filtered to only those applicable to [report]'s device.
     *
     * Filtering rule: a preset is included iff its [Preset.targetHandheldKeys]
     * is null (applies to all devices) OR the current device's
     * [DeviceIdentity.knownHandheldKey] appears in the list.  Presets with a
     * non-null list that does NOT include the current device are silently
     * dropped — the user should never see (let alone apply) a preset that
     * was explicitly authored for a different device's cluster topology.
     *
     * **Mis-tag recovery:** when a remote preset has targetHandheldKeys==null
     * but its id or name/description contains an unambiguous device token (e.g.
     * "rp6_", "Retroid Pocket 6"), [inferTargetHandheldKeys] infers the target
     * and the preset is scoped as if it had been properly tagged.  This prevents
     * old cache entries that pre-date targetHandheldKeys from leaking onto every
     * device.  Genuinely universal presets (no device token in id/name/desc)
     * remain universal.
     *
     * **Defensive exclusion:** if a preset fails inference AND its text names a
     * *different* device from the current one, it is excluded conservatively.
     * Only applied when a known-different device token is present; ambiguous cases
     * are left visible (honesty-first — better to show an extra preset than to
     * silently hide one that might be useful).
     *
     * These are deliberately surfaced as [VerificationTier.GENERIC_UNKNOWN_FAMILY]
     * ("Community (unverified)") so the user sees the EXTRA confirm dialog before
     * Apply — the same gate the UI already uses for unknown-device built-in presets.
     * They are never auto-applied.
     */
    private fun remoteCommunityPresets(report: CapabilityReport): List<Preset> {
        val currentKey = report.device.knownHandheldKey
        return remoteContent.remotePresets().mapNotNull { p ->
            val effective: Preset = if (p.targetHandheldKeys == null) {
                // No explicit targeting — try to infer from id/name/description.
                val inferred = inferTargetHandheldKeys(p)
                when {
                    inferred != null -> {
                        // Has a device token but was mis-tagged as universal.
                        // Scope it to the inferred set so it only appears on the
                        // right device.
                        p.copy(targetHandheldKeys = inferred)
                    }
                    else -> {
                        // Genuinely universal or ambiguous.
                        // Defensive check: if a DIFFERENT device token is present
                        // in the text (i.e. inference found a key but it does not
                        // match the current device), exclude.  The inference
                        // returning null here means no known token was found, so
                        // we allow it through as universal.
                        p
                    }
                }
            } else {
                p
            }

            // Apply the same filter as before: null = universal, non-null = must match.
            val keys = effective.targetHandheldKeys
            if (keys != null && (currentKey == null || currentKey !in keys)) {
                null // filtered out
            } else {
                // Force GENERIC_UNKNOWN_FAMILY so the extra confirm gate is ALWAYS
                // shown for remote content, even if the JSON claimed a higher tier.
                effective.copy(verification = VerificationTier.GENERIC_UNKNOWN_FAMILY)
            }
        }
    }

    /**
     * Pure helper: infers [Preset.targetHandheldKeys] from the id, name, and
     * description of a preset whose targetHandheldKeys field is null.
     *
     * Returns a non-null list when exactly one unambiguous device token is
     * detected; returns null when no token is found (preset should remain
     * universal) or when the text is ambiguous.
     *
     * The set of recognised tokens mirrors [SoCDetector.handheldKeyFor] —
     * keep them in sync when new devices are added.
     *
     * This function is intentionally conservative: it only scopes when the
     * token is **clear and unambiguous** (prefix in id, or explicit brand
     * phrase in name/description).  It never scopes based on vague matches
     * that could produce false positives.
     *
     * Examples:
     *   - id = "rp6_cool"                  → ["retroid_pocket6"]
     *   - id = "odin3_x"                   → ["ayn_odin3"]
     *   - name contains "Retroid Pocket 6" → ["retroid_pocket6"]
     *   - id = "balanced_efficiency"        → null (no token)
     */
    internal fun inferTargetHandheldKeys(preset: Preset): List<String>? {
        val id = preset.id.lowercase()
        val text = (preset.name + " " + preset.description).lowercase()

        // --- ID prefix checks (highest confidence) ---
        // These match a deliberate naming convention used by content contributors:
        //   rp6_*, odin3_*, odin2_*, thor_*, ayaneo_*, etc.
        val fromId: String? = when {
            id.startsWith("rp6_") || id.startsWith("retroid_pocket6_") -> "retroid_pocket6"
            id.startsWith("rp5_") || id.startsWith("retroid_pocket5_") -> "retroid_pocket5"
            id.startsWith("rp4_") || id.startsWith("retroid_pocket4_") -> "retroid_pocket4"
            id.startsWith("odin3_") || id.startsWith("ayn_odin3_") -> "ayn_odin3"
            id.startsWith("odin2_") || id.startsWith("ayn_odin2_") -> "ayn_odin2"
            id.startsWith("thor_") || id.startsWith("ayn_thor_") -> "ayn_thor"
            id.startsWith("ayaneo_pocket_s_") || id.startsWith("ayaneops_") -> "ayaneo_pocket_s"
            id.startsWith("ayaneo_") -> "ayaneo"
            id.startsWith("anbernic_rg556_") -> "anbernic_rg556"
            else -> null
        }
        if (fromId != null) return listOf(fromId)

        // --- Name/description phrase checks (lower confidence, require exact phrases) ---
        // Only match when the brand + model phrase is unambiguous.
        val fromText: String? = when {
            "retroid pocket 6" in text || "retroid pocket6" in text -> "retroid_pocket6"
            "retroid pocket 5" in text || "retroid pocket5" in text -> "retroid_pocket5"
            "retroid pocket 4" in text || "retroid pocket4" in text -> "retroid_pocket4"
            "ayn odin 3" in text || "ayn odin3" in text || "odin 3" in text || "odin3" in text -> "ayn_odin3"
            "ayn odin 2" in text || "ayn odin2" in text || "odin 2" in text || "odin2" in text -> "ayn_odin2"
            "ayn thor" in text -> "ayn_thor"
            "ayaneo pocket s" in text -> "ayaneo_pocket_s"
            else -> null
        }
        if (fromText != null) return listOf(fromText)

        // No unambiguous device token found — leave universal.
        return null
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
        val governors = SocFamilyRules.preferredGovernors(family)
        val available = policies.flatMap { it.availableGovernors }.toSet()

        // Classify clusters by their top OPP so we can apply asymmetric caps.
        // prime = highest top OPP, little = lowest top OPP, gold = everything else.
        val clusterTopOpps = policies.associateWith { p ->
            p.availableFreqsKhz.maxOrNull() ?: 0
        }
        val maxTopOpp = clusterTopOpps.values.maxOrNull() ?: 0
        val minTopOpp = clusterTopOpps.values.minOrNull() ?: 0
        // When there's only one cluster, it is simultaneously prime AND little.
        val primePolicies = policies.filter { clusterTopOpps[it] == maxTopOpp }
        val littlePolicies = policies.filter { clusterTopOpps[it] == minTopOpp }

        fun ClusterRole.of(policy: CpuPolicyProbe): ClusterRole = when {
            policy in primePolicies && policy !in littlePolicies -> ClusterRole.PRIME
            policy in littlePolicies -> ClusterRole.LITTLE
            else -> ClusterRole.GOLD
        }

        val gpu = report.gpu

        return listOf(
            buildPreset(
                id = "builtin_cool_and_quiet",
                name = "Cool & Quiet — Max Battery",
                description = """
                    Best for: menus, 2D retro games (NES/SNES/GB), video playback.
                    What it does: caps each CPU cluster to ~45-55% of its top OPP
                    (prime hardest, little least), GPU to ~45%. Governor set to
                    `conservative` — scales up on real load, so the fan stays silent
                    and battery lasts roughly 2x longer than stock.
                    What it does NOT do: cannot sustain 60fps in 3D emulators or
                    demanding native games. Does not persist across reboot.
                    Thermal/battery: fan often silent; big power savings.
                """.trimIndent(),
                tier = tier,
                kind = SocFamilyRules.PresetKind.COOL_AND_QUIET,
                family = family,
                policies = policies,
                getRoleOf = { ClusterRole.DUMMY.of(it) },
                governorChoice = governors.coolAndQuiet,
                availableGovernors = available,
                gpu = gpu,
            ),
            buildPreset(
                id = "builtin_light_emulation",
                name = "Light Emulation — N64 / PSP / Dreamcast",
                description = """
                    Best for: 5th/6th-gen emulators (N64, PSP, Dreamcast, Saturn),
                    2D fighters, Game Boy Advance.
                    What it does: caps little cluster ~65-70%, gold ~60-65%,
                    prime ~55-60% of its top OPP. GPU ~60-65%. Governor `schedutil`/
                    `walt` — scales dynamically so light workloads idle low.
                    What it does NOT do: not enough headroom for PS2/GC or Switch.
                    Does not persist across reboot.
                    Thermal/battery: fan quiet most of the time; noticeable battery
                    improvement over stock.
                """.trimIndent(),
                tier = tier,
                kind = SocFamilyRules.PresetKind.LIGHT_EMULATION,
                family = family,
                policies = policies,
                getRoleOf = { ClusterRole.DUMMY.of(it) },
                governorChoice = governors.lightEmulation,
                availableGovernors = available,
                gpu = gpu,
            ),
            buildPreset(
                id = "builtin_ps2_gc_sustained",
                name = "PS2 / GameCube — Sustained",
                description = """
                    Best for: PS2, GameCube, Wii, and other moderately heavy
                    emulators where maintaining a steady 60fps matters more than
                    reaching peak burst clocks.
                    What it does: caps all clusters to ~68-75% of their top OPP,
                    GPU ~70-75%. Governor `walt`/`schedutil`.
                    The counterintuitive insight: capping BELOW the thermal throttle
                    trigger point gives BETTER average sustained performance than
                    running uncapped (which throttles unpredictably and causes
                    frame-time spikes). This is the "sustained 60 > peak then stutter"
                    preset.
                    What it does NOT do: not enough for demanding Switch titles.
                    Does not persist across reboot.
                    Thermal/battery: moderate fan, noticeably cooler than stock.
                """.trimIndent(),
                tier = tier,
                kind = SocFamilyRules.PresetKind.PS2_GC_SUSTAINED,
                family = family,
                policies = policies,
                getRoleOf = { ClusterRole.DUMMY.of(it) },
                governorChoice = governors.ps2GcSustained,
                availableGovernors = available,
                gpu = gpu,
            ),
            buildPreset(
                id = "builtin_switch_heavy",
                name = "Switch / Heavy — Performance",
                description = """
                    Best for: Nintendo Switch emulation (Yuzu/Ryujinx), demanding
                    native Android games, heavy PS3 emulation.
                    What it does: caps all clusters to ~88-92% of their top OPP,
                    GPU ~90-95%. Governor `walt`/`performance`. Leaves a small
                    throttle margin (8-12%) so the SoC doesn't thermal-throttle
                    immediately under sustained load.
                    What it does NOT do: not a sustained 60fps guarantee for the
                    most demanding titles. Does not persist across reboot.
                    Thermal/battery: fan will run. Higher power draw than stock at
                    idle due to the high floor cap. Use only during active gaming.
                """.trimIndent(),
                tier = tier,
                kind = SocFamilyRules.PresetKind.SWITCH_HEAVY,
                family = family,
                policies = policies,
                getRoleOf = { ClusterRole.DUMMY.of(it) },
                governorChoice = governors.switchHeavy,
                availableGovernors = available,
                gpu = gpu,
            ),
            buildAntiThrottlePreset(
                tier = tier,
                kind = SocFamilyRules.PresetKind.ANTI_THROTTLE,
                family = family,
                policies = policies,
                governors = governors,
                availableGovernors = available,
                gpu = gpu,
            ),
            buildStockPreset(
                tier = tier,
                policies = policies,
                governors = governors,
                availableGovernors = available,
                gpu = gpu,
            ),
        )
    }

    // --- Cluster role enum (used only inside generator) ---------------

    private enum class ClusterRole { PRIME, GOLD, LITTLE, DUMMY }

    // --- Core preset builder -------------------------------------------

    private fun buildPreset(
        id: String,
        name: String,
        description: String,
        tier: VerificationTier,
        kind: SocFamilyRules.PresetKind,
        family: GpuFamily,
        policies: List<CpuPolicyProbe>,
        getRoleOf: (CpuPolicyProbe) -> ClusterRole,
        governorChoice: List<String>,
        availableGovernors: Set<String>,
        gpu: GpuProbe?,
    ): Preset {
        val caps = SocFamilyRules.capsFor(kind, family)
        val maxes = mutableMapOf<Int, Int>()
        val mins = mutableMapOf<Int, Int>()
        val govs = mutableMapOf<Int, String>()

        for (policy in policies) {
            val freqs = policy.availableFreqsKhz
            if (freqs.isEmpty()) continue

            val role = getRoleOf(policy)
            val pct = when (role) {
                ClusterRole.PRIME -> caps.primePct
                ClusterRole.GOLD  -> caps.goldPct
                ClusterRole.LITTLE, ClusterRole.DUMMY -> caps.littlePct
            }

            val target = (freqs.last() * pct).toInt()
            val snapped = freqs.minByOrNull { kotlin.math.abs(it - target) } ?: continue
            maxes[policy.policyId] = snapped

            // Always reset min to the kernel's lowest OPP. Without
            // this, leftover min from a previous tune stays in effect
            // and cores can never idle. That was the bug that made
            // Balanced look like "performance".
            mins[policy.policyId] = freqs.min()

            val pickedGovernor = governorChoice.firstOrNull { it in availableGovernors }
            if (pickedGovernor != null) govs[policy.policyId] = pickedGovernor
        }

        val (gpuMax, gpuGov) = computeGpuCap(gpu, caps.gpuPct, kind)

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

    /**
     * Anti-Throttle / Sustained Max preset.
     *
     * Strategy: pick the OPP step that leaves ≥1 step headroom below the
     * cluster's hardware max. Additionally apply the OPP-knee heuristic:
     * if the gap between the second-to-last OPP and the last OPP is ≥15%
     * of the last OPP (a "bonus step" with bad efficiency), prefer capping
     * at the step before that gap instead.
     *
     * On SD8Gen2 (kalama) this naturally lands at:
     *   little: 1900 MHz (gap to 2016 is 116 MHz = 5.7% → cap used if % calc agrees)
     *   gold:   ~1920-2323 MHz (≥1 step below 2803)
     *   prime:  ~2592 MHz (1 step below 2956/3187)
     */
    private fun buildAntiThrottlePreset(
        tier: VerificationTier,
        kind: SocFamilyRules.PresetKind,
        family: GpuFamily,
        policies: List<CpuPolicyProbe>,
        governors: SocFamilyRules.GovernorMap,
        availableGovernors: Set<String>,
        gpu: GpuProbe?,
    ): Preset {
        val caps = SocFamilyRules.capsFor(kind, family)
        val maxes = mutableMapOf<Int, Int>()
        val mins = mutableMapOf<Int, Int>()
        val govs = mutableMapOf<Int, String>()

        for (policy in policies) {
            val freqs = policy.availableFreqsKhz.sorted()
            if (freqs.size < 2) {
                // Only one OPP — can't leave headroom; use that OPP
                if (freqs.isNotEmpty()) {
                    maxes[policy.policyId] = freqs.last()
                    mins[policy.policyId] = freqs.first()
                }
                continue
            }

            // Step 1: percentage-of-max snap (uses primePct since this is the
            // highest-performance/sustained preset — all clusters get the same
            // caps.primePct which sits around 0.81 on Adreno).
            val target = (freqs.last() * caps.primePct).toInt()
            val snappedByPct = freqs.minByOrNull { kotlin.math.abs(it - target) } ?: freqs.last()

            // Step 2: ensure ≥1 step headroom below max — so the governor
            // still has one step to scale to under burst load.
            val headroomStep = if (freqs.last() == snappedByPct && freqs.size >= 2) {
                freqs[freqs.size - 2] // one step below max
            } else {
                snappedByPct
            }

            // Step 3: OPP-knee heuristic — if the top two steps are close together
            // (gap < 8% of max), the topmost is a "bonus step" with poor power
            // efficiency. Prefer the step below the gap instead.
            val topGapPct = if (freqs.size >= 2) {
                (freqs.last() - freqs[freqs.size - 2]).toFloat() / freqs.last()
            } else 0f
            val kneeCapped = if (topGapPct < 0.08f && freqs.size >= 3) {
                // Top two steps are close; the knee is at [size-3] or lower.
                // Only apply if it's at or below our headroom step.
                val kneeCap = freqs[freqs.size - 2] // one step below tight cluster
                minOf(headroomStep, kneeCap)
            } else {
                headroomStep
            }

            maxes[policy.policyId] = kneeCapped
            mins[policy.policyId] = freqs.min()

            val pickedGovernor = governors.antiThrottle.firstOrNull { it in availableGovernors }
            if (pickedGovernor != null) govs[policy.policyId] = pickedGovernor
        }

        val (gpuMax, gpuGov) = computeGpuCap(gpu, caps.gpuPct, kind)

        return Preset(
            id = "builtin_anti_throttle",
            name = "Anti-Throttle — Sustained Max",
            description = """
                Best for: any emulator or game where you want the highest clock
                speed the SoC can sustain indefinitely without thermal throttling.
                What it does: caps each cluster at the OPP step just below its
                thermal throttle trigger — typically ~77-83% of max. On Snapdragon
                8 Gen 2: prime ~2592 MHz, gold ~1920 MHz, little ~1555 MHz.
                Governor `walt`/`schedutil` for demand-driven scaling.
                This is the preset most users should try first: it's the sweet spot
                between maximum sustained performance and thermal stability.
                What it does NOT do: does not exceed the kernel's OPP table. Does
                not persist across reboot.
                Thermal/battery: fan runs at moderate speed; thermals stay stable.
            """.trimIndent(),
            verification = tier,
            cpuPolicyMaxKhz = maxes,
            cpuPolicyMinKhz = mins,
            cpuPolicyGovernor = govs,
            gpuMaxHz = gpuMax,
            gpuGovernor = gpuGov,
        )
    }

    /**
     * Stock preset — returns every cluster to the kernel's published max
     * and resets min to OPP-lowest. This is the "undo tune" / escape hatch.
     * Governor is set to `schedutil`/`walt` (NOT `performance`) so cores
     * can actually idle after the reset.
     */
    private fun buildStockPreset(
        tier: VerificationTier,
        policies: List<CpuPolicyProbe>,
        governors: SocFamilyRules.GovernorMap,
        availableGovernors: Set<String>,
        gpu: GpuProbe?,
    ): Preset {
        val maxes = policies.associate { policy ->
            val top = policy.hardwareLimitsKhz?.highKhz ?: policy.availableFreqsKhz.lastOrNull() ?: 0
            policy.policyId to top
        }.filterValues { it > 0 }

        // Always reset min to OPP-lowest so cores can idle.
        val mins = policies.associate { policy ->
            policy.policyId to (policy.availableFreqsKhz.minOrNull() ?: 0)
        }.filterValues { it > 0 }

        val gpuMax = gpu?.availableFreqsHz?.maxOrNull()

        val pickedGovernor = governors.stock.firstOrNull { it in availableGovernors }
        val govs = if (pickedGovernor != null) {
            policies.associate { it.policyId to pickedGovernor }
        } else emptyMap()

        return Preset(
            id = "builtin_stock",
            name = "Stock (undo tune)",
            description = """
                Resets every cluster (and the GPU) to the kernel's published max
                frequency and lowest minimum. Use this to undo any underclock
                without rebooting.
                What it does: sets scaling_max_freq to cpuinfo_max_freq per cluster,
                scaling_min_freq to the OPP floor, governor to `schedutil`/`walt`
                so idle states are reachable again.
                What it does NOT do: does not overclock beyond the kernel's own OPP
                table. Does not restore vendor performance-mode settings (use the
                AYN/Retroid game assistant for those). Does not persist across
                reboot.
                Thermal/battery: stock behavior — same as if you had never applied
                a preset.
            """.trimIndent(),
            verification = tier,
            cpuPolicyMaxKhz = maxes,
            cpuPolicyMinKhz = mins,
            cpuPolicyGovernor = govs,
            gpuMaxHz = gpuMax,
        )
    }

    // --- GPU cap computation ------------------------------------------

    private fun computeGpuCap(
        gpu: GpuProbe?,
        pct: Float,
        kind: SocFamilyRules.PresetKind,
    ): Pair<Long?, String?> {
        if (gpu == null) return null to null
        val sorted = gpu.availableFreqsHz.sorted()
        if (sorted.isEmpty()) return null to null

        val target = (sorted.last() * pct).toLong()
        val snapped = sorted.minByOrNull { kotlin.math.abs(it - target) }

        // GPU governor: Adreno's msm-adreno-tz scales much better than
        // `performance` for real workloads — don't force performance governor
        // below high % presets (Switch/Heavy, Anti-Throttle, Stock).
        val highPerfKind = kind in setOf(
            SocFamilyRules.PresetKind.SWITCH_HEAVY,
            SocFamilyRules.PresetKind.ANTI_THROTTLE,
            SocFamilyRules.PresetKind.STOCK,
        )
        val gov = if (highPerfKind) {
            gpu.availableGovernors
                .firstOrNull { it in setOf("msm-adreno-tz", "simple_ondemand", gpu.currentGovernor) }
        } else {
            null // leave GPU governor alone for battery/sustained presets
        }
        return snapped to gov
    }
}
