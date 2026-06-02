package io.github.mayusi.calibratesoc.data.presets

import kotlinx.serialization.Serializable

/**
 * A device-applicable tuning profile. The same shape regardless of where
 * it came from — built-in algorithmic, SoC-family rule, per-device
 * community contribution, or user-edited. The [verification] tier tells
 * the UI which "safety badge" to render and which confirm modal to gate
 * Apply behind.
 *
 * Values are sparse on purpose: a preset only declares what it wants to
 * change. Anything omitted is left at the kernel's current state. This
 * lets a "GPU-only" community preset coexist with "CPU-only" sliders
 * without one stomping the other.
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val verification: VerificationTier,
    /** Optional URL to wherever this preset was sourced from — repo,
     *  forum post, datasheet. Surfaced as a "Source" link in the UI so
     *  users can read the rationale before applying. */
    val sourceUrl: String? = null,
    /** Per-policyId max freq in kHz. Empty = don't change. */
    val cpuPolicyMaxKhz: Map<Int, Int> = emptyMap(),
    /** Per-policyId min freq in kHz. Useful for "force performance" tunes. */
    val cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),
    /** Per-policyId governor name. Must exist in scaling_available_governors
     *  or the writer drops it with WriteResult.Rejected. */
    val cpuPolicyGovernor: Map<Int, String> = emptyMap(),
    val gpuMaxHz: Long? = null,
    val gpuMinHz: Long? = null,
    val gpuGovernor: String? = null,
)

/**
 * Trust tier. Drives the UI's "safety badge" + which confirm to gate Apply.
 */
@Serializable
enum class VerificationTier {
    /** Bundled with the app + verified on the exact device the user is on.
     *  No extra confirm beyond the first-OC ack. Examples: TheOldTaylor's
     *  Odin 3 underclocks when running on an Odin 3. */
    COMMUNITY_TUNED,

    /** Built-in algorithm + we recognize the device's SoC family so we
     *  know the family's behavior is well-trodden (Snapdragon Adreno on
     *  AOSP). Apply fires after the standard first-OC ack. */
    GENERIC_KNOWN_FAMILY,

    /** Built-in algorithm but the SoC family is something we have little
     *  signal on (Xclipse, Tegra, Unisoc, anything not in our family rule
     *  set). Apply gates behind an EXTRA "unknown device — I accept the
     *  risk" confirm. The preset is still generated and visible. */
    GENERIC_UNKNOWN_FAMILY,

    /** User-edited / cloned. Same UX as GENERIC_KNOWN_FAMILY. */
    USER_CUSTOM,
}
