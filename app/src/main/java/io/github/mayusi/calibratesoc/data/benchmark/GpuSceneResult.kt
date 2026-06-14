package io.github.mayusi.calibratesoc.data.benchmark

import kotlinx.serialization.Serializable

/**
 * Resolution tiers for the 3D scene benchmark.
 * All tiers render offscreen so scores are comparable regardless of
 * physical display resolution.
 *
 * NOTE: Our own original benchmark — compare your own runs across
 * different tunes/settings on the same device. Numbers are NOT
 * comparable to other chips or other benchmarks.
 */
@Serializable
enum class SceneTier(
    val label: String,
    val width: Int,
    val height: Int,
) {
    /** 1080p — minimum for meaningful 3D load; fallback target. */
    STANDARD("1080p", 1920, 1080),

    /** 1440p — the primary/default tier. Balanced GPU load vs. timing resolution. */
    EXTREME("1440p", 2560, 1440),

    /** 2160p (4K) — only on high-end devices with a large enough renderbuffer. */
    ULTRA("2160p (4K)", 3840, 2160),
}

/**
 * Per-loop FPS summary for the sustained-loop model.
 *
 * Each loop's [avgFps] is measured identically to [GpuFrameResult] so the
 * stability% formula is directly comparable to the existing [StabilityResult]
 * computation.
 */
@Serializable
data class SceneLoopResult(
    val loopIndex: Int,
    val avgFps: Double,
    val avgFrameMs: Double,
)

/**
 * Full result from a [GpuSceneBenchmark] run.
 *
 * Metrics mirror [GpuFrameSummary] for consistency with the existing bench
 * pipeline. All fields nullable so callers that only use a subset can ignore
 * the rest without risk of NPE.
 *
 * Stability% = avg of last-25%-loop FPS / best-loop FPS × 100
 * (same formula as [StabilityResult.compute], just applied to per-loop avg FPS
 * instead of per-loop stress-test FPS).
 *
 * This result rides inside [KernelScores.sceneJson] — a single JSON blob added
 * as a nullable field so old DB rows deserialise fine via ignoreUnknownKeys.
 * No DB schema version bump needed.
 */
@Serializable
data class GpuSceneResult(
    /** Which tier actually ran (may differ from requested if driver limited FBO). */
    val tier: SceneTier,
    /** Actual offscreen resolution used (may be clamped). */
    val renderWidthPx: Int,
    val renderHeightPx: Int,
    /** "GLES 3.0" or "GLES 2.0 (fallback)" */
    val apiLabel: String,
    /** Approximate triangles per frame rendered. */
    val trianglesPerFrame: Long,
    /** Number of render passes per frame (1 = forward-only, 2 = depth-prepass + lit). */
    val passCount: Int,

    // Per-frame timing across the whole run (all loops combined after warmup).
    val avgFps: Double,
    val avgFrameMs: Double,
    val p50Fps: Double,
    val p1LowFps: Double,
    val p99FrameMs: Double,
    val consistencyPct: Double,

    /** Stability% = avg of last 25% loop avgFps / peak loop avgFps * 100.
     *  Null when only one loop ran (no meaningful sustained trend). */
    val stabilityPct: Int?,

    /** Per-loop summary for the thermal/FPS curve chart. */
    val loopResults: List<SceneLoopResult>,

    /** Downsampled (≤600 pts) frame-time series for the chart. */
    val frameTimesMsDownsampled: List<Float>,

    /** Peak CPU temperature observed during the run, from concurrent telemetry. */
    val peakCpuTempC: Float?,
    /** Peak GPU temperature observed during the run (null if sensor unavailable). */
    val peakGpuTempC: Float?,

    /** Wall-clock duration of the full benchmark, milliseconds. */
    val totalDurationMs: Long,
) {
    companion object {
        const val HONESTY_CAPTION =
            "Our own benchmark — compare your own runs, not other chips."

        /**
         * Compute stability% from per-loop avgFps list using the same
         * SUSTAINED_WINDOW_RATIO (last 25%) formula as [StabilityResult].
         * Returns null when fewer than 2 loops ran.
         */
        fun computeStabilityPct(loopFps: List<Double>): Int? {
            if (loopFps.size < 2) return null
            val mx = loopFps.maxOrNull() ?: return null
            if (mx <= 0.0) return null
            val tailFrom = (loopFps.size * SUSTAINED_WINDOW_RATIO).toInt()
                .coerceAtMost(loopFps.size - 1)
            val sustained = loopFps.drop(tailFrom).average()
            return ((sustained / mx) * 100).toInt().coerceIn(0, 100)
        }
    }
}
