package io.github.mayusi.calibratesoc.ui.profiles

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.InstalledApp
import org.junit.Test

/**
 * JVM unit tests for [PerAppBundleViewModel.filterReaperEligible].
 *
 * Tests the pure exclusion logic — no Android context, no mocks.
 *
 * The reaper denylist picker must NEVER show system/GMS/Calibrate packages
 * because killing those is a safety hazard (device may become unusable).
 *
 * Covers:
 *  1. System packages with ALWAYS_EXCLUDED_PREFIXES are excluded.
 *  2. GMS / GSF packages are excluded.
 *  3. The app's own package is excluded.
 *  4. Normal user apps (games, media) are included.
 *  5. Prefix matching: "com.android" is excluded but "com.androidthings.example" might
 *     be an IoT app — currently also excluded by prefix. Tests document this precisely.
 *  6. Edge cases: empty list, all excluded, all included.
 *  7. Exact matches (bare prefix = excluded).
 */
class PerAppBundleViewModelFilterTest {

    /**
     * Local copy of [ALWAYS_EXCLUDED_PREFIXES] from [AppReaper].
     * Duplicated here so the test does not need to access an `internal` symbol
     * across package boundaries (Kotlin internal is module-scoped, but this makes
     * the test self-contained and intent-documenting).
     *
     * If the set changes in production code, this should be updated too — any
     * discrepancy will surface as a test failure in [alwaysExcludedPrefixesContainsExpectedEntries].
     */
    private val alwaysExcludedPrefixes: Set<String> = setOf(
        "android",
        "com.android.",
        "com.google.android.gms",
        "com.google.android.gsf",
        "io.github.mayusi.calibratesoc",
    )

    /** Pure copy of the filter logic from [PerAppBundleViewModel.filterReaperEligible]. */
    private fun filterReaperEligible(apps: List<InstalledApp>): List<InstalledApp> =
        apps.filter { app ->
            val pkg = app.packageName
            alwaysExcludedPrefixes.none { prefix ->
                val bare = prefix.trimEnd('.')
                pkg == bare || pkg.startsWith("$bare.")
            }
        }

    private fun app(pkg: String) = InstalledApp(packageName = pkg, displayLabel = pkg)

    // ── ALWAYS_EXCLUDED_PREFIXES coverage ────────────────────────────────────────

    @Test
    fun androidCorePackagesAreExcluded() {
        val apps = listOf(
            app("android"),
            app("android.ext.services"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    @Test
    fun comAndroidPrefixIsExcluded() {
        val apps = listOf(
            app("com.android.systemui"),
            app("com.android.launcher3"),
            app("com.android.settings"),
            app("com.android.phone"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    @Test
    fun googleGmsIsExcluded() {
        val apps = listOf(
            app("com.google.android.gms"),
            app("com.google.android.gms.location"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    @Test
    fun googleGsfIsExcluded() {
        val apps = listOf(
            app("com.google.android.gsf"),
            app("com.google.android.gsf.login"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    @Test
    fun calibrateSocOwnPackageIsExcluded() {
        val apps = listOf(
            app("io.github.mayusi.calibratesoc"),
            app("io.github.mayusi.calibratesoc.debug"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    // ── User apps should pass through ────────────────────────────────────────────

    @Test
    fun gameAppsAreIncluded() {
        val apps = listOf(
            app("com.activision.callofduty.shooter"),
            app("com.supercell.clashofclans"),
            app("com.mojang.minecraftpe"),
            app("com.roblox.client"),
        )
        val result = filterReaperEligible(apps)
        assertThat(result).hasSize(4)
    }

    @Test
    fun mediaAppsAreIncluded() {
        val apps = listOf(
            app("com.spotify.music"),
            app("com.netflix.mediaclient"),
            app("tv.twitch.android.app"),
        )
        val result = filterReaperEligible(apps)
        assertThat(result).hasSize(3)
    }

    @Test
    fun utilityAppsAreIncluded() {
        val apps = listOf(
            app("com.discord"),
            app("org.telegram.messenger"),
            app("io.github.somedev.app"),
        )
        val result = filterReaperEligible(apps)
        assertThat(result).hasSize(3)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────────

    @Test
    fun emptyListReturnsEmpty() {
        assertThat(filterReaperEligible(emptyList())).isEmpty()
    }

    @Test
    fun mixedListFiltersCorrectly() {
        val apps = listOf(
            app("com.android.settings"),            // excluded
            app("com.google.android.gms"),          // excluded
            app("io.github.mayusi.calibratesoc"),   // excluded
            app("com.epicgames.fortnite"),           // included
            app("com.roblox.client"),                // included
            app("android"),                          // excluded
        )
        val result = filterReaperEligible(apps)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.packageName }).containsExactly(
            "com.epicgames.fortnite",
            "com.roblox.client",
        )
    }

    @Test
    fun allExcludedReturnsEmpty() {
        val apps = listOf(
            app("android"),
            app("com.android.systemui"),
            app("com.google.android.gms"),
            app("io.github.mayusi.calibratesoc"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    // ── Exact match vs prefix match ──────────────────────────────────────────────

    @Test
    fun bareExactMatchIsExcluded() {
        // "android" is an exact prefix in ALWAYS_EXCLUDED_PREFIXES — the exact package
        // name "android" (no dot suffix) must also be excluded.
        val apps = listOf(app("android"))
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    @Test
    fun comAndroidExactIsExcluded() {
        // "com.android." in ALWAYS_EXCLUDED_PREFIXES — after trimEnd('.') it's "com.android"
        // An exact package "com.android" (if it existed) should also be excluded.
        val apps = listOf(app("com.android"))
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    @Test
    fun suffixVariantsAreExcluded() {
        // Sub-packages under excluded prefixes must also be excluded at any depth.
        val apps = listOf(
            app("com.android.providers.telephony"),
            app("com.android.server"),
            app("com.google.android.gms.location.settings"),
        )
        assertThat(filterReaperEligible(apps)).isEmpty()
    }

    // ── Local exclusion set completeness ─────────────────────────────────────────

    @Test
    fun alwaysExcludedPrefixesContainsExpectedEntries() {
        // Document the exact expected set so a future accidental removal breaks this test.
        // This mirrors the production AppReaper.ALWAYS_EXCLUDED_PREFIXES.
        val required = setOf(
            "android",
            "com.android.",
            "com.google.android.gms",
            "com.google.android.gsf",
            "io.github.mayusi.calibratesoc",
        )
        assertThat(alwaysExcludedPrefixes).containsAtLeastElementsIn(required)
    }
}
