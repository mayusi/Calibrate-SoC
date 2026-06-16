package io.github.mayusi.calibratesoc.data.autotdp

import kotlinx.serialization.Serializable

/**
 * Active control profile for the AutoTDP engine.
 *
 * EFFICIENCY   — aggressive core parking + big-cluster capping; maximises
 *                battery life at the expense of burst CPU headroom. Best
 *                for GPU-bound workloads (emulation, light 3D).
 *
 * BALANCED     — mild parking/capping; adapts to workload without straying
 *                far from stock behaviour. Default starting point.
 *
 * BATTERY_TARGET — user supplies a target power budget (watts). The engine
 *                  derives a big-cluster cap that keeps measured draw near
 *                  that budget. Requires [targetWatts] to be non-null.
 */
@Serializable
enum class AutoTdpProfile {
    EFFICIENCY,
    BALANCED,
    BATTERY_TARGET,
}

/**
 * Carries the optional target-watts budget used by [AutoTdpProfile.BATTERY_TARGET].
 * [targetMilliWatts] is null for EFFICIENCY and BALANCED; the daemon must supply a
 * positive value when BATTERY_TARGET is active.
 *
 * Kept as a lightweight carrier — the daemon passes this alongside the profile
 * into [AutoTdpEngine.decide] so the pure function can compute the proportional
 * cap without reaching for any Android context.
 *
 * ## Smart goal (Wave 4a)
 *
 * [goal] optionally carries the new 5-mode [GoalProfile] (incl. AUTO). When
 * non-null it is the ACTIVE intent — the daemon passes it to
 * [AutoTdpEngine.decide] as `goalOverride`, so the Smart engine (band-following +
 * AUTO classifier) is actually reached. When null the daemon falls back to the
 * legacy behaviour: [AutoTdpEngine.decide] internally maps [profile] →
 * [GoalProfile.fromLegacyProfile]. [profile] is ALWAYS kept populated (even when a
 * goal is set) for back-compat with the [EXTRA_PROFILE_ORDINAL] intent extra and
 * the [BATTERY_TARGET] watts-ceiling path; pick a sensible legacy mirror via
 * [forGoal].
 */
data class AutoTdpProfileConfig(
    val profile: AutoTdpProfile,
    /** Desired maximum steady-state draw in milliwatts. Non-null iff profile == BATTERY_TARGET. */
    val targetMilliWatts: Long? = null,
    /**
     * The active Smart [GoalProfile], or null to use the legacy [profile] mapping.
     * Carried end-to-end (controller → intent → daemon → engine `goalOverride`).
     */
    val goal: GoalProfile? = null,
) {
    companion object {
        /**
         * Build a config driven by a Smart [goal]. [profile] is set to a legacy
         * mirror so the [EXTRA_PROFILE_ORDINAL] extra and the watts-ceiling path stay
         * coherent, but [goal] is the authority the engine actually follows.
         *
         * The legacy mirror is chosen so the back-compat fields degrade sanely if the
         * goal were ever dropped: a goal that carries a hard power ceiling mirrors to
         * [AutoTdpProfile.BATTERY_TARGET] (so [targetMilliWatts] is honoured),
         * [GoalProfile.BALANCED_SMART]/AUTO mirror to BALANCED, and the remaining
         * thermally-lean goals mirror to EFFICIENCY.
         */
        fun forGoal(goal: GoalProfile, targetMilliWatts: Long? = null): AutoTdpProfileConfig {
            val mirror = when {
                goal.hasHardPowerCeiling -> AutoTdpProfile.BATTERY_TARGET
                goal == GoalProfile.BALANCED_SMART || goal == GoalProfile.AUTO -> AutoTdpProfile.BALANCED
                else -> AutoTdpProfile.EFFICIENCY
            }
            return AutoTdpProfileConfig(
                profile = mirror,
                targetMilliWatts = targetMilliWatts,
                goal = goal,
            )
        }
    }
}
