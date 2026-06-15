package io.github.mayusi.calibratesoc.ui.gameaware

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.gameaware.GamePlanSource
import io.github.mayusi.calibratesoc.data.gameaware.PerGameRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GameEntry] pure logic and ordering.
 *
 * These are pure-JVM tests — no Android context required. The sorting
 * and effective-AutoTDP logic are tested without any ViewModel lifecycle.
 */
class GameEntryOrderingTest {

    private fun makeEntry(
        pkg: String,
        label: String = pkg.substringAfterLast("."),
        knownHint: AutoTdpProfile? = null,
        record: PerGameRecord? = null,
        isKnown: Boolean = true,
    ) = GameEntry(
        packageName = pkg,
        appLabel = label,
        knownHintAutoTdp = knownHint,
        userRecord = record,
        isKnown = isKnown,
    )

    // ── effectiveAutoTdp ──────────────────────────────────────────────────────

    @Test
    fun `effectiveAutoTdp returns user record override when present`() {
        val entry = makeEntry(
            pkg = "org.ppsspp.ppsspp",
            knownHint = AutoTdpProfile.EFFICIENCY,
            record = PerGameRecord(
                packageName = "org.ppsspp.ppsspp",
                profileId = null,
                autoTdpProfile = AutoTdpProfile.BALANCED,
                fpsCapHz = null,
            ),
        )
        assertEquals(AutoTdpProfile.BALANCED, entry.effectiveAutoTdp)
    }

    @Test
    fun `effectiveAutoTdp falls back to hint when user record has null autoTdp`() {
        val entry = makeEntry(
            pkg = "org.ppsspp.ppsspp",
            knownHint = AutoTdpProfile.EFFICIENCY,
            record = PerGameRecord(
                packageName = "org.ppsspp.ppsspp",
                profileId = "my_profile",
                autoTdpProfile = null,
                fpsCapHz = null,
            ),
        )
        assertEquals(AutoTdpProfile.EFFICIENCY, entry.effectiveAutoTdp)
    }

    @Test
    fun `effectiveAutoTdp is null when no hint and no user record`() {
        val entry = makeEntry(
            pkg = "com.unknown.app",
            knownHint = null,
            record = null,
            isKnown = false,
        )
        assertNull(entry.effectiveAutoTdp)
    }

    // ── isLearnedGood ─────────────────────────────────────────────────────────

    @Test
    fun `isLearnedGood is true only when user record marks it`() {
        val notGood = makeEntry(
            pkg = "a.b.c",
            record = PerGameRecord("a.b.c", null, null, null, learnedGood = false),
        )
        assertFalse(notGood.isLearnedGood)

        val good = makeEntry(
            pkg = "a.b.c",
            record = PerGameRecord("a.b.c", null, null, null, learnedGood = true),
        )
        assertTrue(good.isLearnedGood)
    }

    @Test
    fun `isLearnedGood is false when no user record`() {
        val entry = makeEntry(pkg = "x.y.z", record = null)
        assertFalse(entry.isLearnedGood)
    }

    // ── source ────────────────────────────────────────────────────────────────

    @Test
    fun `source is USER_RECORD when userRecord is non-null`() {
        val entry = makeEntry(
            pkg = "a.b",
            record = PerGameRecord("a.b", null, null, null),
        )
        assertEquals(GamePlanSource.USER_RECORD, entry.source)
    }

    @Test
    fun `source is KNOWN_GAME_HINT when no userRecord`() {
        val entry = makeEntry(pkg = "a.b", record = null)
        assertEquals(GamePlanSource.KNOWN_GAME_HINT, entry.source)
    }

    // ── Sort order: user-configured first, then known hints ──────────────────

    @Test
    fun `entries sort user-configured before hint-only`() {
        val hintOnly = makeEntry(pkg = "org.ppsspp.ppsspp", knownHint = AutoTdpProfile.EFFICIENCY)
        val userConfigured = makeEntry(
            pkg = "com.retroarch",
            knownHint = AutoTdpProfile.BALANCED,
            record = PerGameRecord("com.retroarch", null, AutoTdpProfile.BALANCED, null),
        )
        val sorted = listOf(hintOnly, userConfigured)
            .sortedWith(
                compareByDescending<GameEntry> { it.userRecord != null }
                    .thenByDescending { it.isKnown }
                    .thenBy { it.appLabel.lowercase() },
            )
        assertEquals("User-configured should be first", userConfigured.packageName, sorted[0].packageName)
        assertEquals("Hint-only should be second", hintOnly.packageName, sorted[1].packageName)
    }

    @Test
    fun `entries of same user-config status sort alphabetically by label`() {
        val alpha = makeEntry(pkg = "a.alpha", label = "Alpha", record = PerGameRecord("a.alpha", null, null, null))
        val zeta = makeEntry(pkg = "z.zeta", label = "Zeta", record = PerGameRecord("z.zeta", null, null, null))
        val sorted = listOf(zeta, alpha)
            .sortedWith(
                compareByDescending<GameEntry> { it.userRecord != null }
                    .thenByDescending { it.isKnown }
                    .thenBy { it.appLabel.lowercase() },
            )
        assertEquals("Alpha should come before Zeta", "Alpha", sorted[0].appLabel)
        assertEquals("Zeta should come second", "Zeta", sorted[1].appLabel)
    }

    // ── KNOWN_PACKAGES list sanity check ─────────────────────────────────────

    @Test
    fun `KNOWN_PACKAGES contains at least 10 entries`() {
        assertTrue(
            "KNOWN_PACKAGES should have at least 10 entries",
            GameAwareViewModel.KNOWN_PACKAGES.size >= 10,
        )
    }

    @Test
    fun `KNOWN_PACKAGES contains PPSSPP`() {
        assertTrue(
            "KNOWN_PACKAGES should contain PPSSPP",
            "org.ppsspp.ppsspp" in GameAwareViewModel.KNOWN_PACKAGES,
        )
    }

    @Test
    fun `KNOWN_PACKAGES contains Dolphin`() {
        assertTrue(
            "KNOWN_PACKAGES should contain Dolphin",
            "org.dolphinemu.dolphinemu" in GameAwareViewModel.KNOWN_PACKAGES,
        )
    }
}
