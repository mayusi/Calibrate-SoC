package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests that LOCK the UNIVERSAL first-run routing decision —
 * [decideUniversalRoute]. This is the regression guard for the "PServer-or-nothing"
 * onboarding bug: every NON-PServer device (rooted phone, Shizuku tablet, vendor-
 * script handheld, plain phone) used to fall into the same ManualLadder, so none
 * was routed to ITS correct grant path. These tests pin that each device-type now
 * lands on its real path, and that the plain device is never trapped.
 */
class OnboardingUniversalRouteTest {

    private fun signals(
        capResolved: Boolean = true,
        userTookManualControl: Boolean = false,
        pserverSysfsLive: Boolean = false,
        ayaneoBinderLive: Boolean = false,
        rootAvailable: Boolean = false,
        rootEnabled: Boolean = false,
        shizukuInstalled: Boolean = false,
        advancedApplicable: Boolean = false,
    ) = UniversalSignals(
        capResolved = capResolved,
        userTookManualControl = userTookManualControl,
        pserverSysfsLive = pserverSysfsLive,
        ayaneoBinderLive = ayaneoBinderLive,
        rootAvailable = rootAvailable,
        rootEnabled = rootEnabled,
        shizukuInstalled = shizukuInstalled,
        advancedApplicable = advancedApplicable,
    )

    // ── Probe in flight → Checking (transient, never a trap) ──────────────────

    @Test
    fun `unresolved capability shows the checking transient`() {
        // Even with otherwise-favourable stale signals, an unresolved probe must
        // never commit to a real route.
        assertThat(
            decideUniversalRoute(signals(capResolved = false, pserverSysfsLive = true))
        ).isEqualTo(UniversalRoute.Checking)
    }

    // ── PServer / AYANEO / enabled-root → the frictionless one-tap hero ───────

    @Test
    fun `pserver-live routes to the one-click hero`() {
        assertThat(decideUniversalRoute(signals(pserverSysfsLive = true)))
            .isEqualTo(UniversalRoute.AutoSetupHero)
    }

    @Test
    fun `ayaneo-binder-live routes to the one-click hero`() {
        assertThat(decideUniversalRoute(signals(ayaneoBinderLive = true)))
            .isEqualTo(UniversalRoute.AutoSetupHero)
    }

    @Test
    fun `enabled root routes to the one-click hero (live one-tap path)`() {
        assertThat(decideUniversalRoute(signals(rootAvailable = true, rootEnabled = true)))
            .isEqualTo(UniversalRoute.AutoSetupHero)
    }

    // ── Root available but NOT enabled → the ROOT opt-in route ────────────────

    @Test
    fun `root-available-not-enabled routes to RootEnable`() {
        assertThat(decideUniversalRoute(signals(rootAvailable = true, rootEnabled = false)))
            .isEqualTo(UniversalRoute.RootEnable)
    }

    @Test
    fun `root opt-in is offered even when shizuku is also installed (root is stronger)`() {
        // A rooted device that also has Shizuku is offered ROOT first — the
        // strongest tier it can reach.
        assertThat(
            decideUniversalRoute(signals(rootAvailable = true, shizukuInstalled = true))
        ).isEqualTo(UniversalRoute.RootEnable)
    }

    // ── Shizuku installed (no PServer / no root) → the SHIZUKU route ───────────

    @Test
    fun `shizuku-installed routes to ShizukuSetup`() {
        assertThat(decideUniversalRoute(signals(shizukuInstalled = true)))
            .isEqualTo(UniversalRoute.ShizukuSetup)
    }

    @Test
    fun `shizuku is preferred over a vendor script (universal beats vendor-specific)`() {
        assertThat(
            decideUniversalRoute(signals(shizukuInstalled = true, advancedApplicable = true))
        ).isEqualTo(UniversalRoute.ShizukuSetup)
    }

    // ── Vendor script runner only → the SCRIPT route ──────────────────────────

    @Test
    fun `vendor-script-applicable routes to ScriptLadder`() {
        assertThat(decideUniversalRoute(signals(advancedApplicable = true)))
            .isEqualTo(UniversalRoute.ScriptLadder)
    }

    // ── Plain device (none of the above) → the honest terminal ────────────────

    @Test
    fun `plain device routes to the honest PlainTerminal`() {
        assertThat(decideUniversalRoute(signals()))
            .isEqualTo(UniversalRoute.PlainTerminal)
    }

    @Test
    fun `plain device is NEVER routed to a privilege path it cannot use`() {
        val route = decideUniversalRoute(signals())
        assertThat(route).isNotEqualTo(UniversalRoute.AutoSetupHero)
        assertThat(route).isNotEqualTo(UniversalRoute.RootEnable)
        assertThat(route).isNotEqualTo(UniversalRoute.ShizukuSetup)
        assertThat(route).isNotEqualTo(UniversalRoute.ScriptLadder)
    }

    // ── Manual control stickiness ─────────────────────────────────────────────

    @Test
    fun `manual control on a vendor-script device stays on the completable script ladder`() {
        assertThat(
            decideUniversalRoute(signals(userTookManualControl = true, advancedApplicable = true))
        ).isEqualTo(UniversalRoute.ScriptLadder)
    }

    @Test
    fun `manual control on a plain device stays on the plain terminal (never trapped)`() {
        assertThat(
            decideUniversalRoute(signals(userTookManualControl = true, advancedApplicable = false))
        ).isEqualTo(UniversalRoute.PlainTerminal)
    }

    // ── Priority: a live path always wins over an opt-in path ──────────────────

    @Test
    fun `pserver-live wins over root-available and shizuku`() {
        assertThat(
            decideUniversalRoute(
                signals(pserverSysfsLive = true, rootAvailable = true, shizukuInstalled = true)
            )
        ).isEqualTo(UniversalRoute.AutoSetupHero)
    }
}
