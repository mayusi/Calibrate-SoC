package io.github.mayusi.calibratesoc.data.profiles

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.gameaware.GamePlan
import io.github.mayusi.calibratesoc.data.gameaware.GamePlanSource
import io.github.mayusi.calibratesoc.data.gameaware.KnownGames
import org.junit.Test

/**
 * Unit tests for [AutoConfigBundleMapper] — the pure tier→bundle policy behind
 * the zero-tap "auto-configure known games" feature.
 *
 * The contract under test (this is LAW — the bundle auto-applies WITHOUT an
 * explicit per-game tap):
 *  - The mapped goal matches the canonical legacy→goal table exactly.
 *  - The mapping is CONSERVATIVE: never GameBoost, never a profile, never a
 *    refresh-rate or fan override.
 *  - Every auto-created bundle is flagged [PerAppBundle.autoCreated] == true.
 *  - Real [KnownGames] hits (heavy-3D and handheld-2D) map sanely.
 */
class AutoConfigBundleMapperTest {

    private fun hint(profile: AutoTdpProfile?): GamePlan = GamePlan(
        packageName = "com.example.game",
        profileId = null,
        autoTdpProfile = profile,
        fpsCapHz = null,
        isLearnedGood = false,
        source = GamePlanSource.KNOWN_GAME_HINT,
    )

    // ── Tier → goal mapping matches the canonical legacy table ─────────────────

    @Test
    fun `BALANCED advisory maps to BALANCED_SMART goal`() {
        val bundle = AutoConfigBundleMapper.bundleFor(hint(AutoTdpProfile.BALANCED))
        assertThat(bundle).isNotNull()
        assertThat(bundle!!.autoTdpGoal).isEqualTo(GoalProfile.BALANCED_SMART)
    }

    @Test
    fun `EFFICIENCY advisory maps to COOL_QUIET goal`() {
        val bundle = AutoConfigBundleMapper.bundleFor(hint(AutoTdpProfile.EFFICIENCY))
        assertThat(bundle!!.autoTdpGoal).isEqualTo(GoalProfile.COOL_QUIET)
    }

    @Test
    fun `BATTERY_TARGET advisory maps to BATTERY_SAVER goal`() {
        val bundle = AutoConfigBundleMapper.bundleFor(hint(AutoTdpProfile.BATTERY_TARGET))
        assertThat(bundle!!.autoTdpGoal).isEqualTo(GoalProfile.BATTERY_SAVER)
    }

    @Test
    fun `mapped goal always equals the canonical legacy-to-goal table`() {
        for (profile in AutoTdpProfile.entries) {
            val bundle = AutoConfigBundleMapper.bundleFor(hint(profile))!!
            assertThat(bundle.autoTdpGoal).isEqualTo(GoalProfile.fromLegacyProfile(profile))
        }
    }

    // ── Conservative defaults (the safety core) ────────────────────────────────

    @Test
    fun `auto-created bundle is conservative - no boost, no profile, no display or fan override`() {
        for (profile in AutoTdpProfile.entries) {
            val bundle = AutoConfigBundleMapper.bundleFor(hint(profile))!!
            // NEVER brute-pin the device on an unattended auto-apply.
            assertThat(bundle.gameBoostOnLaunch).isFalse()
            // NEVER guess a user profile (it can carry aggressive caps).
            assertThat(bundle.profileId).isNull()
            // NEVER guess display / fan — leave the system + goal governor to it.
            assertThat(bundle.refreshRateHz).isNull()
            assertThat(bundle.fanMode).isNull()
        }
    }

    @Test
    fun `auto-created bundle is flagged autoCreated for provenance and safe undo`() {
        for (profile in AutoTdpProfile.entries) {
            val bundle = AutoConfigBundleMapper.bundleFor(hint(profile))!!
            assertThat(bundle.autoCreated).isTrue()
        }
    }

    @Test
    fun `auto-created goal never enables a hard power ceiling unless the advisory asked for it`() {
        // BALANCED / EFFICIENCY advisories (everything KnownGames produces today)
        // must NOT map to a goal that imposes a hard watts ceiling — that would be
        // an uninvited aggressive power cut on an unattended auto-apply.
        assertThat(AutoConfigBundleMapper.bundleFor(hint(AutoTdpProfile.BALANCED))!!
            .autoTdpGoal!!.hasHardPowerCeiling).isFalse()
        assertThat(AutoConfigBundleMapper.bundleFor(hint(AutoTdpProfile.EFFICIENCY))!!
            .autoTdpGoal!!.hasHardPowerCeiling).isFalse()
    }

    // ── Defensive null-guard ───────────────────────────────────────────────────

    @Test
    fun `hint with null advisory yields null bundle (no empty no-op bundle)`() {
        assertThat(AutoConfigBundleMapper.bundleFor(hint(null))).isNull()
    }

    // ── Real KnownGames hits map sanely ────────────────────────────────────────

    @Test
    fun `heavy 3D known game (AetherSX2) auto-configures to BALANCED_SMART`() {
        val plan = KnownGames.defaultHintFor("xyz.aethersx2.android")
        assertThat(plan).isNotNull()
        val bundle = AutoConfigBundleMapper.bundleFor(plan!!)!!
        assertThat(bundle.autoTdpGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(bundle.gameBoostOnLaunch).isFalse()
        assertThat(bundle.autoCreated).isTrue()
    }

    @Test
    fun `handheld 2D known game (PPSSPP) auto-configures to COOL_QUIET`() {
        val plan = KnownGames.defaultHintFor("org.ppsspp.ppsspp")
        assertThat(plan).isNotNull()
        val bundle = AutoConfigBundleMapper.bundleFor(plan!!)!!
        assertThat(bundle.autoTdpGoal).isEqualTo(GoalProfile.COOL_QUIET)
        assertThat(bundle.autoCreated).isTrue()
    }

    @Test
    fun `unknown package has no hint so nothing to auto-configure`() {
        // The watcher checks this BEFORE calling the mapper, but assert the
        // contract: unknown packages produce no hint at all.
        assertThat(KnownGames.defaultHintFor("com.totally.unknown.app")).isNull()
    }
}
