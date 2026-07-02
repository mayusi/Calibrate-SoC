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

    /**
     * AYANEO vendor PERFORMANCE-MODE ordinal (0..4) — a PATHLESS/abstract tunable driven
     * through the `com_set_performance_mode:<int>` binder token by [AyaneoVendorWriter]. It
     * is NOT a sysfs path: a mode atomically reconfigures CPU caps + governor + GPU max +
     * fan through the vendor PerformanceManager's honored root path, which is why raw
     * per-policy scaling_max_freq writes get walked back by the perf-HAL but a MODE sticks.
     *
     * A mode is abstract, so its [TunableId.target] is a synthetic LABEL, never a real
     * sysfs path — it must NOT be run through the sysfs-path regex classifiers (they only
     * match `/sys/...` strings). [WriterRegistry] routes this kind to [AyaneoVendorWriter]
     * only when the AYANEO binder is live, else to [NoopWriter] (this lever exists ONLY on
     * AYANEO). Readback/verify reads the vendor conf file `currentMode` field — there is no
     * `com_get_performance_mode` token, so the conf file IS the readback.
     */
    AYANEO_PERF_MODE,
}
