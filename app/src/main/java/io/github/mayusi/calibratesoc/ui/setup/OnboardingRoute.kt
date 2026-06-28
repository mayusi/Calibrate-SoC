package io.github.mayusi.calibratesoc.ui.setup

/**
 * Pure, Android-free decision logic for the FIRST-RUN onboarding wizard's
 * top-level route.
 *
 * History / why this exists
 * -------------------------
 * The onboarding wizard has two layers:
 *   1. [OnboardingRoute.stepIdx] ladder — welcome → the three OLD per-permission
 *      manual steps (overlay / usage / battery) → all-done.
 *   2. An `advancedPhase` overlay state machine (Ask / AutoSetup / …) that, when
 *      not `None`, owns the whole screen.
 *
 * The one-click "Set up everything" HERO lives behind `advancedPhase ==
 * AutoSetup`. Before this function existed there was NO routing that sent a
 * PServer-live first-run user there: the hero was only reachable from the
 * all-done step at the END of the old per-permission ladder. So a PServer-live
 * device (which the `isTransactable()` probe confirms is live) was still walked
 * through the OLD manual flow — the exact bug users hit on the Retroid Pocket 6.
 *
 * This function is the single source of truth for that routing decision. It is
 * intentionally pure so it can be unit-tested without Compose or an Android
 * runtime, and so the composable's behaviour is locked by tests.
 *
 * Reactivity contract
 * -------------------
 * The capability probe is async: on the very first composition the report is
 * `null` (`capResolved = false`) and we must NOT commit to the old ladder.
 * Instead we render a brief [OnboardingRoute.Checking] transient. The composable
 * collects the capability as Compose state, so when the probe lands and
 * `pserverSysfsLive` flips true, recomposition re-runs this function and it
 * returns [OnboardingRoute.AutoSetupHero] — landing the user straight on the
 * one-click hero with no manual ladder.
 */
sealed interface OnboardingRoute {
    /**
     * Capability not yet resolved (probe in flight). Show a lightweight
     * "Checking your device…" placeholder rather than committing to the
     * manual ladder — avoids stranding a PServer-live user on the old flow
     * because of a first-frame race.
     */
    object Checking : OnboardingRoute

    /**
     * PServer-live device: jump straight to the one-click "Set up everything"
     * hero (the `AdvPhase.AutoSetup` screen), collapsing the old per-permission
     * ladder. This is the happy path for Odin / Retroid Pocket 6 / AYANEO.
     */
    object AutoSetupHero : OnboardingRoute

    /**
     * Genuinely non-PServer device (or any device where the one-tap self-grant
     * bridge doesn't apply): walk the existing `stepIdx` manual ladder. This
     * preserves the honest script / Shizuku / stock / Enforcing-last-resort
     * path for devices that actually need it.
     */
    object ManualLadder : OnboardingRoute
}

/**
 * Decide the first-run wizard's top-level route from the reactive signals.
 *
 * @param capResolved  whether the capability probe has produced a report yet
 *                     (`capability != null`). False on the first frame.
 * @param pserverSysfsLive  the probe's verdict that PServer-root live tuning is
 *                          active — the same signal `isTransactable()` confirms.
 *                          Only meaningful when [capResolved] is true.
 * @param userTookManualControl  true once the user has explicitly opted into the
 *                          manual flow (entered an advanced sub-phase by hand, or
 *                          navigated past the welcome step on a non-live device).
 *                          When set we never yank them onto the auto route — the
 *                          decision is sticky to user intent.
 *
 * Rules (in order):
 *   1. userTookManualControl                → ManualLadder  (respect the user)
 *   2. !capResolved                         → Checking       (probe in flight)
 *   3. capResolved && pserverSysfsLive      → AutoSetupHero  (one-click hero)
 *   4. capResolved && !pserverSysfsLive     → ManualLadder   (real non-PServer)
 */
fun decideOnboardingRoute(
    capResolved: Boolean,
    pserverSysfsLive: Boolean,
    userTookManualControl: Boolean,
): OnboardingRoute = when {
    userTookManualControl -> OnboardingRoute.ManualLadder
    !capResolved -> OnboardingRoute.Checking
    pserverSysfsLive -> OnboardingRoute.AutoSetupHero
    else -> OnboardingRoute.ManualLadder
}
