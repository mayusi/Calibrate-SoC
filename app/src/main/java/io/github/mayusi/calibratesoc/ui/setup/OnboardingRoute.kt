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

/**
 * Pure gate deciding whether the user is allowed to FINISH onboarding (i.e.
 * `markComplete()` may flip the `onboardingComplete` flag and let them into the
 * app).
 *
 * Why this exists / the policy
 * ----------------------------
 * On a PServer-CAPABLE device (there IS a privilege path — PServer-live, root,
 * AYANEO binder, or a vendor device where the one-tap self-grant is meaningful)
 * the one-tap setup is MANDATORY: the user cannot skip into the app without
 * holding the grant. This collapses every "Skip setup / Not now" escape on those
 * devices — the only way forward is the (single, frictionless) grant.
 *
 * HONESTY is preserved by three carve-outs so we never permanently trap a user:
 *   1. NON-capable device (no privilege path at all — a plain Android phone that
 *      physically can't grant): the door stays OPEN. Hard-locking a device that
 *      genuinely can't grant would strand the user, so the mandatory rule only
 *      applies where there is something to grant THROUGH.
 *   2. PROBING (capability not resolved yet): we neither complete nor lock —
 *      the caller shows the "Checking your device…" transient. A slow or wrong
 *      first-frame probe must never permanently trap a user.
 *   3. BRIDGE GENUINELY UNAVAILABLE (the device LOOKED capable but the one-tap
 *      PServer bridge turned out not transactable — `FullSetupState.NotAvailable`):
 *      we open the door. The app honestly couldn't grant, so it must not lock the
 *      user out of a device it can't actually serve.
 *
 * @param capResolved  whether the capability probe has produced a report yet.
 *                     False on the first frame (probe in flight).
 * @param deviceCapable  true when a privilege path exists (`advancedApplicable`
 *                     OR live-already-active). Only meaningful when [capResolved].
 * @param grantHeld  true when the mandatory grant is actually HELD: live tuning
 *                     is already active (PServer-live / root / AYANEO-binder /
 *                     sysfs-writable) OR the one-tap `setupEverything` produced a
 *                     post-grant readback (`FullSetupState.FullCompleted`).
 * @param bridgeUnavailable  true when the one-tap bridge was tried and reported
 *                     `NotAvailable` (not transactable here) — the honesty escape.
 *
 * Rules (in order):
 *   1. !capResolved        → false  (probing: never complete, never trap)
 *   2. !deviceCapable      → true   (non-capable: door always open)
 *   3. grantHeld           → true   (capable + grant held: enter)
 *   4. bridgeUnavailable   → true   (capable but bridge can't grant: honesty)
 *   5. else                → false  (capable, grant not held, bridge live: MUST grant)
 */
fun canCompleteOnboarding(
    capResolved: Boolean,
    deviceCapable: Boolean,
    grantHeld: Boolean,
    bridgeUnavailable: Boolean,
): Boolean = when {
    !capResolved -> false
    !deviceCapable -> true
    grantHeld -> true
    bridgeUnavailable -> true
    else -> false
}
