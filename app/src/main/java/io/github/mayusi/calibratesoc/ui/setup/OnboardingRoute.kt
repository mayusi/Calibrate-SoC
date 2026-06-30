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
 * UNIVERSAL device routing (added to restore per-device granting paths).
 *
 * Why a second sealed set
 * -----------------------
 * The original [OnboardingRoute] only knew two real destinations: the PServer
 * one-click hero and the catch-all "ManualLadder". Every non-PServer device —
 * a Magisk/KernelSU rooted phone, a Shizuku-paired tablet, a vendor-script
 * handheld, or a plain stock phone — fell into the SAME ManualLadder, so none
 * of them were routed to THEIR correct grant path. The grant infrastructure
 * (root opt-in, Shizuku state machine, the unlock script, the universal perms)
 * all existed but nothing routed to it.
 *
 * [UniversalRoute] is the tier-aware destination set. It is a PURE function of
 * the reactive capability signals (see [decideUniversalRoute]) so it is fully
 * unit-testable without Compose. The composable maps each arm to the step
 * composable that drives the matching existing grant flow — it does NOT
 * reimplement any grant mechanism.
 *
 * HONESTY: every arm routes a device to a REAL path it can actually use. The
 * plain device gets [PlainTerminal] — the honest "HUD + monitoring work here;
 * live tuning needs a privilege path" terminal — and is NEVER trapped.
 */
sealed interface UniversalRoute {
    /** Probe in flight — show the "Checking your device…" transient. Transient,
     *  never a trap. */
    object Checking : UniversalRoute

    /** PServer-live (or AYANEO-binder-live): the frictionless one-click
     *  "Set up everything" hero. The grant here is MANDATORY (one tap, no
     *  reason to decline). */
    object AutoSetupHero : UniversalRoute

    /** Root is AVAILABLE (Magisk/KernelSU present) but root mode is NOT yet
     *  enabled: route to the ROOT opt-in step so the user reaches the ROOT
     *  tier instead of falling to NONE. Root is opt-in by nature, so declining
     *  is allowed (the universal perms still let them in). */
    object RootEnable : UniversalRoute

    /** Shizuku is installed (no PServer / no enabled-root): surface the EXISTING
     *  [io.github.mayusi.calibratesoc.data.shizuku.ShizukuOnboarding] state
     *  machine (install → pair → grant → per-node probe). Strongly offered;
     *  the universal perms still let a user in if they decline. */
    object ShizukuSetup : UniversalRoute

    /** A vendor script runner is present (AYN/Retroid/AYANEO Settings, or
     *  PServer transacts) but no live path / root / Shizuku: route to the
     *  EXISTING unlock-SCRIPT ladder. Completable once the script lands
     *  (the FIX-1 soft-trap fix). */
    object ScriptLadder : UniversalRoute

