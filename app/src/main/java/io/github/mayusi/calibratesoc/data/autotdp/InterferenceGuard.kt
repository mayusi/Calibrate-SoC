package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.gameaware.KnownControllers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NON-INTERFERENCE / BACK-OFF decision core.
 *
 * AutoTDP must NOT fight another app that is already driving the SoC's performance.
 * When any back-off signal fires, AutoTDP FULLY SUSPENDS (drives its target to STOCK
 * through the normal per-tick apply path so the hardened revert machinery restores the
 * clocks) and stays suspended until EVERY signal clears — then it auto-resumes on the
 * next tick for free. This class holds ONLY the pure OR-decision so it is unit-testable
 * without an Android runtime; the caller ([AutoTdpService]) resolves the Android-flavoured
 * inputs (foreground package, bundle existence, CATEGORY_GAME, sysfs contention, the live
 * pause flag) and passes plain booleans/strings in.
 *
 * Modelled on [io.github.mayusi.calibratesoc.data.boost.BoostArbiter]'s @Singleton shape:
 * a tiny injectable holder whose core logic is a pure function.
 *
 * ## The back-off signals (ANY true ⇒ suspend)
 *
 *  - (a) [SuspendReason.KNOWN_CONTROLLER] — a KNOWN external controller (Nova/GameNative,
 *        Winlator, a named tuner) is foreground. See [KnownControllers]. This is the fast
 *        path: an instant name lookup, no Binder call, for controllers we already recognise.
 *  - (a') [SuspendReason.EXTERNAL_CONTROLLER] — a LIST-UNKNOWN app that is nonetheless very
 *        likely a performance controller BY BEHAVIOUR: the caller resolves a single generic
 *        identity heuristic ([genericController]) and passes it in. This makes signal (a)
 *        catch tuners we never hard-coded, WITHOUT a name list — see the safety note below.
 *  - (b) [SuspendReason.SYSFS_CONTENTION] — something external keeps overwriting our clock
 *        writes (the moving-target readback detector in the service feeds this).
 *  - (c) [SuspendReason.MANUAL_PAUSE] — the user flipped the "Pause tuning" toggle (e.g.
 *        before a Nova session). Read LIVE so it takes effect immediately.
 *  - (d) [SuspendReason.UNTUNED_GAME] — an untuned foreground game/3D app is on screen: it
 *        is a game (KnownGames hit or ApplicationInfo.CATEGORY_GAME) AND the user has NOT
 *        created a CalibrateSoC bundle/profile for it. We never flag a game the user
 *        explicitly wants us to tune.
 *
 * ## Why (a') can't over-fire (the generic identity heuristic)
 *
 * [genericController] is resolved by [AutoTdpService] and is TRUE only when the foreground
 * app holds `WRITE_SECURE_SETTINGS` — a signature/privileged runtime permission almost
 * nothing holds by accident — AND it is not a game (that is signal (d)'s job), AND the user
 * has not created a bundle for it (they want it tuned), AND it is not our own package. That
 * AND-chain means a false positive would require a FOREGROUND app that holds WSS, is not a
 * game, and is not one the user set up — i.e. almost certainly another tuner. The heuristic
 * is deliberately NARROW: no foreground-service-declaration or system-app signals feed it
 * (both are far too broad — music/download apps declare FG services, and the system-app
 * population is huge — and would over-fire). Signal (a) is therefore
 * `KnownControllers.isController(pkg) || genericController`.
 *
 * ## Honesty
 *
 * When suspended, [decide] returns WHICH signal fired first (priority order below) so the
 * HUD can say "Suspended — Nova is controlling performance" instead of silently doing
 * nothing. Priority: manual pause (user's explicit will) → known controller → external
 * (list-unknown) controller → sysfs contention → untuned game. This ordering is
 * presentation-only; the suspend decision is a plain OR and any single true signal suspends.
 *
 * ## Safety
 *
 * This guard is ADDITIVE. It never weakens the existing thermal/battery safety kills — a
 * kill still fully STOPS the daemon; this guard only drives target=STOCK while running, so
 * it never leaves clocks pinned fighting another app, and always auto-resumes when clear.
 */
@Singleton
class InterferenceGuard @Inject constructor() {

    /** Which back-off signal caused the suspend (for the honest HUD surface). */
    enum class SuspendReason(
        /** Short human phrase for the HUD, completing "Suspended — <phrase>". */
        val hudPhrase: String,
    ) {
        /** (c) The user flipped the manual "Pause tuning" toggle. */
        MANUAL_PAUSE("paused by you (another app in control)"),

        /** (a) A known external controller (Nova/GameNative, Winlator, a tuner) is foreground. */
        KNOWN_CONTROLLER("another performance app is controlling the SoC"),

        /**
         * (a') A LIST-UNKNOWN app that is very likely a performance controller by BEHAVIOUR
         * (holds WRITE_SECURE_SETTINGS, is not a game, is not user-tuned, is not us) is
         * foreground. Same suspend as [KNOWN_CONTROLLER] but honestly labelled as detected-
         * by-behaviour rather than by name.
         */
        EXTERNAL_CONTROLLER("another performance app appears to be in control"),

        /** (b) Something external keeps overwriting our clock writes. */
        SYSFS_CONTENTION("another app is overriding the clocks"),

        /** (d) An untuned foreground game is on screen (no user bundle for it). */
        UNTUNED_GAME("an untuned game is running — leaving it on stock"),
    }

    /**
     * The result of a back-off evaluation.
     *
     * @param suspend       true ⇒ AutoTDP must revert to stock and stop tuning this tick.
     * @param reason        the FIRST-priority signal that fired, or null when not suspended.
     *                      Drives the "Suspended — X is controlling performance" HUD text.
     */
    data class Verdict(
        val suspend: Boolean,
        val reason: SuspendReason?,
    ) {
        companion object {
            /** Not suspended — AutoTDP runs normally this tick. */
            val RUNNING = Verdict(suspend = false, reason = null)
        }
    }

    /**
     * PURE OR-decision. No Android, no I/O — every input is resolved by the caller.
     *
     * @param foregroundPackage    the current foreground package (window.last().foregroundPackage),
     *                             or null when unknown.
     * @param hasUserBundleForPkg  true when the user has created a CalibrateSoC bundle/profile for
     *                             [foregroundPackage] (so a game they explicitly want tuned is NOT
     *                             flagged by signal (d)).
     * @param isGameForPkg         true when [foregroundPackage] is a game (KnownGames hit OR
     *                             ApplicationInfo.CATEGORY_GAME) — resolved by the caller.
     * @param externalDriveActive  signal (b): the service's moving-target readback detector has
     *                             concluded an external driver is overwriting our writes.
     * @param manuallyPaused       signal (c): the LIVE value of the manual "Pause tuning" toggle.
     * @param genericController    signal (a'): the caller's generic identity heuristic has
     *                             concluded [foregroundPackage] is a LIST-UNKNOWN performance
     *                             controller by BEHAVIOUR (holds WRITE_SECURE_SETTINGS AND is
     *                             not a game AND is not user-tuned AND is not us). Already fully
     *                             guarded by the caller — this class just ORs it into signal (a).
     *                             See the class-level "Why (a') can't over-fire" note.
     */
    fun decide(
        foregroundPackage: String?,
        hasUserBundleForPkg: Boolean,
        isGameForPkg: Boolean,
        externalDriveActive: Boolean,
        manuallyPaused: Boolean,
        genericController: Boolean = false,
    ): Verdict {
        // Priority order = HUD-presentation order. The suspend flag itself is a pure OR;
        // the reason we surface is the highest-priority signal that fired.
        val reason: SuspendReason? = when {
            // (c) the user's explicit will wins the label.
            manuallyPaused -> SuspendReason.MANUAL_PAUSE
            // (a) a KNOWN external controller is foreground — instant name lookup, no Binder.
            KnownControllers.isController(foregroundPackage) -> SuspendReason.KNOWN_CONTROLLER
            // (a') a LIST-UNKNOWN app that behaves like a controller (caller-guarded WSS
            // heuristic). ORed into signal (a) so ANY controlling app backs us off, not just
            // the hard-coded ones — but honestly labelled as detected-by-behaviour.
            genericController -> SuspendReason.EXTERNAL_CONTROLLER
            // (b) an external driver is overwriting our writes.
            externalDriveActive -> SuspendReason.SYSFS_CONTENTION
            // (d) an untuned foreground game (a game with NO user bundle).
            isGameForPkg && !hasUserBundleForPkg -> SuspendReason.UNTUNED_GAME
            else -> null
        }
        return if (reason == null) Verdict.RUNNING else Verdict(suspend = true, reason = reason)
    }
}
