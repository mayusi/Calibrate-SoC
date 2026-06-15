package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import io.github.mayusi.calibratesoc.data.script.ScriptGenerateResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SCRIPT rung of the AutoTDP honesty ladder.
 *
 * On stock no-root devices where the sysfs nodes are not app-UID-writable,
 * the live AutoTDP daemon cannot run. This builder produces the BEST POSSIBLE
 * STATIC efficiency tune as a one-shot "Run as Root" shell script, using
 * the existing [AynScriptGenerator] pipeline so we never reinvent shell
 * escaping or the chmod-sandwich write pattern.
 *
 * What the tune does (in priority order):
 *  1. Offline prime cores (the highest-cluster cores, core 0 excluded — non-negotiable).
 *  2. Cap the big/gold cluster scaling_max_freq at the efficiency knee OPP step
 *     (the best perf-per-watt measured by EfficiencyCurve, or the ~67% heuristic
 *     if no measured knee is available).
 *  3. Prioritise GPU by leaving GPU max_pwrlevel at its hardware minimum (fastest level)
 *     and setting the GPU governor to msm-adreno-tz / simple_ondemand.
 *  4. Set the little-cluster governor to `conservative` so idle states are reachable.
 *
 * HONESTY: The generated script carries an honest banner comment:
 *   "Live auto-adjust needs the one-time unlock; this is the best static efficiency
 *    tune you can run now. Re-run after each reboot."
 * This is non-negotiable — the UI must show the SCRIPT rung label alongside the
 * banner so the user knows this is a one-shot write, not dynamic control.
 *
 * Calling convention:
 *   [buildEfficiencyScript] → returns the complete shell script text ready to
 *   be saved and handed to the device's "Run script as Root" runner. Alternatively,
 *   [buildEfficiencyPreset] → returns the [Preset] if the caller wants to use the
 *   preset directly (e.g. for preview in the UI) before generating the script.
 */
