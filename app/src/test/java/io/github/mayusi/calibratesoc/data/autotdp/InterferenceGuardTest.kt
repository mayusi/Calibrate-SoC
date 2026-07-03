package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [InterferenceGuard] — the pure OR-decision core of the non-interference
 * back-off system. Each of the four signals suspends on its own; all-false runs; and the
 * (d) "game but the user has a bundle" case is NOT flagged. No Android runtime needed.
 */
class InterferenceGuardTest {

    private val guard = InterferenceGuard()

    /** All inputs benign — the baseline "no back-off" call. */
    private fun runningInputs() = guard.decide(
        foregroundPackage = "com.some.game",
        hasUserBundleForPkg = true,   // user configured it → not an untuned game
        isGameForPkg = true,
        externalDriveActive = false,
        manuallyPaused = false,
    )

    // ── All clear → RUNNING ───────────────────────────────────────────────────

    @Test
    fun `all signals false does NOT suspend`() {
        val v = guard.decide(
            foregroundPackage = "com.example.launcher",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
        )
        assertThat(v.suspend).isFalse()
        assertThat(v.reason).isNull()
    }

    @Test
    fun `a game the user has a bundle for does NOT suspend (explicitly wants it tuned)`() {
        val v = runningInputs()
        assertThat(v.suspend).isFalse()
        assertThat(v.reason).isNull()
    }

    // ── Each signal alone → suspend, with the right reason ────────────────────

    @Test
    fun `(a) known controller foreground suspends`() {
        val v = guard.decide(
            foregroundPackage = "app.gamenative.iic",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.KNOWN_CONTROLLER)
    }

    @Test
    fun `(b) sysfs contention alone suspends`() {
        val v = guard.decide(
            foregroundPackage = "com.example.launcher",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = true,
            manuallyPaused = false,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.SYSFS_CONTENTION)
    }

    @Test
    fun `(c) manual pause alone suspends`() {
        val v = guard.decide(
            foregroundPackage = "com.example.launcher",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = true,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.MANUAL_PAUSE)
    }

    @Test
    fun `(d) untuned foreground game alone suspends`() {
        val v = guard.decide(
            foregroundPackage = "com.mojang.minecraftpe",
            hasUserBundleForPkg = false,   // no user bundle → untuned
            isGameForPkg = true,
            externalDriveActive = false,
            manuallyPaused = false,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.UNTUNED_GAME)
    }

    @Test
    fun `(d) a game WITH a user bundle is not flagged even with no other signal`() {
        val v = guard.decide(
            foregroundPackage = "com.mojang.minecraftpe",
            hasUserBundleForPkg = true,   // user wants it tuned
            isGameForPkg = true,
            externalDriveActive = false,
            manuallyPaused = false,
        )
        assertThat(v.suspend).isFalse()
        assertThat(v.reason).isNull()
    }

    // ── Priority order of the reason label (presentation only) ────────────────

    @Test
    fun `manual pause wins the reason label over a known controller`() {
        val v = guard.decide(
            foregroundPackage = "com.gamenative",
            hasUserBundleForPkg = false,
            isGameForPkg = true,
            externalDriveActive = true,
            manuallyPaused = true,   // highest priority
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.MANUAL_PAUSE)
    }

    @Test
    fun `known controller wins the reason over sysfs contention and untuned game`() {
        val v = guard.decide(
            foregroundPackage = "com.winlator",
            hasUserBundleForPkg = false,
            isGameForPkg = true,
            externalDriveActive = true,
            manuallyPaused = false,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.KNOWN_CONTROLLER)
    }

    @Test
    fun `every suspend reason carries a non-blank HUD phrase`() {
        InterferenceGuard.SuspendReason.entries.forEach { r ->
            assertThat(r.hudPhrase).isNotEmpty()
        }
    }
}
