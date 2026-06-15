package io.github.mayusi.calibratesoc.data.gameaware

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import org.junit.Test

class KnownGamesTest {

    // ── Unknown packages return null ──────────────────────────────────────────

    @Test
    fun `totally unknown package returns null`() {
        assertThat(KnownGames.defaultHintFor("com.totally.unknown")).isNull()
    }

    @Test
    fun `system app package returns null`() {
        assertThat(KnownGames.defaultHintFor("com.android.settings")).isNull()
    }

    @Test
    fun `browser package returns null`() {
        assertThat(KnownGames.defaultHintFor("com.android.chrome")).isNull()
    }

    // ── Heavy 3D emulators → BALANCED ────────────────────────────────────────

    @Test
    fun `Dolphin (official) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("org.dolphinemu.dolphinemu")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `AetherSX2 (PS2) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("xyz.aethersx2.android")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `NetherSX2 returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("xyz.nethersx2.android")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `Yuzu returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("org.yuzu.yuzu_emu")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `Eden (Yuzu successor) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("dev.eden.eden_emu")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `Cemu (Wii U) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("info.cemu.Cemu")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `aps3e (PS3) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("xyz.aps3e.android")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `Flycast (Dreamcast) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("com.flycast.emulator")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    // ── 2D / handheld emulators → EFFICIENCY ─────────────────────────────────

    @Test
    fun `PPSSPP (PSP) returns EFFICIENCY`() {
        val hint = KnownGames.defaultHintFor("org.ppsspp.ppsspp")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `PPSSPP Gold returns EFFICIENCY`() {
        val hint = KnownGames.defaultHintFor("org.ppsspp.ppssppgold")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `Citra (3DS) returns EFFICIENCY`() {
        val hint = KnownGames.defaultHintFor("org.citra.citra_emu")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `Azahar (Citra fork) returns EFFICIENCY`() {
        val hint = KnownGames.defaultHintFor("com.azahar.emulator")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `melonDS (DS) returns EFFICIENCY`() {
        val hint = KnownGames.defaultHintFor("me.magnum.melonds")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `DuckStation (PS1) returns EFFICIENCY`() {
        val hint = KnownGames.defaultHintFor("com.github.stenzek.duckstation")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    // ── Launchers / translation layers → BALANCED ────────────────────────────

    @Test
    fun `RetroArch returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("com.retroarch")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `Winlator returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("com.winlator")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `GameNative returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("com.gamenative")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    // ── Returned GamePlan contract ────────────────────────────────────────────

    @Test
    fun `all hints have null profileId (we cannot guess user profile)`() {
        val packages = listOf(
            "org.ppsspp.ppsspp",
            "org.dolphinemu.dolphinemu",
            "xyz.aethersx2.android",
            "me.magnum.melonds",
            "com.retroarch",
        )
        for (pkg in packages) {
            val hint = KnownGames.defaultHintFor(pkg)
            assertThat(hint).isNotNull()
            assertThat(hint!!.profileId).isNull()
        }
    }

    @Test
    fun `all hints have null fpsCapHz (display-rate-dependent)`() {
        val packages = listOf(
            "org.ppsspp.ppsspp",
            "org.dolphinemu.dolphinemu",
            "com.github.stenzek.duckstation",
        )
        for (pkg in packages) {
            val hint = KnownGames.defaultHintFor(pkg)
            assertThat(hint).isNotNull()
            assertThat(hint!!.fpsCapHz).isNull()
        }
    }

    @Test
    fun `all hints have KNOWN_GAME_HINT source`() {
        val packages = listOf(
            "org.ppsspp.ppsspp",
            "org.dolphinemu.dolphinemu",
            "me.magnum.melonds",
        )
        for (pkg in packages) {
            val hint = KnownGames.defaultHintFor(pkg)
            assertThat(hint).isNotNull()
            assertThat(hint!!.source).isEqualTo(GamePlanSource.KNOWN_GAME_HINT)
        }
    }

    @Test
    fun `all hints have isLearnedGood false`() {
        val packages = listOf(
            "org.ppsspp.ppsspp",
            "org.dolphinemu.dolphinemu",
            "com.winlator",
        )
        for (pkg in packages) {
            val hint = KnownGames.defaultHintFor(pkg)
            assertThat(hint).isNotNull()
            assertThat(hint!!.isLearnedGood).isFalse()
        }
    }

    // ── Prefix matching ───────────────────────────────────────────────────────

    @Test
    fun `Dolphin variant not in exact table matches via prefix`() {
        // "org.dolphinemu.dolphinemu_beta" isn't in the exact table
        // but "org.dolphinemu" prefix should match.
        val hint = KnownGames.defaultHintFor("org.dolphinemu.dolphinemu_beta")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `PPSSPP variant not in exact table matches via prefix`() {
        val hint = KnownGames.defaultHintFor("org.ppsspp.ppsspp_customfork")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    // ── Sudachi ───────────────────────────────────────────────────────────────

    @Test
    fun `Sudachi (Switch) returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("com.sudachi.sudachi_emu")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `Sudachi Preview returns BALANCED`() {
        val hint = KnownGames.defaultHintFor("com.sudachi.sudachi_emu.preview")
        assertThat(hint).isNotNull()
        assertThat(hint!!.autoTdpProfile).isEqualTo(AutoTdpProfile.BALANCED)
    }
}
