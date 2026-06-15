package io.github.mayusi.calibratesoc.data.script

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.profiles.PresetSafetyGate
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result type returned by [AynScriptGenerator.generate].
 *
 * [Ok] carries the script body string.
 * [Rejected] carries a user-visible reason from [PresetSafetyGate].
 * Callers (TuneViewModel) must check for [Rejected] and surface the
 * reason before calling [AynScriptDeployer].
 */
sealed class ScriptGenerateResult {
    data class Ok(val script: String) : ScriptGenerateResult()
    data class Rejected(val reason: String) : ScriptGenerateResult()
}

/**
 * Converts a [Preset] into a bash script byte-compatible with the
 * pattern from TheOldTaylor's Odin3-CPU-Underclock repo. The user then
 * invokes the script via Odin Settings -> "Run script as Root" — that
 * UI path executes shell scripts under root *without* the user needing
 * to install Magisk or KernelSU.
 *
 * Script body uses the same recipe TheOldTaylor proved works on the
 * 8 Elite firmware:
 *
 *   1. stop vendor perf daemons (per-device, from DeviceAdapter)
 *   2. chmod 666 the target so a stock `echo >` succeeds
 *   3. write the value with printf (echo's trailing newline upsets
 *      some kernel parsers)
 *   4. chmod 444 to seal — the daemons can restart but they can't
 *      clobber the value because they no longer have write permission
 *
 * The generated script is self-contained — no external dependencies,
 * no embedded values from our app, no networking. Easy to audit before
 * running.
 *
 * [generate] runs [PresetSafetyGate.check] before building the script
 * body.  A [ScriptGenerateResult.Rejected] result means the preset is
 * not safe for this device; callers MUST NOT deploy the script and MUST
 * surface the reason to the user.
 */
@Singleton
class AynScriptGenerator @Inject constructor() {

