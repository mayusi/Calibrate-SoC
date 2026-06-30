package io.github.mayusi.calibratesoc.ui.insights

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import org.junit.Test

/**
 * Pure JVM tests for the package-keyed "APPLIED" badge predicate used by
 * InsightsScreen's BEST PROFILE PER GAME panel (BUG 2 fix).
 *
 * The OLD (buggy) predicate was "ANY bundle in the store has this profileId" —
 * which mislabels a sibling app as APPLIED whenever it happens to share the
 * recommended profile. The FIXED predicate matches THIS package's OWN bundle:
 *
 *     boundProfileId(packageName) == recommendedProfileId
 *
 * These tests assert the fixed predicate behaves package-precisely. They
 * replicate the screen's pure logic (no Compose / Android needed).
 */
class InsightsAppliedBadgeByPackageTest {

    /** Mirrors the screen-side badge computation in InsightsScreen. */
    private fun isApplied(
        store: ProfileStore,
        packageName: String,
        recommendedProfileName: String,
    ): Boolean {
        val profileId = store.profiles.firstOrNull { it.name == recommendedProfileName }?.id
        val boundId = store.perAppBundles[packageName]?.profileId
        return profileId != null && boundId == profileId
    }

    private fun profile(id: String, name: String) =
        UserProfile(id = id, name = name, description = "", createdAtMs = 0L)

    // ── The bug: shared profileId must NOT bleed the badge across apps ─────────

    @Test
    fun `badge is true only for the app whose own bundle is bound`() {
        val perf = profile("p_perf", "Performance")
        // gameA is bound to Performance; gameB has NO bundle (never applied).
        val store = ProfileStore(
            profiles = listOf(perf),
            perAppBundles = mapOf(
                "com.example.gameA" to PerAppBundle(profileId = "p_perf"),
            ),
        )

        // gameA: bound to the recommended profile → APPLIED.
        assertThat(isApplied(store, "com.example.gameA", "Performance")).isTrue()
        // gameB: same recommended profile, but its OWN bundle is unbound →
        // NOT applied. The old "any bundle with this profileId" check would
        // have wrongly returned true here.
        assertThat(isApplied(store, "com.example.gameB", "Performance")).isFalse()
    }

    @Test
    fun `badge is false when this app is bound to a DIFFERENT profile`() {
        val perf = profile("p_perf", "Performance")
        val eco = profile("p_eco", "Eco")
        val store = ProfileStore(
            profiles = listOf(perf, eco),
            perAppBundles = mapOf(
                // gameA is bound to Eco, but the recommendation is Performance.
                "com.example.gameA" to PerAppBundle(profileId = "p_eco"),
            ),
        )
        assertThat(isApplied(store, "com.example.gameA", "Performance")).isFalse()
    }

    @Test
    fun `badge is true when this app is bound to exactly the recommended profile`() {
        val perf = profile("p_perf", "Performance")
        val store = ProfileStore(
            profiles = listOf(perf),
            perAppBundles = mapOf(
                "com.example.gameA" to PerAppBundle(profileId = "p_perf", refreshRateHz = 90f),
            ),
        )
        assertThat(isApplied(store, "com.example.gameA", "Performance")).isTrue()
    }

    @Test
    fun `badge is false when the recommended profile no longer exists`() {
        // Recommended profile was renamed/deleted → cannot resolve its id → no badge.
        val store = ProfileStore(
            profiles = emptyList(),
            perAppBundles = mapOf(
                "com.example.gameA" to PerAppBundle(profileId = "p_perf"),
            ),
        )
        assertThat(isApplied(store, "com.example.gameA", "Performance")).isFalse()
    }

    @Test
    fun `badge is false when this app has no bundle at all`() {
        val perf = profile("p_perf", "Performance")
        val store = ProfileStore(profiles = listOf(perf), perAppBundles = emptyMap())
        assertThat(isApplied(store, "com.example.gameA", "Performance")).isFalse()
    }
}
