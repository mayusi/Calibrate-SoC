package io.github.mayusi.calibratesoc.data.autotdp

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
enum class AutoTdpProfile {
    EFFICIENCY,
    BALANCED,
    BATTERY_TARGET,
}

/**
 * Carries the optional target-watts budget used by [AutoTdpProfile.BATTERY_TARGET].
 * [targetWatts] is null for EFFICIENCY and BALANCED; the daemon must supply a
 * positive value when BATTERY_TARGET is active.
 *
 * Kept as a lightweight carrier — the daemon passes this alongside the profile
 * into [AutoTdpEngine.decide] so the pure function can compute the proportional
 * cap without reaching for any Android context.
 */
data class AutoTdpProfileConfig(
    val profile: AutoTdpProfile,
    /** Desired maximum steady-state draw in milliwatts. Non-null iff profile == BATTERY_TARGET. */
    val targetMilliWatts: Long? = null,
)