    /**
     * POSIX-correct single-quote escaper for shell arguments.
     *
     * Closes the surrounding single-quote context, inserts a backslash-escaped
     * literal single-quote, then reopens the context. The result is always
     * safe to embed between outer single quotes in a generated shell line,
     * even if [s] itself contains single-quotes, dollar signs, backticks,
     * semicolons, or any other shell-special characters.
     *
     * Examples:
     *   shellSingleQuote("Performance") → 'Performance'
     *   shellSingleQuote("Mike's tune") → 'Mike'\''s tune'
     *   shellSingleQuote("foo'; rm -rf /data; echo '") → 'foo'\''; rm -rf /data; echo '''
     *
     * Mirrors the identical helper already present in [RootWriter.shellQuote].
     */
    internal fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Make [s] safe to embed in a single-line shell COMMENT (`# ...`).
     *
     * A shell comment runs to end-of-line, so a value placed after `#` cannot
     * inject a command on the *same* line. The one danger is an embedded
     * newline (or carriage return): it would terminate the comment and turn
     * whatever follows into a real command line. Collapse all CR/LF to spaces
     * so a malicious preset name can never escape the comment. (Command-line
     * uses of the same values still go through [shellSingleQuote].)
     */
    internal fun commentSafe(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ')

    /**
     * Run the device-safety gates and, if they pass, build a shell script
     * string for [preset].
     *
     * Returns [ScriptGenerateResult.Rejected] with a clear reason if
     * [PresetSafetyGate.check] fails — callers MUST surface this to the
     * user and MUST NOT deploy the script.
     *
     * Returns [ScriptGenerateResult.Ok] with the script body otherwise.
     * Caller writes [ScriptGenerateResult.Ok.script] to disk via [AynScriptDeployer].
     */
    fun generate(preset: Preset, report: CapabilityReport, adapter: DeviceAdapter?): ScriptGenerateResult {
        // Safety gate: same two-gate check as ProfileApplier, extracted into
        // PresetSafetyGate so neither path duplicates the logic.
        when (val verdict = PresetSafetyGate.check(preset, report)) {
            is PresetSafetyGate.SafetyVerdict.Rejected ->
                return ScriptGenerateResult.Rejected(verdict.reason)
            is PresetSafetyGate.SafetyVerdict.Ok -> { /* proceed */ }
        }

        val daemons = adapter?.perfDaemonsToStopOnWrite.orEmpty()
        val chmodLock = adapter?.chmodLockCpuFreqWrites ?: false

        return ScriptGenerateResult.Ok(buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Generated by Calibrate SoC for ${report.device.manufacturer} ${report.device.model}")
            appendLine("# Preset: ${commentSafe(preset.name)}")
            appendLine("# ${commentSafe(preset.description)}")
            preset.sourceUrl?.let { appendLine("# Source: $it") }
            appendLine("# This script writes to kernel sysfs and is invoked via")
            appendLine("# your device's settings -> Run script as Root. Sysfs writes")
            appendLine("# do not persist across reboot — re-run after each boot if needed.")
            appendLine()

            if (daemons.isNotEmpty()) {
                appendLine("# Stop vendor perf daemons so they cannot race the write.")
                appendLine("# 2>/dev/null: a daemon that isn't running on THIS firmware")
                appendLine("# must not abort the rest of the script.")
                daemons.forEach { appendLine("stop $it 2>/dev/null") }
                appendLine()
            }

            // Safety guard: refuse to emit min == max OR min > max OR
            // min > 1.5 GHz. All three patterns trap the CPU at high
            // freq and cause the "device cooks while idle" failure.
            // Caller must give us a properly-formed preset; if they
            // didn't, append a comment that explains why we skipped.
            val safeMins = mutableMapOf<Int, Int>()
            for ((policyId, minKhz) in preset.cpuPolicyMinKhz) {
                val maxKhz = preset.cpuPolicyMaxKhz[policyId]
                when {
                    minKhz > 1_500_000 -> {
                        appendLine("# SAFETY: skipping min for policy$policyId — min ${minKhz}kHz > 1.5 GHz would trap cores at high freq.")
                    }
                    maxKhz != null && minKhz >= maxKhz -> {
                        appendLine("# SAFETY: skipping min for policy$policyId — min ${minKhz}kHz >= max ${maxKhz}kHz would pin cores.")
                    }
                    else -> safeMins[policyId] = minKhz
                }
            }

            // Write min FIRST when we're LOWERING it, max FIRST when
            // RAISING. Otherwise the kernel rejects a write that
            // would temporarily violate min <= cur <= max. Easiest
            // safe order: always write min low first, then max,
            // then min final.
            safeMins.toSortedMap().forEach { (policyId, _) ->
                // Drop min to a guaranteed-safe floor before touching max.
                emitFreqWrite(policyId, "scaling_min_freq", 300000, chmodLock)
            }
            preset.cpuPolicyMaxKhz.toSortedMap().forEach { (policyId, khz) ->
                emitFreqWrite(policyId, "scaling_max_freq", khz, chmodLock)
            }
            safeMins.toSortedMap().forEach { (policyId, khz) ->
                emitFreqWrite(policyId, "scaling_min_freq", khz, chmodLock)
            }
            preset.cpuPolicyGovernor.toSortedMap().forEach { (policyId, gov) ->
                emitGovernorWrite(policyId, gov)
            }

            // GPU writes — Adreno + Mali both expose devfreq paths.
            // We use the GpuProbe's rootPath to be vendor-agnostic.
            val gpuRoot = report.gpu?.rootPath
            if (gpuRoot != null) {
                preset.gpuMinHz?.let { emitGpuWrite(gpuRoot, "devfreq/min_freq", it.toString()) }
                preset.gpuMaxHz?.let { emitGpuWrite(gpuRoot, "devfreq/max_freq", it.toString()) }
                preset.gpuGovernor?.let { emitGpuWrite(gpuRoot, "devfreq/governor", it) }
            }

            if (daemons.isNotEmpty()) {
                appendLine()
                appendLine("# Daemons stay stopped — restarting them re-enables the")
                appendLine("# race they were stopped to avoid. Reboot to restore them.")
            }

            // Extra sysfs knobs (governor tunables, VM sysctls, I/O, schedtune,
            // input boost, DDR, etc.) carried in preset.extraSysfs as path→value.
            //
            // Every entry is:
            //   1. Validated via TunableMetadata before being emitted — a bad path
            //      or bad value is skipped with a comment (never emitted as a real
            //      command).
            //   2. Shell-escaped via shellSingleQuote() so no value can break the
            //      single-quote boundary and inject an arbitrary command.
            //   3. Wrapped in an existence guard so a node absent on THIS device
            //      (different SoC/kernel) doesn't abort the rest of the script.
            val extraEntries = preset.extraSysfs.entries.toSortedSet(compareBy { it.key })
            if (extraEntries.isNotEmpty()) {
                appendLine()
                appendLine("# Extra kernel knobs (governor tunables, VM, I/O, schedtune, etc.)")
                for ((path, value) in extraEntries) {
                    val pathError = TunableMetadata.validateCustomSysfsPath(path)
                    if (pathError != null) {
                        appendLine("# SKIPPED (invalid path): ${commentSafe(path)} — $pathError")
                        continue
                    }
                    val id = TunableId(kind = TunableKind.SYSFS, target = path)
                    val valueError = TunableMetadata.forId(id).validate(value)
                    if (valueError != null) {
                        appendLine("# SKIPPED (invalid value for ${commentSafe(path)}): $valueError")
                        continue
                    }
                    emitSysfsWrite(path, value)
                }
            }

            // Verification block: read back every policy we touched so the
            // user (and Calibrate SoC's run-as-root output capture) can
            // confirm the values actually stuck. A line that shows the
            // OLD value here means perfd or a kernel min<=cur<=max
            // constraint clamped us — actionable signal, not a silent
            // failure.
            val touchedPolicies = (preset.cpuPolicyMaxKhz.keys + preset.cpuPolicyMinKhz.keys)
                .toSortedSet()
            if (touchedPolicies.isNotEmpty()) {
                appendLine()
                appendLine("echo '--- Calibrate SoC: verifying ---'")
                for (policyId in touchedPolicies) {
                    val base = "/sys/devices/system/cpu/cpufreq/policy$policyId"
                    appendLine(
                        "echo \"policy$policyId max=\$(cat $base/scaling_max_freq 2>/dev/null) " +
                            "min=\$(cat $base/scaling_min_freq 2>/dev/null) " +
                            "gov=\$(cat $base/scaling_governor 2>/dev/null)\"",
                    )
                }
            }
            appendLine()
            appendLine("echo 'Calibrate SoC: '${shellSingleQuote(preset.name)}' applied.'")
        })
    }

