package io.github.mayusi.calibratesoc.data.gameaware

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import org.junit.Test

class GameProfileResolverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val emptyRecords: Map<String, PerGameRecord> = emptyMap()

    private val userRecord = PerGameRecord(
        packageName   = "org.ppsspp.ppsspp",
        profileId     = "user_12345",
        autoTdpProfile = AutoTdpProfile.EFFICIENCY,
        fpsCapHz      = 60,
        learnedGood   = true,
    )

    private val records: Map<String, PerGameRecord> = mapOf(
        "org.ppsspp.ppsspp" to userRecord,
        "com.some.othergame" to PerGameRecord(
            packageName    = "com.some.othergame",
            profileId      = "user_67890",
            autoTdpProfile = AutoTdpProfile.BALANCED,
            fpsCapHz       = null,
            learnedGood    = false,
        ),
    )

    // ── Null for completely unknown packages ──────────────────────────────────

    @Test
    fun `completely unknown package with no user record returns null`() {
        val result = GameProfileResolver.resolve("com.totally.unknown.app", emptyRecords)
        assertThat(result).isNull()
    }

    @Test
    fun `unknown package not in KnownGames and not in user records returns null`() {
        val result = GameProfileResolver.resolve("com.my.homebrewapp", records)
        assertThat(result).isNull()
    }

    // ── User record is returned when present ──────────────────────────────────

    @Test
    fun `known user record is returned with USER_RECORD source`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)
        assertThat(result).isNotNull()
        assertThat(result!!.source).isEqualTo(GamePlanSource.USER_RECORD)
    }

    @Test
    fun `user record profileId is preserved in GamePlan`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)!!
        assertThat(result.profileId).isEqualTo("user_12345")
    }

    @Test
    fun `user record autoTdpProfile is preserved in GamePlan`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `user record fpsCapHz is preserved in GamePlan`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)!!
        assertThat(result.fpsCapHz).isEqualTo(60)
    }

    @Test
    fun `user record with learnedGood true sets isLearnedGood on GamePlan`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)!!
        assertThat(result.isLearnedGood).isTrue()
    }

    @Test
    fun `user record with learnedGood false sets isLearnedGood false on GamePlan`() {
        val result = GameProfileResolver.resolve("com.some.othergame", records)!!
        assertThat(result.isLearnedGood).isFalse()
    }

    // ── User record beats KnownGames hint ────────────────────────────────────

    @Test
    fun `user record beats KnownGames default hint for same package`() {
        // PPSSPP is in KnownGames with EFFICIENCY + no profileId.
        // Our user record has a specific profileId and fpsCapHz=60.
        // The resolver must prefer the user record.
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)!!
        assertThat(result.source).isEqualTo(GamePlanSource.USER_RECORD)
        assertThat(result.profileId).isEqualTo("user_12345")
        assertThat(result.fpsCapHz).isEqualTo(60)
    }

    // ── KnownGames hint returned for recognised packages not in user records ──

    @Test
    fun `PPSSPP without user record returns KNOWN_GAME_HINT`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", emptyRecords)
        assertThat(result).isNotNull()
        assertThat(result!!.source).isEqualTo(GamePlanSource.KNOWN_GAME_HINT)
    }

    @Test
    fun `PPSSPP hint has EFFICIENCY autoTdpProfile`() {
        val result = GameProfileResolver.resolve("org.ppsspp.ppsspp", emptyRecords)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `Dolphin hint has BALANCED autoTdpProfile`() {
        val result = GameProfileResolver.resolve("org.dolphinemu.dolphinemu", emptyRecords)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `KnownGames hint has isLearnedGood false`() {
        val result = GameProfileResolver.resolve("org.dolphinemu.dolphinemu", emptyRecords)!!
        assertThat(result.isLearnedGood).isFalse()
    }

    @Test
    fun `KnownGames hint has null profileId`() {
        // We can't know which UserProfile the user has saved.
        val result = GameProfileResolver.resolve("org.dolphinemu.dolphinemu", emptyRecords)!!
        assertThat(result.profileId).isNull()
    }

    @Test
    fun `KnownGames hint has null fpsCapHz`() {
        val result = GameProfileResolver.resolve("org.dolphinemu.dolphinemu", emptyRecords)!!
        assertThat(result.fpsCapHz).isNull()
    }

    // ── packageName is preserved in returned GamePlan ─────────────────────────

    @Test
    fun `packageName in GamePlan matches the resolved package`() {
        val pkg = "com.github.stenzek.duckstation"
        val result = GameProfileResolver.resolve(pkg, emptyRecords)!!
        assertThat(result.packageName).isEqualTo(pkg)
    }

    // ── List overload ─────────────────────────────────────────────────────────

    @Test
    fun `list overload returns same result as map overload`() {
        val mapResult  = GameProfileResolver.resolve("org.ppsspp.ppsspp", records)
        val listResult = GameProfileResolver.resolve("org.ppsspp.ppsspp", records.values.toList())
        assertThat(listResult?.source).isEqualTo(mapResult?.source)
        assertThat(listResult?.profileId).isEqualTo(mapResult?.profileId)
    }

    // ── Empty records with several known games ────────────────────────────────

    @Test
    fun `AetherSX2 returns BALANCED hint`() {
        val result = GameProfileResolver.resolve("xyz.aethersx2.android", emptyRecords)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
        assertThat(result.source).isEqualTo(GamePlanSource.KNOWN_GAME_HINT)
    }

    @Test
    fun `melonDS returns EFFICIENCY hint`() {
        val result = GameProfileResolver.resolve("me.magnum.melonds", emptyRecords)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `Winlator returns BALANCED hint`() {
        val result = GameProfileResolver.resolve("com.winlator", emptyRecords)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `GameNative returns BALANCED hint`() {
        val result = GameProfileResolver.resolve("com.gamenative", emptyRecords)!!
        assertThat(result.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }
}
