package io.github.mayusi.calibratesoc.data.profiles

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.presets.Preset

/**
 * Pure device-safety validator for [Preset] → [CapabilityReport] compatibility.
 *
 * Extracted from [ProfileApplier] so that BOTH the live-apply path AND the
 * script-generation path share exactly the same logic — no duplication.
 *
 * ## Gates
 *
 * ### Gate 1: device-targeting (targetHandheldKeys)
 * If the preset declares a [Preset.targetHandheldKeys] list and the current
 * device's [DeviceIdentity.knownHandheldKey] is NOT in that list, the preset
 * is rejected immediately.  This prevents an RP6 preset (3-cluster, 2.2 GHz
 * big cores) from writing incorrect MHz values onto an Odin 3 (2-cluster).
 *
 * **Gate 1 is the primary protection** for device-targeted presets.  It only
 * works when [Preset.targetHandheldKeys] is populated, which is why every path
 * that converts a UserProfile → Preset MUST propagate the targeting field.
 * That propagation is enforced by [UserProfile.toPreset] and
 * [ShareablePreset.toUserProfile].
 *
 * ### Gate 2: policy-existence
 * Rejects any preset that references a cpuPolicy policyId that does not exist
 * in the current device's [CapabilityReport.cpuPolicies].
 *
 * **Honesty: Gate 2 does NOT catch same-policyId/different-topology mismatches.**
 * Example: both the RP6 and Odin 3 have policy0, so a preset that sets
 * policy0 max to an RP6-specific value (2.0 GHz little) will PASS Gate 2 on
 * an Odin 3 (which also has policy0).  Gate 2 is defence-in-depth only —
 * it does NOT substitute for Gate 1.  Callers MUST NOT rely on Gate 2 alone
 * for cross-device safety; Gate 1 (targetHandheldKeys propagation) is the
 * real guard.
 */
object PresetSafetyGate {

    /** Result of a safety check. */
    sealed class SafetyVerdict {
        /** Preset passed all gates; safe to apply or generate. */
        object Ok : SafetyVerdict()

        /**
         * Preset was rejected by one of the gates.
         * [reason] is a user-visible explanation suitable for display or logging.
         */
        data class Rejected(val reason: String) : SafetyVerdict()
    }

    /**
     * Run all safety gates against [preset] + [report].
     *
     * Returns [SafetyVerdict.Ok] when the preset is safe to apply on this device,
     * or [SafetyVerdict.Rejected] with a clear reason string when it is not.
     *
     * This function is pure (no I/O, no coroutines) so it can be called from
     * both suspend and non-suspend contexts.
     */
    fun check(preset: Preset, report: CapabilityReport): SafetyVerdict {
        // ── Gate 1: device-targeting check ────────────────────────────────────
        // If the preset declares target devices and this device is not in the
        // list, refuse all writes.  A foreign MHz value on the wrong cluster
        // topology is a real, harmful write — not a no-op.
        //
        // NOTE: This gate only fires when targetHandheldKeys is non-null.  Every
        // path that creates a Preset from a UserProfile MUST propagate
        // targetHandheldKeys (via UserProfile.toPreset) so this gate is not
        // silently bypassed on the Profiles screen, BootRevertReceiver,
        // ForegroundAppWatcher, or share-code import.
        val targetKeys = preset.targetHandheldKeys
        val currentKey = report.device.knownHandheldKey
        if (targetKeys != null && (currentKey == null || currentKey !in targetKeys)) {
            val targetDisplay = targetKeys.joinToString(", ")
            val currentDisplay = currentKey ?: "unknown"
            return SafetyVerdict.Rejected(
                "This preset targets [$targetDisplay] and cannot be safely applied " +
                    "to your device [$currentDisplay]. Import a preset made for your device.",
            )
        }

        // ── Gate 2: policy-existence check ────────────────────────────────────
        // Reject any preset that references a policyId the current device does
        // not have.  This catches topology mismatches (e.g. an RP6 preset with
        // policy3 / policy7 imported onto an Odin 3 that only has policy0 /
        // policy6).  We check ALL policy maps together so a single error message
        // lists ALL unknown IDs.
        //
        // HONESTY: This gate does NOT catch same-policyId/different-topology
        // mismatches (e.g. RP6 policy0 MHz on Odin 3 policy0 — both devices
        // have policy0, so the gate passes even though the MHz ceiling differs).
        // Gate 1 (targetHandheldKeys) is the real protection for those cases.
        val knownPolicyIds = report.cpuPolicies.map { it.policyId }.toSet()
        val foreignPolicyIds = (
            preset.cpuPolicyMaxKhz.keys +
                preset.cpuPolicyMinKhz.keys +
                preset.cpuPolicyGovernor.keys
        ).toSet() - knownPolicyIds
        if (foreignPolicyIds.isNotEmpty()) {
            val unknownList = foreignPolicyIds.sorted().joinToString(", ") { "policy$it" }
            val knownList = knownPolicyIds.sorted().joinToString(", ") { "policy$it" }
            return SafetyVerdict.Rejected(
                "Preset references CPU policies [$unknownList] that do not exist " +
                    "on this device (known: [$knownList]). Apply blocked to prevent " +
                    "writing incorrect MHz values to the wrong cluster.",
            )
        }

        return SafetyVerdict.Ok
    }
}