    private fun StringBuilder.emitFreqWrite(
        policyId: Int,
        node: String,
        khz: Int,
        chmodLock: Boolean,
    ) {
        val path = "/sys/devices/system/cpu/cpufreq/policy$policyId/$node"
        // Guard the whole write on the path existing — policy IDs differ
        // per SoC (2 clusters on Odin, 3 on Thor/RP6/DS) and a missing
        // node must not abort the script.
        //
        // Redirect note: `printf > file 2>/dev/null` does NOT suppress a
        // "can't create: Permission denied" message, because the shell
        // opens the redirect target BEFORE running printf, so the error
        // is the SHELL's, not printf's. Wrapping the whole write in a
        // subshell with the subshell's stderr redirected DOES catch it —
        // `( printf ... > file ) 2>/dev/null`. Keeps the runner output
        // clean even when a node is momentarily unwritable.
        // Defence-in-depth: single-quote the path too. policyId is an Int and node is a
        // hardcoded string so neither can contain metacharacters, but quoting consistently
        // makes the shell lines audit-friendly and safe against future code changes.
        val qpath = shellSingleQuote(path)
        if (chmodLock) appendLine("[ -e $qpath ] && ( chmod 666 $qpath ) 2>/dev/null")
        appendLine("[ -e $qpath ] && ( printf %s '$khz' > $qpath ) 2>/dev/null")
        if (chmodLock) appendLine("[ -e $qpath ] && ( chmod 444 $qpath ) 2>/dev/null")
    }

    private fun StringBuilder.emitGovernorWrite(policyId: Int, governor: String) {
        val path = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_governor"
        // Governors aren't policed by perfd the way freq caps are, so no
        // chmod sandwich needed. Subshell-wrap so a redirect-create
        // failure (EINVAL: governor not in this kernel) stays quiet.
        // Shell-quote BOTH the path (defence-in-depth; it's policy-derived but
        // quoting consistently is good audit hygiene) and the governor value
        // (shellSingleQuote prevents metacharacter injection from user/profile strings).
        val qpath = shellSingleQuote(path)
        appendLine("[ -e $qpath ] && ( printf %s ${shellSingleQuote(governor)} > $qpath ) 2>/dev/null")
    }

    /**
     * Generic sysfs write: existence guard + chmod sandwich + shell-escaped path and value.
     *
     * Used for every entry in [Preset.extraSysfs] — governor tunables, VM
     * sysctls, I/O scheduler, schedtune/uclamp, input boost, DDR, etc.
     *
     * The chmod sandwich is applied unconditionally: on nodes where the kernel
     * doesn't enforce a read-only mode it's a harmless no-op; on nodes where
     * perfd/DCVS races the write (e.g. CPU cpufreq) it keeps the value sticky.
     *
     * Both the PATH and the VALUE are single-quote-escaped via [shellSingleQuote]:
     * the path has already been validated by [TunableMetadata.validateCustomSysfsPath]
     * (which rejects shell metacharacters), but we quote it here too for
     * defence-in-depth and consistency with the other emit* helpers.
     */
    internal fun StringBuilder.emitSysfsWrite(path: String, value: String) {
        val qpath = shellSingleQuote(path)
        appendLine("[ -e $qpath ] && ( chmod 666 $qpath ) 2>/dev/null")
        appendLine("[ -e $qpath ] && ( printf %s ${shellSingleQuote(value)} > $qpath ) 2>/dev/null")
        appendLine("[ -e $qpath ] && ( chmod 444 $qpath ) 2>/dev/null")
    }

    private fun StringBuilder.emitGpuWrite(gpuRoot: String, relativePath: String, value: String) {
        val path = "$gpuRoot/$relativePath"
        // GPU freq writes on Adreno KGSL also benefit from the chmod
        // sandwich because msm-adreno-tz can override caps. We do it
        // unconditionally for safety — chmod-on-non-existent path is
        // a harmless error. Subshell-wrap the write so a redirect-create
        // failure stays out of the runner output.
        // Single-quote BOTH the path (gpuRoot comes from a device probe, so
        // quoting is defence-in-depth) and the value (user/profile string —
        // shellSingleQuote prevents metacharacter injection).
        val qpath = shellSingleQuote(path)
        appendLine("[ -e $qpath ] && ( chmod 666 $qpath ) 2>/dev/null")
        appendLine("[ -e $qpath ] && ( printf %s ${shellSingleQuote(value)} > $qpath ) 2>/dev/null")
        appendLine("[ -e $qpath ] && ( chmod 444 $qpath ) 2>/dev/null")
    }
}
