package io.github.mayusi.calibratesoc.data.autotdp

/**
 * The configurable cool-and-quiet profile applied automatically when the device
 * is plugged in to charge AND no game session is actively running.
 *
 * ## Axes
 * - [autoTdpGoal]  — which [GoalProfile] the daemon runs while charging.
 *                    Default: [GoalProfile.COOL_QUIET] (lowest temps, aggressive
 *                    pre-empt). Use [GoalProfile.EFFICIENCY] for maximum battery
 *                    saving when charging is slow.
 * - [fanMode]      — vendor fan-mode preset index, or null (don't touch fan).
 *                    Matches [io.github.mayusi.calibratesoc.data.autotdp.GoalProfile.FanPresets]:
 *                    0 = Quiet, 4 = Smart, 5 = Sport.
 *                    Default: [GoalProfile.FanPresets.QUIET] (0).
 * - [refreshRateHz] — preferred display Hz while charging, or null (don't touch).
 *                     Default: 60f (lowest common refresh rate = minimum panel power).
 *                     Pass null to leave the display rate untouched.
 *
 * ## Default = preserves old behaviour + adds new axes
 * The pre-bundle IdleChargeTrigger hardcoded [AutoTdpProfile.EFFICIENCY], which
 * maps to the daemon's [GoalProfile.COOL_QUIET] goal via the legacy-profile bridge.
 * [ChargingBundle.DEFAULT] uses [GoalProfile.COOL_QUIET] directly (same result)
 * and adds quiet-fan + 60 Hz on top of it, so existing opt-in users get the same
 * AutoTDP behaviour they had plus the two new axes.
 *
 * ## Not-gaming contract
 * This bundle is ONLY applied when no game bundle / AutoTDP session is already
 * active. If a game is running, [ChargingTuneTrigger] skips the bundle and logs
 * the skip. The toggle description in the UI is honest about this behaviour.
 */
data class ChargingBundle(
    /** AutoTDP goal to run while charging. Never null — always actively manages thermals. */
    val autoTdpGoal: GoalProfile = GoalProfile.COOL_QUIET,
    /**
     * Vendor fan-mode preset index, or null to leave the fan untouched.
     * Uses the same Settings.System key the per-app bundles write.
     */
    val fanMode: Int? = GoalProfile.FanPresets.QUIET,
    /**
     * Display refresh-rate Hz to pin while charging, or null to leave untouched.
     * Lower rate = less panel power drawn from the charger.
     */
    val refreshRateHz: Float? = 60f,
) {
    companion object {
        /**
         * Sensible cool-and-quiet defaults:
         *  - COOL_QUIET goal: lowest temps, aggressive pre-empt
         *  - Quiet fan (preset 0): silent operation while on charger
         *  - 60 Hz: minimum panel power
         *
         * This is what the opt-in toggle enables. The existing EFFICIENCY-only
         * behaviour is subsumed: COOL_QUIET is the Smart-native equivalent of
         * the legacy EFFICIENCY profile.
         */
        val DEFAULT = ChargingBundle(
            autoTdpGoal = GoalProfile.COOL_QUIET,
            fanMode = GoalProfile.FanPresets.QUIET,
            refreshRateHz = 60f,
        )
    }
}
