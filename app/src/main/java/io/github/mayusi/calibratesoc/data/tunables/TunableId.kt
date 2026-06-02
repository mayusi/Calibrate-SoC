package io.github.mayusi.calibratesoc.data.tunables

import kotlinx.serialization.Serializable

/**
 * Stable, content-addressed identity for a single tunable so we can
 * snapshot and revert deterministically. Two probes of the same kernel
 * surface must produce the same id — that's why we key on the literal
 * sysfs path / Settings key, not on a synthetic UUID.
 *
 * The `kind` discriminator tells [TunableSnapshotStore] which writer to
 * use for the revert, since the snapshot file outlives the CapabilityReport
 * that produced it (it has to survive a reboot).
 */
@Serializable
data class TunableId(
    val kind: TunableKind,
    val target: String,
)

@Serializable
enum class TunableKind {
    /** Sysfs file write — RootWriter or ShizukuWriter. */
    SYSFS,
    /** Settings.System key — SettingsKeyWriter (AYN-style). */
    SETTINGS_SYSTEM,
    /** Vendor intent action — SettingsKeyWriter via intent (AYANEO). */
    VENDOR_INTENT,
}
