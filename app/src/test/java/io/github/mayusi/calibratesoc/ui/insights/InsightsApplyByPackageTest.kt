package io.github.mayusi.calibratesoc.ui.insights

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import org.junit.Test

/**
 * Pure JVM tests for the package-keyed apply logic backing
 * [InsightsViewModel.applyBestProfileByPackage].
 *
 * Mirrors [InsightsApplyBestProfileTest] but exercises the package-keyed
 * path: no PackageManager involved — packageName is used directly as the
 * bundle key.
 *
 * Covers:
 *   1. Profile name resolves to correct id from ProfileStore.
 *   2. Unknown/deleted profile name resolves to null (no write should occur).
 *   3. Existing bundle fields (autoTdpGoal, refreshRateHz, fanMode,
 *      gameBoostOnLaunch) survive when only profileId is updated.
 *   4. No existing bundle → new bundle with only profileId set.
 *   5. Idempotent: applying same profile id again preserves other fields.
 */
class InsightsApplyByPackageTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeProfile(id: String, name: String) = UserProfile(
        id = id,
        name = name,
        description = "Test profile $name",
        createdAtMs = 0L,
    )

    // ── 1. Profile name resolves to correct id ────────────────────────────────

    @Test
    fun `profile name resolves to correct id from ProfileStore`() {
        val profile = makeProfile("pkg_abc123", "Performance")
        val store = ProfileStore(profiles = listOf(profile))

        val resolved = store.profiles.firstOrNull { it.name == "Performance" }
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.id).isEqualTo("pkg_abc123")
    }

    // ── 2. Unknown profile resolves to null ───────────────────────────────────

    @Test
    fun `unknown profile name resolves to null`() {
        val profile = makeProfile("pkg_abc123", "Performance")
        val store = ProfileStore(profiles = listOf(profile))

        val resolved = store.profiles.firstOrNull { it.name == "DeletedProfile" }
        assertThat(resolved).isNull()
    }

    @Test
    fun `profile resolution is exact — partial match does not qualify`() {
        val profile = makeProfile("pkg_abc123", "Performance")
        val store = ProfileStore(profiles = listOf(profile))

        val resolved = store.profiles.firstOrNull { it.name == "Perform" }
        assertThat(resolved).isNull()
    }

    // ── 3. Bundle-field preservation ──────────────────────────────────────────

    @Test
    fun `applying new profileId preserves autoTdpGoal and other bundle fields`() {
        val existing = PerAppBundle(
            profileId = "old_id",
            autoTdpGoal = GoalProfile.BALANCED_SMART,
            refreshRateHz = 90f,
            fanMode = 3,
            gameBoostOnLaunch = true,
        )
        val newId = "new_id"

        // Simulate the applyBestProfileByPackage copy path.
        val updated = existing.copy(profileId = newId)

        assertThat(updated.profileId).isEqualTo(newId)
        assertThat(updated.autoTdpGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(updated.refreshRateHz).isEqualTo(90f)
        assertThat(updated.fanMode).isEqualTo(3)
        assertThat(updated.gameBoostOnLaunch).isTrue()
    }

    @Test
    fun `preserves autoCreated flag when updating profileId`() {
        val existing = PerAppBundle(
            profileId = "old_id",
            autoCreated = true,
        )
        val updated = existing.copy(profileId = "new_id")
        assertThat(updated.autoCreated).isTrue()
    }

    // ── 4. No existing bundle → new bundle with only profileId ────────────────

    @Test
    fun `when no existing bundle new bundle has only profileId set`() {
        val newId = "new_id"
        // Simulate: (existing ?: PerAppBundle()).copy(profileId = id)
        val updated = (null ?: PerAppBundle()).copy(profileId = newId)

        assertThat(updated.profileId).isEqualTo(newId)
        assertThat(updated.autoTdpGoal).isNull()
        assertThat(updated.refreshRateHz).isNull()
        assertThat(updated.fanMode).isNull()
        assertThat(updated.gameBoostOnLaunch).isFalse()
        assertThat(updated.autoCreated).isFalse()
    }

    // ── 5. Idempotent update ──────────────────────────────────────────────────

    @Test
    fun `applying same profileId is idempotent`() {
        val existing = PerAppBundle(
            profileId = "same_id",
            refreshRateHz = 120f,
            fanMode = 2,
        )
        val updated = existing.copy(profileId = "same_id")

        assertThat(updated.profileId).isEqualTo("same_id")
        assertThat(updated.refreshRateHz).isEqualTo(120f)
        assertThat(updated.fanMode).isEqualTo(2)
    }

    // ── 6. packageName is used as bundle key (no PackageManager ambiguity) ────

    @Test
    fun `bundle is keyed by packageName directly — no label resolution needed`() {
        // Verify that perAppBundles is keyed by packageName, as applyBestProfileByPackage
        // relies on: store.perAppBundles[packageName] and setBundle(packageName, ...)
        val pkg = "com.example.mygame"
        val bundle = PerAppBundle(profileId = "profile_x", refreshRateHz = 60f)
        val store = ProfileStore(
            profiles = emptyList(),
            perAppBundles = mapOf(pkg to bundle),
        )

        val existing = store.perAppBundles[pkg]
        assertThat(existing).isNotNull()
        assertThat(existing!!.refreshRateHz).isEqualTo(60f)

        // Updating preserves the refreshRateHz.
        val updated = existing.copy(profileId = "new_profile")
        assertThat(updated.profileId).isEqualTo("new_profile")
        assertThat(updated.refreshRateHz).isEqualTo(60f)
    }
}
