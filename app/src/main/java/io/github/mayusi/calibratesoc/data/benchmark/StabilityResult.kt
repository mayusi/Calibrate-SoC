package io.github.mayusi.calibratesoc.data.benchmark

/** Result of a sustained GPU stability run (Wild Life Extreme Stress style).
 *  stabilityPct = (min loop FPS / max loop FPS) * 100 — 100% means no
 *  throttling under sustained load. */
data class StabilityResult(
    val loopFps: List<Double>,
    val stabilityPct: Int,
    val samples: List<ThrottleSample>,
    val outcome: BenchOutcome,
    val loopCount: Int,
    val startedAtMs: Long,
    val durationMs: Long,
) {
    val minFps: Double get() = loopFps.minOrNull() ?: 0.0
    val maxFps: Double get() = loopFps.maxOrNull() ?: 0.0
    val peakTempC: Float get() = samples.maxOfOrNull { it.cpuMaxTempC } ?: 0f
    companion object {
        fun compute(loopFps: List<Double>): Int {
            val mx = loopFps.maxOrNull() ?: return 0
            val mn = loopFps.minOrNull() ?: return 0
            if (mx <= 0.0) return 0
            return ((mn / mx) * 100).toInt().coerceIn(0, 100)
        }
    }
}
