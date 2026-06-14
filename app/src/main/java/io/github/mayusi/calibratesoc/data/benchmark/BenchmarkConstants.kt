package io.github.mayusi.calibratesoc.data.benchmark

/**
 * Shared constants for benchmark and stability analysis.
 *
 * [SUSTAINED_WINDOW_RATIO] — the fraction of samples/loops that define the
 * "sustained" window: the last 25% of the run. Used identically in
 * [StabilityResult] (loop-FPS tail average) and [ThrottleAnalysis] (CPU MHz
 * tail average). Keeping it in one place ensures both analyses use the same
 * boundary.
 *
 * Usage:
 *   val tailFrom = (list.size * SUSTAINED_WINDOW_RATIO).toInt().coerceAtMost(list.size - 1)
 */
internal const val SUSTAINED_WINDOW_RATIO = 0.75
