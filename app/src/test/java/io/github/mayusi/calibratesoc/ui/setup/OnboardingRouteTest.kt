package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests that LOCK the first-run onboarding route decision.
 *
 * Regression guard for the Retroid Pocket 6 bug: a PServer-live device
 * (isTransactable() probe → true) was being walked through the OLD per-permission
 * manual ladder instead of landing on the one-click "Set up everything" hero,
 * because nothing consumed the reactive pserverSysfsLive signal to route there.
 *
 * [decideOnboardingRoute] is the single source of truth for that decision; these
 * tests pin every branch so a future edit can't silently re-route a live device
 * down the old ladder, and can't strand a user on a spinner.
 */
class OnboardingRouteTest {

    // ── 1. The bug it fixes: PServer-live → one-click hero, NOT the ladder ────

    @Test
    fun `pserver-live resolved goes straight to the one-click hero`() {
        val route = decideOnboardingRoute(
            capResolved = true,
            pserverSysfsLive = true,
            userTookManualControl = false,
        )
        assertThat(route).isEqualTo(OnboardingRoute.AutoSetupHero)
    }

    @Test
    fun `pserver-live must NEVER select the manual ladder`() {
        val route = decideOnboardingRoute(
            capResolved = true,
            pserverSysfsLive = true,
            userTookManualControl = false,
        )
        assertThat(route).isNotEqualTo(OnboardingRoute.ManualLadder)
    }

    // ── 2. Genuine non-PServer device → the real manual ladder (intact) ──────

    @Test
    fun `non-pserver resolved walks the manual ladder`() {
        val route = decideOnboardingRoute(
            capResolved = true,
            pserverSysfsLive = false,
            userTookManualControl = false,
        )
        assertThat(route).isEqualTo(OnboardingRoute.ManualLadder)
    }

    // ── 3. Probe in flight → Checking transient, NOT the manual ladder ───────

    @Test
    fun `unresolved capability shows the checking transient`() {
        val route = decideOnboardingRoute(
            capResolved = false,
            pserverSysfsLive = false,
            userTookManualControl = false,
        )
        assertThat(route).isEqualTo(OnboardingRoute.Checking)
    }

    @Test
    fun `unresolved capability must NOT commit to the manual ladder (first-frame race)`() {
        val route = decideOnboardingRoute(
            capResolved = false,
            // Stale/garbage value possible before the probe lands — must be ignored.
            pserverSysfsLive = true,
            userTookManualControl = false,
        )
        assertThat(route).isEqualTo(OnboardingRoute.Checking)
        assertThat(route).isNotEqualTo(OnboardingRoute.ManualLadder)
    }

    // ── 4. Stickiness: once the user takes manual control, stay there ────────

    @Test
    fun `manual control is sticky even if a late probe says live`() {
        val route = decideOnboardingRoute(
            capResolved = true,
            pserverSysfsLive = true,
            userTookManualControl = true,
        )
        // A user who deliberately chose the manual path is never yanked onto
        // the auto hero by a late-resolving probe.
        assertThat(route).isEqualTo(OnboardingRoute.ManualLadder)
    }

    @Test
    fun `manual control wins over an unresolved probe`() {
        val route = decideOnboardingRoute(
            capResolved = false,
            pserverSysfsLive = false,
            userTookManualControl = true,
        )
        assertThat(route).isEqualTo(OnboardingRoute.ManualLadder)
    }

    // ── 5. Exhaustive truth table ────────────────────────────────────────────

    private data class RouteCase(
        val capResolved: Boolean,
        val pserverSysfsLive: Boolean,
        val userTookManualControl: Boolean,
        val expected: OnboardingRoute,
        val label: String,
    )

    private val truthTable = listOf(
        // userTookManualControl = false
        RouteCase(false, false, false, OnboardingRoute.Checking,      "unresolved → Checking"),
        RouteCase(false, true,  false, OnboardingRoute.Checking,      "unresolved (stale live) → Checking"),
        RouteCase(true,  true,  false, OnboardingRoute.AutoSetupHero, "live → AutoSetupHero"),
        RouteCase(true,  false, false, OnboardingRoute.ManualLadder,  "resolved non-live → ManualLadder"),
        // userTookManualControl = true → always ManualLadder
        RouteCase(false, false, true,  OnboardingRoute.ManualLadder,  "manual + unresolved → ManualLadder"),
        RouteCase(false, true,  true,  OnboardingRoute.ManualLadder,  "manual + stale live → ManualLadder"),
        RouteCase(true,  true,  true,  OnboardingRoute.ManualLadder,  "manual + live → ManualLadder"),
        RouteCase(true,  false, true,  OnboardingRoute.ManualLadder,  "manual + non-live → ManualLadder"),
    )

    @Test
    fun `route truth table is exhaustive and correct`() {
        for (c in truthTable) {
            val result = decideOnboardingRoute(
                capResolved = c.capResolved,
                pserverSysfsLive = c.pserverSysfsLive,
                userTookManualControl = c.userTookManualControl,
            )
            assertThat(result).isEqualTo(c.expected)
        }
    }
}
