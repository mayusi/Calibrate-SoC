package io.github.mayusi.calibratesoc.data.profiles

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for "apply this preset, return the per-tunable
 * results". Called from:
 *
 *   - Tune screen (manual Apply, Community Tuned tap)
 *   - Boot receiver (re-apply applyOnBoot profiles)
 *   - Accessibility service (per-app auto-switch)
 *
 * Pulled out of TuneViewModel so the three callers above don't each
 * grow their own copy of the same per-policy iteration.
 *
 * Each write routes through TunableWriter which snapshots before
 * writing — so even an auto-switch firing 50 times in a row leaves
 * exactly one journal entry per tunable per session (the snapshot
 * coalescer keeps the original stock value).
 */
@Singleton
class ProfileApplier @Inject constructor(
    private val tunableWriter: TunableWriter,
) {
    suspend fun apply(
        preset: Preset,
        report: CapabilityReport,
        reason: String,
    ): List<WriteResult> {
        val results = mutableListOf<WriteResult>()
        for ((policyId, maxKhz) in preset.cpuPolicyMaxKhz) {
            results += tunableWriter.write(
                id = Tunables.cpuMaxFreq(policyId),
                value = maxKhz.toString(),
                report = report,
                reason = reason,
            )
        }
        for ((policyId, minKhz) in preset.cpuPolicyMinKhz) {
            results += tunableWriter.write(
                id = Tunables.cpuMinFreq(policyId),
                value = minKhz.toString(),
                report = report,
                reason = reason,
            )
        }
        for ((policyId, gov) in preset.cpuPolicyGovernor) {
            results += tunableWriter.write(
                id = Tunables.cpuGovernor(policyId),
                value = gov,
                report = report,
                reason = reason,
            )
        }
        // GPU writes — only if the capability probe found a usable
        // root path. Skipped silently on devices where the probe gave
        // up (Mali kernels without a recognisable devfreq dir).
        report.gpu?.rootPath?.let { gpuRoot ->
            preset.gpuMinHz?.let { hz ->
                results += tunableWriter.write(
                    id = Tunables.gpuMinFreq(gpuRoot),
                    value = hz.toString(),
                    report = report,
                    reason = reason,
                )
            }
            preset.gpuMaxHz?.let { hz ->
                results += tunableWriter.write(
                    id = Tunables.gpuMaxFreq(gpuRoot),
                    value = hz.toString(),
                    report = report,
                    reason = reason,
                )
            }
            preset.gpuGovernor?.let { gov ->
                results += tunableWriter.write(
                    id = Tunables.gpuGovernor(gpuRoot),
                    value = gov,
                    report = report,
                    reason = reason,
                )
            }
        }

        // Generic extraSysfs knobs — path→value pairs validated through
        // TunableMetadata before being dispatched to TunableWriter.
        //
        // Validation order per entry:
        //   1. TunableMetadata.validateCustomSysfsPath → reject bad paths.
        //   2. TunableMetadata.forId(...).validate(value) → reject bad values.
        //
        // A validation failure is recorded as WriteResult.Rejected with a
        // message so the caller can surface it without crashing.
        for ((path, value) in preset.extraSysfs) {
            val id = TunableId(kind = TunableKind.SYSFS, target = path)
            val pathError = TunableMetadata.validateCustomSysfsPath(path)
            if (pathError != null) {
                results += WriteResult.Rejected(
                    id = id,
                    errno = null,
                    message = "extraSysfs path rejected: $pathError",
                )
                continue
            }
            val valueError = TunableMetadata.forId(id).validate(value)
            if (valueError != null) {
                results += WriteResult.Rejected(
                    id = id,
                    errno = null,
                    message = "extraSysfs value rejected for $path: $valueError",
                )
                continue
            }
            results += tunableWriter.write(
                id = id,
                value = value,
                report = report,
                reason = reason,
            )
        }
        return results
    }
}
