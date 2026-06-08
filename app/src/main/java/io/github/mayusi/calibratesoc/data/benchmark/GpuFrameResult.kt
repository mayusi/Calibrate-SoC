package io.github.mayusi.calibratesoc.data.benchmark

import kotlinx.serialization.Serializable

/** Rich GPU fragment-bench result. Replaces the bare Double from
 *  GpuTriangleStorm.run(). frameTimesMs is the RAW capture (not persisted);
 *  the runner downsamples + summarizes into KernelScores. */
data class GpuFrameResult(
    val avgFps: Double,
    val frameTimesMs: FloatArray,   // raw per-frame deltas, ms. Transient.
) {
    /** Percentile helpers operate on a sorted copy. */
    fun summarize(downsampleTo: Int = 600): GpuFrameSummary =
        GpuFrameSummary.from(avgFps, frameTimesMs, downsampleTo)
}

/** Persisted, compact GPU detail. All nullable-friendly via KernelScores. */
@Serializable
data class GpuFrameSummary(
    val avgFps: Double,
    val avgFrameMs: Double,
    val p50Fps: Double,        // median-frame-time -> FPS
    val p1LowFps: Double,      // 99th-percentile frame time -> FPS (the "1% low")
    val p99FrameMs: Double,    // 99th-percentile frame time
    val consistencyPct: Double,// 100 * p50FrameMs / p99FrameMs  (100 = perfectly even)
    val frameTimesMsDownsampled: List<Float>, // <=600 pts for the chart
) {
    companion object {
        fun from(avgFps: Double, frames: FloatArray, downsampleTo: Int): GpuFrameSummary {
            if (frames.isEmpty()) return GpuFrameSummary(avgFps, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
            val sorted = frames.copyOf().also { it.sort() }
            val avgMs = frames.average()
            val p50Ms = percentile(sorted, 50.0)
            val p99Ms = percentile(sorted, 99.0)
            val p50Fps = if (p50Ms > 0) 1000.0 / p50Ms else 0.0
            val p1LowFps = if (p99Ms > 0) 1000.0 / p99Ms else 0.0
            val consistency = if (p99Ms > 0) (p50Ms / p99Ms * 100.0).coerceIn(0.0, 100.0) else 0.0
            return GpuFrameSummary(
                avgFps = avgFps,
                avgFrameMs = avgMs,
                p50Fps = p50Fps,
                p1LowFps = p1LowFps,
                p99FrameMs = p99Ms,
                consistencyPct = consistency,
                frameTimesMsDownsampled = downsample(frames, downsampleTo),
            )
        }

        /** Linear-interpolated percentile on an ASCENDING-sorted array.
         *  p in [0,100]. */
        fun percentile(sortedAsc: FloatArray, p: Double): Double {
            if (sortedAsc.isEmpty()) return 0.0
            if (sortedAsc.size == 1) return sortedAsc[0].toDouble()
            val rank = (p / 100.0) * (sortedAsc.size - 1)
            val lo = rank.toInt()
            val hi = (lo + 1).coerceAtMost(sortedAsc.size - 1)
            val frac = rank - lo
            return sortedAsc[lo] + (sortedAsc[hi] - sortedAsc[lo]) * frac
        }

        /** Uniform stride downsample preserving first+last. */
        fun downsample(src: FloatArray, target: Int): List<Float> {
            if (src.size <= target) return src.toList()
            val out = ArrayList<Float>(target)
            val stride = src.size.toDouble() / target
            var i = 0.0
            while (out.size < target) { out.add(src[i.toInt().coerceAtMost(src.size - 1)]); i += stride }
            return out
        }
    }
}
