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

    // ── (a') GENERIC controller heuristic — signal (a) is now list-UNKNOWN capable ────

    @Test
    fun `(a-prime) generic controller alone suspends with EXTERNAL_CONTROLLER`() {
        val v = guard.decide(
            foregroundPackage = "com.unknown.tuner",   // NOT in KnownControllers
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = true,                   // caller's WSS heuristic fired
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.EXTERNAL_CONTROLLER)
    }

    @Test
    fun `generic controller defaults to false and does not suspend an ordinary app`() {
        // The param defaults to false — a plain foreground app with no other signal runs.
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
    fun `known controller fast-path still fires independently of the generic heuristic`() {
        // A confirmed KnownControllers hit with genericController=false (no WSS/Binder needed)
        // still suspends via the fast path — the generic heuristic is purely additive.
        val v = guard.decide(
            foregroundPackage = "com.gamenative",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = false,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.KNOWN_CONTROLLER)
    }

    @Test
    fun `known controller label wins over a co-firing generic heuristic`() {
        // Both signal (a) forms true: the named fast path takes the label (higher priority).
        val v = guard.decide(
            foregroundPackage = "app.gamenative.iic",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = true,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.KNOWN_CONTROLLER)
    }

    @Test
    fun `manual pause wins the label over a generic controller`() {
        val v = guard.decide(
            foregroundPackage = "com.unknown.tuner",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = true,
            genericController = true,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.MANUAL_PAUSE)
    }

    @Test
    fun `external controller label wins over sysfs contention and untuned game`() {
        // Priority: EXTERNAL_CONTROLLER sits above (b) and (d).
        val v = guard.decide(
            foregroundPackage = "com.unknown.tuner",
            hasUserBundleForPkg = false,
            isGameForPkg = true,             // also a game with no bundle → (d) would fire
            externalDriveActive = true,      // (b) also active
            manuallyPaused = false,
            genericController = true,
        )
        assertThat(v.suspend).isTrue()
        assertThat(v.reason).isEqualTo(InterferenceGuard.SuspendReason.EXTERNAL_CONTROLLER)
    }

    // ── The caller AND-guards can't over-fire: a game / user-bundled / our-own pkg holding
    //    WSS resolves genericController=false BEFORE it reaches decide(). We model that here
    //    (genericController=false for each excluded case) and assert no EXTERNAL_CONTROLLER.

    @Test
    fun `a game holding WSS is NOT flagged as external controller (excluded by !isGame)`() {
        // Caller: !isGame is false → genericController resolves false. A game with a bundle
        // (the user wants it tuned) therefore runs; a game WITHOUT one is signal (d)'s job.
        val vTuned = guard.decide(
            foregroundPackage = "com.some.game",
            hasUserBundleForPkg = true,      // user wants it tuned
            isGameForPkg = true,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = false,       // excluded by !isGame at the call site
        )
        assertThat(vTuned.suspend).isFalse()
        assertThat(vTuned.reason).isNull()

        val vUntuned = guard.decide(
            foregroundPackage = "com.some.game",
            hasUserBundleForPkg = false,
            isGameForPkg = true,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = false,       // excluded by !isGame — (d) owns games, not (a')
        )
        assertThat(vUntuned.suspend).isTrue()
        // Game → labelled UNTUNED_GAME, NOT EXTERNAL_CONTROLLER.
        assertThat(vUntuned.reason).isEqualTo(InterferenceGuard.SuspendReason.UNTUNED_GAME)
    }

    @Test
    fun `a user-bundled app holding WSS is NOT flagged (excluded by !hasUserBundle)`() {
        // Caller: hasUserBundle true → genericController false. The user tunes it with us.
        val v = guard.decide(
            foregroundPackage = "com.user.tunedapp",
            hasUserBundleForPkg = true,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = false,       // excluded by !hasUserBundle at the call site
        )
        assertThat(v.suspend).isFalse()
        assertThat(v.reason).isNull()
    }

    @Test
    fun `our own package holding WSS is NEVER flagged (excluded by fgPkg != our package)`() {
        // Caller: fgPkg == packageName → resolveForegroundPackage() already drops us, and
        // genericController explicitly excludes our own package → false.
        val v = guard.decide(
            foregroundPackage = "io.github.mayusi.calibratesoc",
            hasUserBundleForPkg = false,
            isGameForPkg = false,
            externalDriveActive = false,
            manuallyPaused = false,
            genericController = false,       // excluded by fgPkg != packageName at the call site
        )
        assertThat(v.suspend).isFalse()
        assertThat(v.reason).isNull()
    }
}
