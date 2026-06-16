package io.github.mayusi.calibratesoc.data.boost

/**
 * Configuration for a Game Boost session.
 *
 * Game Boost is the brute "max performance" mode: it pins every cluster + GPU + bus
 * to the ceiling for raw FPS. It is deliberately TIME-BOXED and THERMALLY GUARDED —
 * max heat for max FPS is never free, so the run is bounded.
 *
 * @param timeBoxMinutes Auto-revert after this many minutes. Default
 *   [DEFAULT_TIME_BOX_MINUTES]. The session stops + reverts everything when the box
 *   expires even if the user never taps stop. Clamped to [MIN_TIME_BOX_MINUTES] ..
 *   [MAX_TIME_BOX_MINUTES] by [normalized].
 * @param setFanSport When true (default) the bundle also flips fan_mode → Sport for
 *   max cooling while boosted (only on devices with a controllable fan key).
 */
data class GameBoostConfig(
    val timeBoxMinutes: Int = DEFAULT_TIME_BOX_MINUTES,
    val setFanSport: Boolean = true,
) {
    /** Clamp the time box into the supported range. */
    fun normalized(): GameBoostConfig =
        copy(timeBoxMinutes = timeBoxMinutes.coerceIn(MIN_TIME_BOX_MINUTES, MAX_TIME_BOX_MINUTES))

    /** Time box expressed in milliseconds (post-normalisation). */
    val timeBoxMillis: Long
        get() = normalized().timeBoxMinutes * 60_000L

    companion object {
        const val DEFAULT_TIME_BOX_MINUTES = 30
        const val MIN_TIME_BOX_MINUTES = 1
        const val MAX_TIME_BOX_MINUTES = 120
    }
}