    /** Plain Android phone/tablet — none of the above. The honest terminal:
     *  the universal perms (overlay / usage / battery) for the HUD + monitoring
     *  features, plus an honest note that live tuning needs a privilege path.
     *  canComplete is true (door always open). */
    object PlainTerminal : UniversalRoute
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
 * LEGACY gate — SUPERSEDED by [canCompleteUniversal].
 *
 * This encoded the old "PServer-CAPABLE devices MUST hold the one-tap grant to
 * enter" hard-require, which is WRONG against Calibrate's actual philosophy:
 * the app is ROOT-OPTIONAL and fully works with no grant, so NO device is ever
 * hard-locked (see [canCompleteUniversal]). The onboarding screen no longer
 * calls this function. It is retained ONLY so its existing unit tests
 * (OnboardingGateTest) keep documenting the old binary behaviour without churn;
 * do NOT wire it into new code — route entry decisions through
 * [canCompleteUniversal].
 *
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

// =============================================================================
// UNIVERSAL routing + gate (restores per-device grant paths)
// =============================================================================

/**
 * The reactive signals the universal router needs, in ONE value object so the
 * decision is a pure function of a snapshot. Built in the composable from the
 * [io.github.mayusi.calibratesoc.data.capability.CapabilityReport] + the live
 * grant/Shizuku/root state.
 *
 * Every field is meaningful only when [capResolved] is true; before that the
 * router returns [UniversalRoute.Checking] regardless (so a garbage first-frame
 * value can never strand a user).
 */
data class UniversalSignals(
    /** Probe has produced a report (`capability != null`). */
    val capResolved: Boolean,
    /** Sticky: the user deliberately chose the manual ladder / a sub-phase. */
    val userTookManualControl: Boolean,
    /** PServer-root live tuning confirmed (the `isTransactable()` verdict). */
    val pserverSysfsLive: Boolean,
    /** AYANEO vendor perf binder confirmed live (zero-setup, like PServer). */
    val ayaneoBinderLive: Boolean,
    /** Root binary present (Magisk/KernelSU/other) — `rootKind != NONE`. */
    val rootAvailable: Boolean,
    /** User has opted into root mode AND root is live (`privilege == ROOT`). */
    val rootEnabled: Boolean,
    /** Shizuku app is installed on the device (running or not). */
    val shizukuInstalled: Boolean,
    /** A vendor script runner is present OR PServer transacts (the script
     *  whitelists us) — the unlock-SCRIPT ladder applies here. */
    val advancedApplicable: Boolean,
)

/**
 * Decide the UNIVERSAL first-run route — each device to ITS correct grant path.
 *
 * Priority (highest-value / most-frictionless path first):
 *   0. !capResolved                 → Checking      (probe in flight; never a trap)
 *   1. userTookManualControl        → ScriptLadder if advancedApplicable else
 *                                      PlainTerminal (respect the user's choice;
 *                                      keep them on a real, completable path)
 *   2. pserverSysfsLive || ayaneoBinderLive
 *                                    → AutoSetupHero (one-tap zero-setup grant)
 *   3. rootEnabled                  → AutoSetupHero (root IS a live one-tap path:
 *                                      the app can self-grant via the root writer,
 *                                      same as PServer)
 *   4. rootAvailable (not enabled)  → RootEnable    (offer "enable root mode")
 *   5. shizukuInstalled             → ShizukuSetup  (surface the Shizuku flow)
 *   6. advancedApplicable           → ScriptLadder  (the vendor unlock script)
 *   7. else                         → PlainTerminal (honest universal terminal)
 *
 * Rationale for ordering: a frictionless live path (PServer / AYANEO / enabled
 * root) always wins; a rooted-but-not-enabled device is offered the opt-in
 * BEFORE Shizuku/script because root is the strongest tier it can reach; Shizuku
 * (universal, no vendor app needed) precedes the vendor-specific script; the
 * plain terminal is the honest last resort that never traps.
 */
fun decideUniversalRoute(s: UniversalSignals): UniversalRoute = when {
    !s.capResolved -> UniversalRoute.Checking
    s.userTookManualControl ->
        if (s.advancedApplicable) UniversalRoute.ScriptLadder else UniversalRoute.PlainTerminal
    s.pserverSysfsLive || s.ayaneoBinderLive -> UniversalRoute.AutoSetupHero
    s.rootEnabled -> UniversalRoute.AutoSetupHero
    s.rootAvailable -> UniversalRoute.RootEnable
    s.shizukuInstalled -> UniversalRoute.ShizukuSetup
    s.advancedApplicable -> UniversalRoute.ScriptLadder
    else -> UniversalRoute.PlainTerminal
}

/**
 * Tier-aware completion gate — the universal replacement for the binary
 * [canCompleteOnboarding] that only understood the PServer one-tap.
 *
 * THE NORTH STAR — Calibrate SoC is ROOT-OPTIONAL. The app FULLY WORKS on ANY
 * device with NO grant at all: the HUD overlay, FPS, performance monitoring,
 * benchmarks, profiles, and insights ALL run on the NONE tier with zero
 * privilege. Root / PServer / Shizuku / script ONLY unlock the EXTRA live CPU/GPU
 * tuning (and that only matters on handhelds). It is "BETTER WITH", NEVER
 * "required". Therefore onboarding NEVER HARD-LOCKS ANY DEVICE — including a
 * PServer handheld.
 *
 * So the gate is simple and honest: once the capability probe has RESOLVED (so we
 * can show the device its real options), EVERY device can ALWAYS complete
 * onboarding and enter the app. The grant path is STRONGLY OFFERED per device —
 * the big inviting primary CTA (PServer one-tap / enable-root / Shizuku / script)
 * — but it is always skippable, and it stays re-reachable later from inside the
 * app (Settings / Tune). Onboarding offers the path; it is not the only chance,
 * and it is never a wall.
 *
 * Per-route policy:
 *   - Checking      → false  (probe in flight; transient — show "Checking…", not a
 *                     lock. The ONLY non-completable state, and it is momentary.)
 *   - everything else (AutoSetupHero / RootEnable / ShizukuSetup / ScriptLadder /
 *     PlainTerminal) → true   (resolved → the user has seen their options → they
 *                     can ALWAYS enter; the grant is offered, never required).
 *
 * The remaining parameters are retained for call-site clarity and so the UI can
 * decide what to EMPHASISE (e.g. whether the one-tap grant already landed, so the
 * hero collapses to a quiet "all set"); they no longer GATE entry. FIX 1 is
 * subsumed: a script/Shizuku/root device — like every device — can always
 * complete once resolved.
 *
 * @param oneTapGrantHeld  the PServer/AYANEO one-tap grant is HELD (live tuning
 *        active, or `setupEverything` produced a FullCompleted readback). Informs
 *        UI emphasis only.
 * @param bridgeUnavailable  the one-tap bridge was tried and reported NotAvailable.
 *        Informs UI emphasis only.
 * @param privilegePathGranted  a non-PServer privilege path actually landed (script
 *        granted / Shizuku granted / root enabled). Informs UI emphasis only.
 * @param universalPermsSatisfied  retained for signature stability; entry no longer
 *        depends on it (the universal perms are OFFERED, never required — the app
 *        works without them too).
 */
@Suppress("UNUSED_PARAMETER")
fun canCompleteUniversal(
    route: UniversalRoute,
    oneTapGrantHeld: Boolean,
    bridgeUnavailable: Boolean,
    privilegePathGranted: Boolean,
    universalPermsSatisfied: Boolean,
): Boolean = when (route) {
    // Probe still in flight — transient "Checking…" placeholder, NOT a lock. The
    // instant it resolves to any real route the door opens. Never permanent.
    UniversalRoute.Checking -> false
    // ROOT-OPTIONAL: every resolved device can ALWAYS enter. The grant is the
    // strongly-offered primary action per route, but skipping it still lets the
    // user into the fully-working app (HUD / monitoring / benchmarks / profiles).
    UniversalRoute.AutoSetupHero,
    UniversalRoute.RootEnable,
    UniversalRoute.ShizukuSetup,
    UniversalRoute.ScriptLadder,
    UniversalRoute.PlainTerminal -> true
}
