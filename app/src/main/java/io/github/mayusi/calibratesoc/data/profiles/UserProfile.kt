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
    )

    companion object {
        fun fromPreset(preset: Preset, applyOnBoot: Boolean = false): UserProfile = UserProfile(
            id = "user_${System.currentTimeMillis()}",
            name = preset.name,
            description = preset.description,
            cpuPolicyMaxKhz = preset.cpuPolicyMaxKhz,
            cpuPolicyMinKhz = preset.cpuPolicyMinKhz,
            cpuPolicyGovernor = preset.cpuPolicyGovernor,
            gpuMaxHz = preset.gpuMaxHz,
            gpuMinHz = preset.gpuMinHz,
            gpuGovernor = preset.gpuGovernor,
            applyOnBoot = applyOnBoot,
            createdAtMs = System.currentTimeMillis(),
        )
    }
}

/** Persisted shape of the whole user-profile store. */
@Serializable
data class ProfileStore(
    val version: Int = 1,
    val profiles: List<UserProfile> = emptyList(),
    /** package name -> profile id. The Accessibility service maps the
     *  current foreground app through this to decide what to apply. */
    val perAppOverrides: Map<String, String> = emptyMap(),
)
