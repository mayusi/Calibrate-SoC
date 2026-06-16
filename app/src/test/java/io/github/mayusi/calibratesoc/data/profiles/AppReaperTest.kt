package io.github.mayusi.calibratesoc.data.profiles

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AppReaperImpl] (Wave 3b Feature 2).
 *
 * Uses a [TestableAppReaper] subclass that overrides the two Android-API-dependent
 * detector methods ([detectLauncherPackages], [detectImePkg]) with fixed values,
 * avoiding the need for Robolectric or a real Android context in pure-JVM tests.
 *
 * Tests cover:
 *   1. [isSafeToReap] excludes system packages via [ALWAYS_EXCLUDED_PREFIXES].
 *   2. [isSafeToReap] excludes the game package itself.
 *   3. [isSafeToReap] excludes launcher packages.
 *   4. [isSafeToReap] excludes the default IME.
 *   5. [isSafeToReap] rejects malformed package names.
 *   6. [isSafeToReap] accepts valid user-installed packages.
 *   7. [reapForGame] builds the correct `am force-stop` command.
 *   8. [reapForGame] is a no-op when [ReaperConfig.enabled] == false.
 *   9. [reapForGame] is a no-op when the denylist is empty.
 *  10. [reapForGame] records reaped/failed packages in session state.
 *  11. [reapForGame] skips system packages without counting them as failed.
 *  12. [ALWAYS_EXCLUDED_PREFIXES] contains all required critical prefixes.
 */
class AppReaperTest {

