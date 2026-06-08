package io.github.mayusi.calibratesoc.data.benchmark

/** Result of a sustained GPU stability run (Wild Life Extreme Stress style).
 *  stabilityPct = (avg of last 25% loop FPS / max loop FPS) * 100 — 100% means
 *  performance held under sustained load; lower means throttling occurred. */
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
    val avgSustainedFps: Double get() = sustainedFpsAverage()
    val peakTempC: Float get() = samples.maxOfOrNull { it.cpuMaxTempC } ?: 0f
    val peakGpuTempC: Float? get() = samples.mapNotNull { it.gpuTempC }.maxOrNull()

    private fun sustainedFpsAverage(): Double {
        if (loopFps.isEmpty()) return 0.0
        val tailFrom = (loopFps.size * 0.75).toInt().coerceAtMost(loopFps.size - 1)
        return loopFps.drop(tailFrom).average()
    }

    companion object {
        /** Compute sustained/peak % — avg of last 25% over max FPS * 100.
         *  Gated on REAL clock drops (from ThrottleAnalysis), not just FPS variance. */
        fun compute(loopFps: List<Double>): Int {
            val mx = loopFps.maxOrNull() ?: return 0
            if (mx <= 0.0) return 0
            val tailFrom = (loopFps.size * 0.75).toInt().coerceAtMost(loopFps.size - 1)
            val sustained = if (loopFps.isEmpty()) 0.0 else loopFps.drop(tailFrom).average()
            return ((sustained / mx) * 100).toInt().coerceIn(0, 100)
        }
    }
}

/** Honest verdict on throttling, gated on REAL hardware behavior (clocks, temps) not just FPS variance. */
data class StabilityVerdict(
    val word: String,        // e.g. "Rock solid", "Mild throttling", "Heavy throttling"
    val colorHint: String,   // "primary", "secondary", "tertiary", "error" — let UI map to colorScheme
    val explanation: String, // e.g. "Clocks held 95% of peak — no throttling." or "Dropped 20% MHz under heat."
)

fun makeThrottleVerdict(
    analysis: ThrottleAnalysis?,
    peakTempC: Float,
    killTempC: Float,
    sustainedPct: Int = -1,
): StabilityVerdict {
    if (analysis == null) {
        return StabilityVerdict(
            word = "—",
            colorHint = "primary",
            explanation = "No samples collected.",
        )
    }

    val dropPct = analysis.dropPct   // always >= 0 (clamped in ThrottleAnalysis.from)
    val headroomC = analysis.thermalHeadroomC ?: (killTempC - peakTempC)
    val sustained = analysis.sustainedMhz
    val peak = analysis.peakMhz
    val ttMs = analysis.timeToThrottleMs

    // FPS sustained ratio is the PRIMARY signal; clock drop corroborates.
    // sustainedPct == -1 means caller did not supply it — fall back to clock-only gating.
    val fpsBased = sustainedPct >= 0

    return when {
        // Rock solid: FPS held ≥95% AND clocks dropped <8% (or no FPS data: <5% drop + headroom ok).
        (fpsBased && sustainedPct >= 95 && dropPct < 8.0) ||
        (!fpsBased && dropPct < 5.0 && headroomC >= 5f) -> {
            val msg = "Held peak the whole run — clocks stayed at ~${sustained} MHz, no throttling. Peaked at ${peakTempC.toInt()}°C."
            StabilityVerdict("Rock solid", "tertiary", msg)
        }

        // Mild throttling: FPS ≥85% OR clock drop 8–18%.
        (fpsBased && sustainedPct >= 85) ||
        (!fpsBased && dropPct in 5.0..15.0 && headroomC >= 0f) ||
        (fpsBased && dropPct in 8.0..18.0) -> {
            val msg = "Mild throttle — sustained ${sustained} MHz vs ${peak} MHz peak (${dropPct.toInt()}% drop). Headroom ${headroomC.toInt()}°C."
            StabilityVerdict("Mild throttling", "primary", msg)
        }

        // Heavy throttling: FPS <85% AND clock drop >18% (or legacy clock-only path).
        else -> {
            val ttStr = ttMs?.let { "after ${it / 1000}s" } ?: "throughout"
            val msg = "Sustained ${sustained} MHz vs ${peak} MHz peak (${dropPct.toInt()}% drop) — throttled ${ttStr}. Peaked at ${peakTempC.toInt()}°C."
            StabilityVerdict("Heavy throttling", "error", msg)
        }
    }
}
