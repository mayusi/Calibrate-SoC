package io.github.mayusi.calibratesoc.data.profiles

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import org.junit.Test

/**
 * Unit tests for the origin-label fields introduced on [UserProfile]:
 *   - [UserProfile.createdOnDeviceKey]
 *   - [UserProfile.createdOnDeviceName]
 *
 * Verifies that [UserProfile.fromPreset] propagates these values when
 * supplied, and leaves them null when omitted (backwards-compatible default).
 */
class UserProfileOriginTest {

    private val samplePreset = Preset(
        id = "sample",
        name = "Sample Preset",
        description = "A test preset",
        verification = VerificationTier.USER_CUSTOM,
        cpuPolicyMaxKhz = mapOf(0 to 1000000),
    )

    @Test
    fun `fromPreset without device args leaves origin fields null`() {
        val profile = UserProfile.fromPreset(samplePreset, applyOnBoot = false)

        assertThat(profile.createdOnDeviceKey).isNull()
        assertThat(profile.createdOnDeviceName).isNull()
    }

    @Test
    fun `fromPreset with device key and name populates origin fields`() {
        val profile = UserProfile.fromPreset(
            preset = samplePreset,
            applyOnBoot = false,
            createdOnDeviceKey = "retroid_pocket6",
            createdOnDeviceName = "Retroid Pocket 6",
        )

        assertThat(profile.createdOnDeviceKey).isEqualTo("retroid_pocket6")
        assertThat(profile.createdOnDeviceName).isEqualTo("Retroid Pocket 6")
    }

    @Test
    fun `fromPreset with only device key leaves name null`() {
        val profile = UserProfile.fromPreset(
            preset = samplePreset,
            applyOnBoot = false,
            createdOnDeviceKey = "ayn_odin3",
            createdOnDeviceName = null,
        )

        assertThat(profile.createdOnDeviceKey).isEqualTo("ayn_odin3")
        assertThat(profile.createdOnDeviceName).isNull()
    }

    @Test
    fun `origin fields are preserved through toPreset round-trip`() {
        // The origin fields live only on UserProfile, not Preset — this
        // test confirms toPreset() doesn't corrupt anything on the profile
        // that called it (the profile itself still has its origin data).
        val profile = UserProfile.fromPreset(
            preset = samplePreset,
            applyOnBoot = false,
            createdOnDeviceKey = "retroid_pocket6",
            createdOnDeviceName = "Retroid Pocket 6",
        )
        // toPreset() call must not throw and must not alter the profile.
        profile.toPreset()

        assertThat(profile.createdOnDeviceKey).isEqualTo("retroid_pocket6")
        assertThat(profile.createdOnDeviceName).isEqualTo("Retroid Pocket 6")
    }

    @Test
    fun `old profiles without origin fields deserialize with null origin`() {
        // Simulates loading a profile that was serialized before this feature
        // by checking the default-parameter contract — a profile created
        // without the new fields still exists with null origins.
        val legacyProfile = UserProfile(
            id = "legacy",
            name = "Legacy Profile",
            description = "Old profile",
            createdAtMs = 1_000_000L,
            // No createdOnDeviceKey / createdOnDeviceName — defaults to null.
        )

        assertThat(legacyProfile.createdOnDeviceKey).isNull()
        assertThat(legacyProfile.createdOnDeviceName).isNull()
    }
}
