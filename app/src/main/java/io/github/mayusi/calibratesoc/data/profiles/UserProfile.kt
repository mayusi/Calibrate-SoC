package io.github.mayusi.calibratesoc.data.profiles

import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import kotlinx.serialization.Serializable

/**
 * A user-saved profile. Identical shape to [Preset] but carries a
 * stable user-chosen id + an "auto-apply on boot" flag that the
 * boot receiver checks when the user opts a profile into persistence.
 *
 * Storing as JSON in DataStore (one file, atomic writes) rather than
 * a Room table because there are typically ~5 profiles per device and
 * we never query individual fields — we always read the whole set.
 */
@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val description: String,
    val cpuPolicyMaxKhz: Map<Int, Int> = emptyMap(),
    val cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),
    val cpuPolicyGovernor: Map<Int, String> = emptyMap(),
    val gpuMaxHz: Long? = null,
    val gpuMinHz: Long? = null,
    val gpuGovernor: String? = null,
    /** When true, BootRevertReceiver re-applies this instead of reverting. */
    val applyOnBoot: Boolean = false,
    val createdAtMs: Long,
    /**
     * Generic sysfs/procfs knobs beyond the first-class fields above.
     * Mirrors [Preset.extraSysfs] — see that field's kdoc for semantics.
     * Serialized automatically by kotlinx.serialization; safe for the
     * OTA/preset-sharing channel.
     */
    val extraSysfs: Map<String, String> = emptyMap(),
    /**
     * The [DeviceIdentity.knownHandheldKey] of the device on which this
     * profile was created (e.g. "retroid_pocket6", "ayn_odin3").  Null for
     * profiles imported from a backup or share code that predates this field,
     * or for profiles created on an unrecognised device.
     *
     * Used by the UI to show "from <device>" when the origin device differs
     * from the current device — a subtle safety hint before Apply.
     */
    val createdOnDeviceKey: String? = null,
    /**
     * Human-readable name for [createdOnDeviceKey], captured at save time
     * from the device's display name in the adapter or a fallback.
     * Stored separately so the UI can render "from Retroid Pocket 6" even
     * when the current device doesn't have that adapter loaded.
     */
    val createdOnDeviceName: String? = null,
    /**
     * Mirror of [Preset.targetHandheldKeys] — the list of device keys this
     * profile is safe to apply to (e.g. ["retroid_pocket6"]).
     *
     * Null = applies to any device (backwards-compatible default; old persisted
     * profiles that pre-date this field deserialize to null via
     * kotlinx.serialization's ignoreUnknownKeys=true on the ProfileStore codec).
     *
     * **Why this field exists on UserProfile:**
     * [ProfileApplier.apply] receives a [Preset], not a [UserProfile].  Every
     * real apply path converts UserProfile → Preset first via [toPreset].  If
     * [toPreset] drops targetHandheldKeys, Gate 1 in ProfileApplier never sees
     * the restriction, so a device-targeted preset silently applies everywhere.
     * Keeping this field here — and propagating it through [fromPreset]/[toPreset]
     * — closes that hole on ALL production apply paths:
     *   - Profiles screen Apply button  (UserProfile → toPreset → ProfileApplier)
     *   - Per-app auto-switch           (ForegroundAppWatcher → same path)
     *   - Boot re-apply                 (BootRevertReceiver → same path)
     *   - Share-code import             (ShareablePreset → toUserProfile → toPreset)
     */
    val targetHandheldKeys: List<String>? = null,
) {
    fun toPreset(): Preset = Preset(
        id = id,
        name = name,
        description = description,
        verification = VerificationTier.USER_CUSTOM,
        cpuPolicyMaxKhz = cpuPolicyMaxKhz,
        cpuPolicyMinKhz = cpuPolicyMinKhz,
        cpuPolicyGovernor = cpuPolicyGovernor,
        gpuMaxHz = gpuMaxHz,
        gpuMinHz = gpuMinHz,
        gpuGovernor = gpuGovernor,
        extraSysfs = extraSysfs,
        // Propagate targeting so ProfileApplier's Gate 1 fires on every
        // UserProfile → Preset apply path (Profiles screen, auto-switch, boot).
        targetHandheldKeys = targetHandheldKeys,
    )

    companion object {
        fun fromPreset(
            preset: Preset,
            applyOnBoot: Boolean = false,
            createdOnDeviceKey: String? = null,
            createdOnDeviceName: String? = null,
        ): UserProfile = UserProfile(
            id = "user_${System.currentTimeMillis()}",
            name = preset.name,
            description = preset.description,
            cpuPolicyMaxKhz = preset.cpuPolicyMaxKhz,
            cpuPolicyMinKhz = preset.cpuPolicyMinKhz,
            cpuPolicyGovernor = preset.cpuPolicyGovernor,
            gpuMaxHz = preset.gpuMaxHz,
            gpuMinHz = preset.gpuMinHz,
            gpuGovernor = preset.gpuGovernor,
            extraSysfs = preset.extraSysfs,
            applyOnBoot = applyOnBoot,
            createdAtMs = System.currentTimeMillis(),
            createdOnDeviceKey = createdOnDeviceKey,
            createdOnDeviceName = createdOnDeviceName,
            // Carry targeting forward so toPreset() can re-surface it to
            // ProfileApplier's Gate 1 on every subsequent apply.
            targetHandheldKeys = preset.targetHandheldKeys,
        )
    }
}

/** Persisted shape of the whole user-profile store. */
@Serializable
data class ProfileStore(
    val version: Int = 1,
    val profiles: List<UserProfile> = emptyList(),
    /**
     * Legacy single-profile override map: package name -> profile id.
     *
     * Kept for backwards-compat deserialization. [ForegroundAppWatcher] prefers
     * [perAppBundles] when a bundle exists for a package; otherwise it falls back
     * to this map so existing per-app overrides continue to work without migration.
     * New writes go to [perAppBundles] (a bundle with only [PerAppBundle.profileId] set).
     */
    val perAppOverrides: Map<String, String> = emptyMap(),
    /**
     * Full per-app tune bundles: package name -> [PerAppBundle].
     *
     * Supersedes [perAppOverrides] per package when present. A bundle with only
     * [PerAppBundle.profileId] set is semantically identical to a [perAppOverrides]
     * entry — the legacy map is not written when [perAppBundles] already covers
     * a package. Serialized by kotlinx.serialization; ignoreUnknownKeys handles
     * older stores that pre-date this field.
     */
    val perAppBundles: Map<String, PerAppBundle> = emptyMap(),
)
