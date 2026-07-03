package io.github.mayusi.calibratesoc.data.gameaware

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [KnownControllers] — the pure "is this foreground package another performance
 * app CalibrateSoC must defer to?" classifier (non-interference back-off, signal (a)).
 *
 * No Android runtime needed (matches [KnownGamesTest]).
 */
class KnownControllersTest {

    // ── Known controllers → true ──────────────────────────────────────────────

    @Test
    fun `GameNative exact package is a controller`() {
        assertThat(KnownControllers.isController("com.gamenative")).isTrue()
    }

    @Test
    fun `Nova GameNative wrapper prefix is a controller`() {
        assertThat(KnownControllers.isController("app.gamenative")).isTrue()
    }

    @Test
    fun `Nova GameNative wrapper variant (app_gamenative_iic) is a controller`() {
        assertThat(KnownControllers.isController("app.gamenative.iic")).isTrue()
    }

    @Test
    fun `Winlator exact package is a controller`() {
        assertThat(KnownControllers.isController("com.winlator")).isTrue()
    }

    @Test
    fun `Winlator fork variant (com_winlator_cmod) is a controller`() {
        assertThat(KnownControllers.isController("com.winlator.cmod")).isTrue()
    }

    @Test
    fun `an extensible tuner example (Franco Kernel) is a controller`() {
        assertThat(KnownControllers.isController("com.franco.kernel")).isTrue()
    }

    // ── Everything else → false ───────────────────────────────────────────────

    @Test
    fun `a random game is not a controller`() {
        assertThat(KnownControllers.isController("com.mojang.minecraftpe")).isFalse()
    }

    @Test
    fun `an emulator is not a controller`() {
        // Emulators are tunable workloads, not performance CONTROLLERS.
        assertThat(KnownControllers.isController("org.dolphinemu.dolphinemu")).isFalse()
    }

    @Test
    fun `a system app is not a controller`() {
        assertThat(KnownControllers.isController("com.android.settings")).isFalse()
    }

    @Test
    fun `null package is not a controller`() {
        assertThat(KnownControllers.isController(null)).isFalse()
    }

    @Test
    fun `blank package is not a controller`() {
        assertThat(KnownControllers.isController("")).isFalse()
        assertThat(KnownControllers.isController("   ")).isFalse()
    }

    @Test
    fun `a package that merely CONTAINS a controller name (not a prefix) is not a controller`() {
        // Prefix match must anchor at the START — a lookalike package is not flagged.
        assertThat(KnownControllers.isController("io.example.com.winlator")).isFalse()
    }
}
