package io.github.mayusi.calibratesoc.data.profiles

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.gameaware.GamePlan

/**
 * Pure mapper: a known-game [GamePlan] hint → a CONSERVATIVE auto-created
 * [PerAppBundle].
 *
 * This is the brain behind the "app just handles everything" headline. When a
 * recognised game launches with no existing per-app bundle, [ForegroundAppWatcher]
 * calls [bundleFor] to synthesize a sensible starting tune from the game's tier
 * hint, persists it (flagged [PerAppBundle.autoCreated] = true), and lets the
 * normal apply/revert machinery run.
 *
 * ## Why this is a separate pure object
 * No Android, no I/O, no time — just `GamePlan → PerAppBundle?`. That keeps the
 * tier→defaults policy unit-testable in isolation and makes the conservatism
 * auditable in one place. [ForegroundAppWatcher] owns the side effects.
 *
 * ## The conservative-defaults contract (this is LAW — it auto-applies WITHOUT
 *    an explicit per-game tap; the global toggle is the only consent)
 *
 * The ONLY axis we auto-set is the AutoTDP **goal** — a goal-seeking band
 * controller that hunts the lowest-power operating point holding a target GPU
 * band, and which AUTO-REVERTS when the game exits (existing discipline). We map
 * the hint's advisory [GamePlan.autoTdpProfile] (which already encodes the
 * workload tier — HEAVY_3D/TRANSLATION_LAYER → BALANCED, HANDHELD_2D →
 * EFFICIENCY) through the exact same [GoalProfile.fromLegacyProfile] table the
 * rest of the app uses, so the auto-created goal is identical to what a manual
 * "use the hint" would pick:
 *
 *   | Advisory profile (tier)                 | Auto goal      | Rationale                         |
 *   |-----------------------------------------|----------------|-----------------------------------|
 *   | BALANCED  (HEAVY_3D / TRANSLATION)      | BALANCED_SMART | hold the knee — sustainable FPS   |
 *   | EFFICIENCY (HANDHELD_2D)                | COOL_QUIET     | battery/thermal lean for light 2D |
 *   | BATTERY_TARGET                          | BATTERY_SAVER  | (not produced by KnownGames today)|
 *
 * We DELIBERATELY DO NOT auto-set, because none of these are safe to apply
 * without explicit per-game consent:
 *  - [PerAppBundle.profileId] — null. We never guess which saved user profile to
 *    apply; a profile can carry aggressive custom CPU/GPU caps. Profiles are the
 *    user's to bind.
 *  - [PerAppBundle.gameBoostOnLaunch] — false. Game Boost brute-PINS every cluster
 *    + GPU + bus to the ceiling; that is the OPPOSITE of conservative and would
 *    cook the device on an unattended auto-apply. Never auto-enabled.
 *  - [PerAppBundle.refreshRateHz] — null. Display-rate intent is per-user and
 *    per-display; a wrong guess is jarring. Leave the system default.
 *  - [PerAppBundle.fanMode] — null. Let the AutoTDP goal's own fan governor (or
 *    the vendor default) manage the fan; we don't pin a vendor preset blindly.
 *
 * Net effect: an auto-created bundle is "start the goal-seeking governor in the
 * right gear for this game's workload, and revert it cleanly when you leave."
 * It is the gentlest possible non-no-op — honest about being a starting default,
 * not a claim of optimality.
 */
object AutoConfigBundleMapper {

    /**
     * Build a conservative auto-created [PerAppBundle] from a known-game [hint],
     * or null when the hint carries no actionable advisory (defensive — every
     * current [GamePlan] from [io.github.mayusi.calibratesoc.data.gameaware.KnownGames]
     * has a non-null [GamePlan.autoTdpProfile], but a future hint without one must
     * NOT produce an empty/no-op bundle that would suppress a later real config).
     *
     * The returned bundle ALWAYS has [PerAppBundle.autoCreated] == true so the
     * undo + provenance contract holds.
     */
    fun bundleFor(hint: GamePlan): PerAppBundle? {
        val advisory = hint.autoTdpProfile ?: return null
        return PerAppBundle(
            // Never guess a user profile — profiles can carry aggressive caps.
            profileId = null,
            // The single axis we auto-set: the goal-seeking governor, in the gear
            // the hint's tier implies. Reuse the canonical legacy→goal table so the
            // auto pick matches a manual "apply the hint" exactly.
            autoTdpGoal = goalFor(advisory),
            // Conservative: no display, fan, or brute-pin boost on an unattended
            // auto-apply. See the class kdoc for the full rationale.
            refreshRateHz = null,
            fanMode = null,
            gameBoostOnLaunch = false,
            // Provenance — this is the auto-create flag the undo + UI key off.
            autoCreated = true,
        )
    }

    /**
     * Map the advisory [AutoTdpProfile] to its [GoalProfile] via the SAME table
     * the daemon's back-compat path uses ([GoalProfile.fromLegacyProfile]):
     * EFFICIENCY → COOL_QUIET, BALANCED → BALANCED_SMART, BATTERY_TARGET →
     * BATTERY_SAVER. Kept as a thin delegate (not an inline `when`) so there is a
     * single source of truth for legacy→goal and the auto-config can never drift
     * from it.
     */
    private fun goalFor(advisory: AutoTdpProfile): GoalProfile =
        GoalProfile.fromLegacyProfile(advisory)
}
