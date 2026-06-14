package io.github.mayusi.calibratesoc.data.benchmark

/**
 * Shared constants for benchmark and stability analysis.
 *
 * [SUSTAINED_WINDOW_RATIO] — the **drop fraction**: the proportion of
 * samples/loops to DROP from the START of the run before computing the
 * "sustained" window. A value of 0.75 means the first 75% of the run is
 * discarded as warm-up/ramp, and the sustained window is the **remaining
 * last 25%**.
 *
 * Despite the name ending in "RATIO", this is the drop-point index divisor,
 * NOT the fraction of the run that is kept. The sustained window itself is
 * (1 − 0.75) = 25% of the total run.
 *
 * Used identically in [StabilityResult] (loop-FPS tail average) and
 * [ThrottleAnalysis] (CPU MHz tail average). Keeping it in one place ensures
 * both analyses use the same boundary.
 *
 * Usage:
 *   val tailFrom = (list.size * SUSTAINED_WINDOW_RATIO).toInt().coerceAtMost(list.size - 1)
 *   // tailFrom is the FIRST index of the sustained window (last 25% of the list).
 */
internal const val SUSTAINED_WINDOW_RATIO = 0.75
