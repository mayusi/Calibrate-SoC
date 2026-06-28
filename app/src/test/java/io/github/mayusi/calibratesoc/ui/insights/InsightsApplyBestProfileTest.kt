package io.github.mayusi.calibratesoc.ui.insights

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Pure-JVM tests for the "apply best profile" feature logic in [InsightsViewModel].
 *
 * Tests cover the pure companion-object helper [InsightsViewModel.resolvePackageName]
 * (mocked PackageManager, no Android runtime) and the bundle-field preservation /
 * profile-resolution logic that backs [InsightsViewModel.applyBestProfile].
 *
 * Covers:
 *   1. resolvePackageName — exact match returns the package name.
 *   2. resolvePackageName — case-insensitive match.
 *   3. resolvePackageName — no match returns null.
 *   4. resolvePackageName — ambiguous labels (multiple apps with the same name)
 *      returns AMBIGUOUS_PACKAGE sentinel.
 *   5. Profile name → id resolution: known name resolves to correct id.
 *   6. Profile name → id resolution: unknown/deleted name returns null (no write).
 *   7. Bundle-field preservation: existing autoTdpGoal/refreshRateHz/fanMode/
 *      gameBoostOnLaunch survive when only profileId is updated.
 *   8. Bundle-field preservation: no existing bundle → new bundle with only
 *      profileId set (all other fields at defaults).
 *   9. AMBIGUOUS_PACKAGE sentinel constant has a stable value that won't collide
 *      with real package names (starts with "__").
 */
class InsightsApplyBestProfileTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeProfile(id: String, name: String) = UserProfile(
        id = id,
        name = name,
        description = "Test profile $name",
        createdAtMs = 0L,
    )

    /**
     * Build a mock Context whose PackageManager returns [apps] as installed apps.
     * Each element is (packageName, displayLabel).
     */
    private fun fakeContext(vararg apps: Pair<String, String>): Context {
        val pm = mockk<PackageManager>()
        val infos = apps.map { (pkg, label) ->
            val info = ApplicationInfo()
            info.packageName = pkg
            // getApplicationLabel is called per-info in resolvePackageName
            every { pm.getApplicationLabel(info) } returns label
            info
        }

        // resolvePackageName branches on Build.VERSION.SDK_INT >= TIRAMISU (33).
        // In the pure-JVM test runtime Build.VERSION.SDK_INT == 0 so it always
        // takes the @Suppress("DEPRECATION") branch (SDK_INT < 33).
        @Suppress("DEPRECATION")
        every { pm.getInstalledApplications(0) } returns infos

        val ctx = mockk<Context>()
        every { ctx.packageManager } returns pm
        return ctx
    }

    // ── resolvePackageName ────────────────────────────────────────────────────

    @Test
    fun `resolvePackageName returns package name for exact label match`() {
        val ctx = fakeContext("com.example.game" to "My Game", "com.other" to "Other App")
        val result = InsightsViewModel.resolvePackageName(ctx, "My Game")
        assertThat(result).isEqualTo("com.example.game")
    }

    @Test
    fun `resolvePackageName is case-insensitive`() {
        val ctx = fakeContext("com.example.game" to "My Game")
        val result = InsightsViewModel.resolvePackageName(ctx, "my game")
        assertThat(result).isEqualTo("com.example.game")
    }

    @Test
    fun `resolvePackageName trims whitespace before matching`() {
        val ctx = fakeContext("com.example.game" to "My Game")
        val result = InsightsViewModel.resolvePackageName(ctx, "  My Game  ")
        assertThat(result).isEqualTo("com.example.game")
    }

    @Test
    fun `resolvePackageName returns null when no app matches the label`() {
        val ctx = fakeContext("com.example.game" to "My Game")
        val result = InsightsViewModel.resolvePackageName(ctx, "Unknown App")
        assertThat(result).isNull()
    }

    @Test
    fun `resolvePackageName returns AMBIGUOUS_PACKAGE when multiple apps share a label`() {
        val ctx = fakeContext(
            "com.example.game1" to "Duplicate Name",
            "com.example.game2" to "Duplicate Name",
        )
        val result = InsightsViewModel.resolvePackageName(ctx, "Duplicate Name")
        assertThat(result).isEqualTo(InsightsViewModel.AMBIGUOUS_PACKAGE)
    }

    @Test
    fun `resolvePackageName returns single match when only one of many shares the label`() {
        val ctx = fakeContext(
            "com.example.game" to "Target Game",
            "com.other1" to "Other One",
            "com.other2" to "Other Two",
        )
        val result = InsightsViewModel.resolvePackageName(ctx, "Target Game")
        assertThat(result).isEqualTo("com.example.game")
    }

    // ── AMBIGUOUS_PACKAGE sentinel ────────────────────────────────────────────

    @Test
    fun `AMBIGUOUS_PACKAGE sentinel starts with __ so it cannot be a real package name`() {
        // Android package names follow Java identifier rules — they cannot start
        // with underscores as the first character of a segment. This ensures
        // the sentinel will never collide with a real installed package.
        assertThat(InsightsViewModel.AMBIGUOUS_PACKAGE).startsWith("__")
    }

    // ── Profile name → id resolution (pure store logic) ───────────────────────

    @Test
    fun `profile name resolves to correct id from ProfileStore`() {
        val profile = makeProfile(id = "user_abc123", name = "Performance")
        val store = ProfileStore(profiles = listOf(profile))

        val resolved = store.profiles.firstOrNull { it.name == "Performance" }
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.id).isEqualTo("user_abc123")
    }

    @Test
    fun `profile name that no longer exists resolves to null`() {
        val profile = makeProfile(id = "user_abc123", name = "Performance")
        val store = ProfileStore(profiles = listOf(profile))

        val resolved = store.profiles.firstOrNull { it.name == "DeletedProfile" }
        assertThat(resolved).isNull()
    }

    @Test
    fun `profile name resolution is exact — partial name does not match`() {
        val profile = makeProfile(id = "user_abc123", name = "Performance")
        val store = ProfileStore(profiles = listOf(profile))

        val resolved = store.profiles.firstOrNull { it.name == "Perform" }
        assertThat(resolved).isNull()
    }

    // ── Bundle-field preservation ──────────────────────────────────────────────

    @Test
    fun `updating profileId preserves existing autoTdpGoal and other bundle fields`() {
        val existingBundle = PerAppBundle(
            profileId = "old_profile_id",
            autoTdpGoal = io.github.mayusi.calibratesoc.data.autotdp.GoalProfile.BALANCED_SMART,
            refreshRateHz = 90f,
            fanMode = 4,
            gameBoostOnLaunch = true,
        )
        val newProfileId = "new_profile_id"

        // Simulate what applyBestProfile does: copy the existing bundle, override only profileId.
        val updated = existingBundle.copy(profileId = newProfileId)

        assertThat(updated.profileId).isEqualTo(newProfileId)
        assertThat(updated.autoTdpGoal).isEqualTo(
            io.github.mayusi.calibratesoc.data.autotdp.GoalProfile.BALANCED_SMART,
        )
        assertThat(updated.refreshRateHz).isEqualTo(90f)
        assertThat(updated.fanMode).isEqualTo(4)
        assertThat(updated.gameBoostOnLaunch).isTrue()
    }

    @Test
    fun `when no existing bundle apply creates bundle with only profileId set`() {
        // Simulate the (existing ?: PerAppBundle()).copy(profileId = id) path.
        val newProfileId = "new_profile_id"
        val updated = (null ?: PerAppBundle()).copy(profileId = newProfileId)

        assertThat(updated.profileId).isEqualTo(newProfileId)
        // All other fields are at their defaults — no spurious values injected.
        assertThat(updated.autoTdpGoal).isNull()
        assertThat(updated.refreshRateHz).isNull()
        assertThat(updated.fanMode).isNull()
        assertThat(updated.gameBoostOnLaunch).isFalse()
    }

    @Test
    fun `updating profileId to same id is idempotent`() {
        val existing = PerAppBundle(
            profileId = "same_id",
            refreshRateHz = 120f,
        )
        val updated = existing.copy(profileId = "same_id")

        assertThat(updated.profileId).isEqualTo("same_id")
        assertThat(updated.refreshRateHz).isEqualTo(120f)
    }
}
