package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests that LOCK the ROOT-OPTIONAL completion gate —
 * [canCompleteUniversal].
 *
 * THE NORTH STAR — Calibrate SoC is ROOT-OPTIONAL. The app FULLY WORKS on ANY
 * device with NO grant (HUD overlay, FPS, monitoring, benchmarks, profiles,
 * insights all run on the NONE tier). Root / PServer / Shizuku / script only
 * unlock the EXTRA live CPU/GPU tuning — "better with", never "required".
 *
 * Therefore onboarding NEVER HARD-LOCKS ANY DEVICE — including a PServer handheld.
 * The gate is simple: once the probe RESOLVES (any real route), the device can
 * ALWAYS complete; the grant is strongly OFFERED per device but never a wall, and
 * stays re-reachable later from Settings / Tune. The ONLY non-completable state is
 * the momentary Checking (probe in flight) — a transient placeholder, not a lock.
 *
 * The FIX-1 soft-trap is SUBSUMED: a script/Shizuku/root device — like every
 * device — always completes once resolved, so the old permanent no-op "Enter app"
 * is impossible.
 */
class OnboardingUniversalGateTest {

    private fun gate(
        route: UniversalRoute,
        oneTapGrantHeld: Boolean = false,
        bridgeUnavailable: Boolean = false,
        privilegePathGranted: Boolean = false,
        universalPermsSatisfied: Boolean = true,
    ) = canCompleteUniversal(
        route = route,
        oneTapGrantHeld = oneTapGrantHeld,
        bridgeUnavailable = bridgeUnavailable,
        privilegePathGranted = privilegePathGranted,
        universalPermsSatisfied = universalPermsSatisfied,
    )

    /** Every real (resolved) route. Checking is the only non-resolved state. */
    private val resolvedRoutes = listOf(
        UniversalRoute.AutoSetupHero,
        UniversalRoute.RootEnable,
        UniversalRoute.ShizukuSetup,
        UniversalRoute.ScriptLadder,
        UniversalRoute.PlainTerminal,
    )

    // ── Probing → never completes (transient, not a lock) ─────────────────────

    @Test
    fun `checking never completes onboarding (transient placeholder)`() {
        // Even with every favourable signal, a route still resolving to Checking
        // must not complete — it's a momentary "Checking…" placeholder.
        assertThat(
            gate(
                UniversalRoute.Checking,
                oneTapGrantHeld = true,
                privilegePathGranted = true,
                universalPermsSatisfied = true,
            )
        ).isFalse()
    }

    // ── ROOT-OPTIONAL: EVERY resolved device can ALWAYS enter (never walled) ──

    @Test
    fun `every resolved device can complete with NO grant at all (never hard-locked)`() {
        // The headline invariant: no device — PServer handheld included — is ever
        // blocked from entering once the probe resolves, even with zero grant.
        for (route in resolvedRoutes) {
            assertThat(
                gate(
                    route,
                    oneTapGrantHeld = false,
                    bridgeUnavailable = false,
                    privilegePathGranted = false,
                    universalPermsSatisfied = false,
                )
            ).isTrue()
        }
    }

    @Test
    fun `pserver one-tap hero is OFFERED not REQUIRED — can enter without the grant`() {
        // The one-tap is the prominent primary CTA, but skipping it still lets the
        // user into the fully-working app.
        assertThat(gate(UniversalRoute.AutoSetupHero, oneTapGrantHeld = false)).isTrue()
        // And of course completing the one-tap also enters.
        assertThat(gate(UniversalRoute.AutoSetupHero, oneTapGrantHeld = true)).isTrue()
    }

    @Test
    fun `script device can complete whether or not the script landed (FIX 1, root-optional)`() {
        // FIX 1 regression: a script device must be ABLE to complete after the
        // script lands — and, under root-optional, also if it skips. Both enter.
        assertThat(gate(UniversalRoute.ScriptLadder, privilegePathGranted = true)).isTrue()
        assertThat(gate(UniversalRoute.ScriptLadder, privilegePathGranted = false)).isTrue()
    }

    @Test
    fun `shizuku device including GRANTED_NO_WRITES can always enter`() {
        // Granted, or granted-but-kernel-blocks-writes, or skipped — all enter.
        assertThat(gate(UniversalRoute.ShizukuSetup, privilegePathGranted = true)).isTrue()
        assertThat(gate(UniversalRoute.ShizukuSetup, privilegePathGranted = false)).isTrue()
    }

    @Test
    fun `root device whether it enables root mode or declines can always enter`() {
        assertThat(gate(UniversalRoute.RootEnable, privilegePathGranted = true)).isTrue()
        assertThat(gate(UniversalRoute.RootEnable, privilegePathGranted = false)).isTrue()
    }

    @Test
    fun `plain device door is always open`() {
        assertThat(gate(UniversalRoute.PlainTerminal)).isTrue()
    }

    // ── Invariant: Checking is the ONLY state that ever blocks ────────────────

    @Test
    fun `the ONLY non-completable state is Checking — across all signal permutations`() {
        var blockedNonChecking = 0
        for (route in resolvedRoutes) {
            for (oneTap in listOf(true, false)) {
                for (bridge in listOf(true, false)) {
                    for (granted in listOf(true, false)) {
                        for (perms in listOf(true, false)) {
                            val canComplete = gate(route, oneTap, bridge, granted, perms)
                            if (!canComplete) blockedNonChecking++
                        }
                    }
                }
            }
        }
        // No resolved route, under any signal combination, ever blocks.
        assertThat(blockedNonChecking).isEqualTo(0)
        // Checking always blocks (the transient).
        assertThat(gate(UniversalRoute.Checking)).isFalse()
    }
}