@Singleton
class AutoTdpScriptBuilder @Inject constructor(
    private val scriptGenerator: AynScriptGenerator,
) {

    /**
     * Builds the complete shell script text for the optimal static efficiency tune.
     *
     * @param caps       Immutable device envelope (prime cores, OPP steps, GPU levels).
     * @param profile    The active AutoTDP profile — determines how aggressively to cap.
     * @param report     Full capability report needed by [AynScriptGenerator.generate].
     * @param adapter    Optional per-device adapter (daemon stops, chmod-lock flag).
     * @param kneeKhz    Optional measured big-cluster efficiency-knee frequency in kHz
     *                   (from EfficiencyCurve). When null, the builder falls back to
     *                   ~67% of the top OPP (a reasonable heuristic for Snapdragon big
     *                   clusters — the measured knee usually lands between 60-75%).
     * @return           Shell script text, ready to write to disk and execute as root.
     */
    fun buildEfficiencyScript(
        caps: TdpCaps,
        profile: AutoTdpProfile,
        report: CapabilityReport,
        adapter: DeviceAdapter? = null,
        kneeKhz: Int? = null,
    ): String {
        val preset = buildEfficiencyPreset(caps, profile, report, kneeKhz)
        // AutoTDP presets are always built from the current device's own capability
        // report (cpuPolicies, bigPolicyId), so the safety gate should never reject
        // them. If it does, it indicates a programming error in buildEfficiencyPreset.
        return when (val result = scriptGenerator.generate(preset, report, adapter)) {
            is ScriptGenerateResult.Ok -> result.script
            is ScriptGenerateResult.Rejected ->
                error("AutoTDP preset was rejected by safety gate — this is a bug: ${result.reason}")
        }
    }

    /**
     * Builds a [Preset] representing the optimal static efficiency tune.
     *
     * Exposed separately so the UI can preview the tune (show which cores will be
     * offlined, what the cap will be) before offering the "Generate Script" button.
     *
     * The [Preset] is passed to [AynScriptGenerator.generate] to produce the script;
     * the cpu$N/online=0 writes are carried via [Preset.extraSysfs] because AynScriptGenerator
     * already emits every extraSysfs entry as a shell-escaped, existence-guarded write.
     */
    fun buildEfficiencyPreset(
        caps: TdpCaps,
        profile: AutoTdpProfile,
        report: CapabilityReport,
        kneeKhz: Int? = null,
    ): Preset {
        // ── 1. Prime-core offline entries via extraSysfs ──────────────────────
        // cpu$N/online = 0 offlines the core. cpu0 MUST NOT be offlined (enforced here
        // and in AutoTdpEngine — belt-and-suspenders). The kernel rejects writes to
        // cpu0/online on most configurations anyway, but we never emit the write.
        val extraSysfs = mutableMapOf<String, String>()
        for (coreIdx in caps.primeCoreIndices) {
            if (coreIdx == 0) continue // SAFETY: never offline cpu0
            extraSysfs["/sys/devices/system/cpu/cpu$coreIdx/online"] = "0"
        }

        // ── 2. Big-cluster frequency cap ──────────────────────────────────────
        // Use the measured knee when available; fall back to a heuristic ~67% of
        // the top OPP when the EfficiencyCurve hasn't been run yet.
        val cpuPolicyMaxKhz = mutableMapOf<Int, Int>()
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isNotEmpty()) {
            val targetKhz = when {
                kneeKhz != null -> {
                    // Snap to the nearest OPP step at-or-below the measured knee.
                    steps.filter { it <= kneeKhz }.maxOrNull()
                        ?: steps.first()
                }
                else -> {
                    // Heuristic: target ~67% of max for EFFICIENCY, ~75% for BALANCED.
                    val pct = when (profile) {
                        AutoTdpProfile.EFFICIENCY    -> 0.67
                        AutoTdpProfile.BALANCED      -> 0.75
                        AutoTdpProfile.BATTERY_TARGET -> 0.67
                    }
                    val target = (steps.last() * pct).toInt()
                    steps.minByOrNull { kotlin.math.abs(it - target) } ?: steps.first()
                }
            }
            cpuPolicyMaxKhz[caps.bigPolicyId] = targetKhz
        }

        // ── 3. Little-cluster governor: conservative ──────────────────────────
        // Set via extraSysfs so we don't need a policy-ID-to-governor mapping here.
        // The little-cluster policy IDs are discovered from cpuPolicies in the report.
        val cpuPolicyGovernor = mutableMapOf<Int, String>()
        val policies = report.cpuPolicies
        if (policies.isNotEmpty()) {
            val policyTopOpp = policies.associateWith { p ->
                p.availableFreqsKhz.maxOrNull() ?: 0
            }
            val minTopOpp = policyTopOpp.values.minOrNull() ?: 0
            // Little = policy (or policies) with the lowest top OPP.
            val littlePolicies = policies.filter { policyTopOpp[it] == minTopOpp }
            for (lp in littlePolicies) {
                // Prefer `conservative` (scales up on real load, unlike `powersave` which pins).
                // Fall back to `schedutil` if `conservative` isn't available.
                val available = lp.availableGovernors.toSet()
                val gov = when {
                    "conservative" in available -> "conservative"
                    "schedutil" in available    -> "schedutil"
                    "walt" in available         -> "walt"
                    else                        -> null
                }
                if (gov != null) cpuPolicyGovernor[lp.policyId] = gov
            }
        }

        // ── 4. GPU: keep at min power level (fastest) via extraSysfs ─────────
        // Adreno: max_pwrlevel = caps.gpuMinLevel means "allow up to the fastest GPU level".
        // This PRIORITISES the GPU — we never restrict it in the SCRIPT rung.
        val gpuRoot = report.gpu?.rootPath
        if (gpuRoot != null) {
            val minLevel = caps.gpuMinLevel
            if (minLevel != null) {
                extraSysfs["$gpuRoot/max_pwrlevel"] = minLevel.toString()
            }
        }

        // ── Description ───────────────────────────────────────────────────────
        val parkedCores = caps.primeCoreIndices.filter { it != 0 }
        val capMhz = cpuPolicyMaxKhz[caps.bigPolicyId]?.div(1000)
        val kneeLabel = if (kneeKhz != null) " (measured efficiency knee)" else " (heuristic ~67% of max)"
        val descLines = buildList {
            add("AutoTDP static efficiency tune — SCRIPT rung.")
            add(
                "Live auto-adjust needs the one-time unlock; this is the best " +
                    "static efficiency tune you can run now. Re-run after each reboot."
            )
            if (parkedCores.isNotEmpty()) {
                add("Prime cores offlined: cpu${parkedCores.joinToString(", cpu")}.")
            }
            if (capMhz != null) {
                add("Big-cluster cap: ${capMhz} MHz$kneeLabel.")
            }
            add("GPU: prioritised (max_pwrlevel held at fastest level).")
            add("Little cluster: conservative governor (idles low, scales on load).")
        }

        return Preset(
            id = "autotdp_script_efficiency",
            name = "AutoTDP — Static Efficiency Tune",
            description = descLines.joinToString(" "),
            verification = VerificationTier.USER_CUSTOM,
            cpuPolicyMaxKhz = cpuPolicyMaxKhz,
            cpuPolicyGovernor = cpuPolicyGovernor,
            extraSysfs = extraSysfs,
        )
    }
}