    @Before
    fun stubLog() {
        // android.util.Log is not available in the pure-JVM unit-test runtime.
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    // ── Test double ───────────────────────────────────────────────────────────

    /**
     * Subclass that overrides the Android-dependent detector methods so the main
     * [AppReaperImpl] logic can be exercised in pure-JVM unit tests.
     */
    private class TestableAppReaper(
        ctx: Context,
        pserver: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter,
        repo: ReaperRepository,
        private val fixedLaunchers: Set<String> = setOf("com.example.launcher"),
        private val fixedIme: String? = "com.gboard.ime",
    ) : AppReaperImpl(ctx, pserver, repo) {
        override fun detectLauncherPackages(): Set<String> = fixedLaunchers
        override fun detectImePkg(): String? = fixedIme
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a [TestableAppReaper] with mocked PServerWriter and ReaperRepository.
     * [capturedCommands] is populated with every shell command sent to PServer.
     */
    private fun buildReaper(
        config: ReaperConfig = ReaperConfig(enabled = true, denylist = setOf("com.example.spotify")),
        capturedCommands: MutableList<String> = mutableListOf(),
        pserverAvailable: Boolean = true,
        launcherPackages: Set<String> = setOf("com.example.launcher"),
        imePackage: String? = "com.gboard.ime",
    ): TestableAppReaper {
        val pserver = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter>()
        coEvery { pserver.executeShell(any()) } answers {
            val cmd = firstArg<String>()
            capturedCommands += cmd
            if (pserverAvailable) (0 to "success") else null
        }

        val repo = mockk<ReaperRepository>()
        coEvery { repo.snapshot() } returns config

        val ctx = mockk<Context>(relaxed = true)

        return TestableAppReaper(ctx, pserver, repo, launcherPackages, imePackage)
    }

    // ── 1-6: isSafeToReap ─────────────────────────────────────────────────────

    @Test
    fun `isSafeToReap excludes android exact and android-dot prefix`() {
        val reaper = buildReaper()
        // Exact match "android"
        assertThat(reaper.isSafeToReap("android", "com.game", emptySet(), null)).isFalse()
        // Sub-package "android.app.usage"
        assertThat(reaper.isSafeToReap("android.app.usage", "com.game", emptySet(), null)).isFalse()
        assertThat(reaper.isSafeToReap("android.os.something", "com.game", emptySet(), null)).isFalse()
    }

    @Test
    fun `isSafeToReap excludes com_android prefix`() {
        val reaper = buildReaper()
        assertThat(reaper.isSafeToReap("com.android.launcher3", "com.game", emptySet(), null)).isFalse()
        assertThat(reaper.isSafeToReap("com.android.systemui", "com.game", emptySet(), null)).isFalse()
        assertThat(reaper.isSafeToReap("com.android.providers.settings", "com.game", emptySet(), null)).isFalse()
    }

    @Test
    fun `isSafeToReap excludes Google Play Services`() {
        val reaper = buildReaper()
        assertThat(reaper.isSafeToReap("com.google.android.gms", "com.game", emptySet(), null)).isFalse()
        assertThat(reaper.isSafeToReap("com.google.android.gsf", "com.game", emptySet(), null)).isFalse()
    }

    @Test
    fun `isSafeToReap excludes Calibrate SoC and its sub-packages`() {
        val reaper = buildReaper()
        assertThat(
            reaper.isSafeToReap("io.github.mayusi.calibratesoc", "com.game", emptySet(), null),
        ).isFalse()
        assertThat(
            reaper.isSafeToReap("io.github.mayusi.calibratesoc.debug", "com.game", emptySet(), null),
        ).isFalse()
    }

    @Test
    fun `isSafeToReap excludes the game package itself`() {
        val reaper = buildReaper()
        assertThat(
            reaper.isSafeToReap("com.example.game", "com.example.game", emptySet(), null),
        ).isFalse()
    }

    @Test
    fun `isSafeToReap excludes launcher packages`() {
        val reaper = buildReaper()
        val launchers = setOf("com.example.launcher", "com.sec.android.app.launcher")
        assertThat(reaper.isSafeToReap("com.example.launcher", "com.game", launchers, null)).isFalse()
        assertThat(reaper.isSafeToReap("com.sec.android.app.launcher", "com.game", launchers, null)).isFalse()
    }

    @Test
    fun `isSafeToReap excludes the default IME`() {
        val reaper = buildReaper()
        assertThat(
            reaper.isSafeToReap(
                "com.google.android.inputmethod.latin",
                "com.game",
                emptySet(),
                "com.google.android.inputmethod.latin",
            )
        ).isFalse()
    }

    @Test
    fun `isSafeToReap rejects malformed package names`() {
        val reaper = buildReaper()
        assertThat(reaper.isSafeToReap("", "com.game", emptySet(), null)).isFalse()
        assertThat(reaper.isSafeToReap("com.example app", "com.game", emptySet(), null)).isFalse()
        assertThat(reaper.isSafeToReap("com.example;evil", "com.game", emptySet(), null)).isFalse()
    }

    @Test
    fun `isSafeToReap accepts valid user-installed packages`() {
        val reaper = buildReaper()
        assertThat(reaper.isSafeToReap("com.spotify.music", "com.example.game", emptySet(), null)).isTrue()
        assertThat(reaper.isSafeToReap("com.discord", "com.example.game", emptySet(), null)).isTrue()
        assertThat(reaper.isSafeToReap("tv.twitch.android.app", "com.example.game", emptySet(), null)).isTrue()
    }

    // ── 7: correct am command format ──────────────────────────────────────────

    @Test
    fun `reapForGame sends correctly formatted am force-stop command`() = runTest {
        val captured = mutableListOf<String>()
        val reaper = buildReaper(
            config = ReaperConfig(enabled = true, denylist = setOf("com.spotify.music")),
            capturedCommands = captured,
            launcherPackages = emptySet(),
            imePackage = null,
        )
        reaper.reapForGame("com.example.game")

        assertThat(captured).isNotEmpty()
        val cmd = captured.first()
        assertThat(cmd).contains("am force-stop")
        assertThat(cmd).contains("com.spotify.music")
        assertThat(cmd).contains("am set-inactive")
        // Must NOT contain the game package itself.
        assertThat(cmd).doesNotContain("com.example.game")
    }

    // ── 8: no-op when disabled ────────────────────────────────────────────────

    @Test
    fun `reapForGame is a no-op when ReaperConfig is disabled`() = runTest {
        val captured = mutableListOf<String>()
        val reaper = buildReaper(
            config = ReaperConfig(enabled = false, denylist = setOf("com.spotify.music")),
            capturedCommands = captured,
        )
        reaper.reapForGame("com.example.game")

        assertThat(captured).isEmpty()
    }

    // ── 9: no-op when denylist empty ──────────────────────────────────────────

    @Test
    fun `reapForGame is a no-op when denylist is empty`() = runTest {
        val captured = mutableListOf<String>()
        val reaper = buildReaper(
            config = ReaperConfig(enabled = true, denylist = emptySet()),
            capturedCommands = captured,
        )
        reaper.reapForGame("com.example.game")

        assertThat(captured).isEmpty()
    }

    // ── 10: session state tracking ────────────────────────────────────────────

    @Test
    fun `reapForGame records reaped packages in session state`() = runTest {
        val reaper = buildReaper(
            config = ReaperConfig(enabled = true, denylist = setOf("com.spotify.music", "com.discord")),
            pserverAvailable = true,
            launcherPackages = emptySet(),
            imePackage = null,
        )
        reaper.reapForGame("com.example.game")

        val state = reaper.lastSessionState()
        assertThat(state.reapedPackages).containsExactly("com.spotify.music", "com.discord")
        assertThat(state.failedPackages).isEmpty()
    }

    @Test
    fun `reapForGame records failed packages when PServer unavailable`() = runTest {
        val reaper = buildReaper(
            config = ReaperConfig(enabled = true, denylist = setOf("com.spotify.music")),
            pserverAvailable = false,
            launcherPackages = emptySet(),
            imePackage = null,
        )
        reaper.reapForGame("com.example.game")

        val state = reaper.lastSessionState()
        assertThat(state.reapedPackages).isEmpty()
        assertThat(state.failedPackages).containsExactly("com.spotify.music")
    }

    // ── 11: system packages silently skipped ──────────────────────────────────

    @Test
    fun `reapForGame skips system packages and does not count them as failed`() = runTest {
        val captured = mutableListOf<String>()
        val reaper = buildReaper(
            config = ReaperConfig(
                enabled = true,
                denylist = setOf(
                    "com.android.settings",    // system — must be excluded (no PServer call)
                    "com.spotify.music",        // user — must be reaped
                ),
            ),
            capturedCommands = captured,
            launcherPackages = emptySet(),
            imePackage = null,
        )
        reaper.reapForGame("com.example.game")

        val state = reaper.lastSessionState()
        assertThat(state.reapedPackages).containsExactly("com.spotify.music")
        assertThat(state.failedPackages).isEmpty()
        // Only one PServer command — the system package must not have been sent.
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).doesNotContain("com.android.settings")
    }

    // ── 12: ALWAYS_EXCLUDED_PREFIXES coverage ─────────────────────────────────

    @Test
    fun `ALWAYS_EXCLUDED_PREFIXES contains all required critical prefixes`() {
        val required = setOf(
            "android",
            "com.android.",
            "com.google.android.gms",
            "com.google.android.gsf",
            "io.github.mayusi.calibratesoc",
        )
        assertThat(ALWAYS_EXCLUDED_PREFIXES).containsAtLeastElementsIn(required)
    }
}
