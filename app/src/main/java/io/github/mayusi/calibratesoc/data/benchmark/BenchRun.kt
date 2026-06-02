package io.github.mayusi.calibratesoc.data.benchmark

import kotlinx.serialization.Serializable

/**
 * A complete benchmark run. Three flavors:
 *   - QUICK:    single-thread CPU only. ~30s. Cheap before/after check.
 *   - STANDARD: CPU int + float + AES + multi-thread + RAM + GPU. ~2 min.
 *               No throttle test. The "main" mode most users want.
 *   - FULL:     Standard + 10-min sustained throttle. ~12 min.
 *               The only one that surfaces the under-heat throttle floor.
 *
 * Each kernel's score is stored separately so the compare view can show
 * per-kernel deltas, not just one composite. The composite [overallScore]
 * is a weighted blend for top-line "did my tune help" comparison.
 */
@Serializable
data class BenchRun(
    val id: Long,
    val name: String,
    val flavor: BenchFlavor,
    val startedAtMs: Long,
    val durationMs: Long,
    val snapshot: SystemSnapshot,
    val kernels: KernelScores,
    val throttleSamples: List<ThrottleSample>,
    val outcome: BenchOutcome,
) {
    /** Backward-compat field for the dashboard's compare card — picks
     *  the integer CPU score as the headline number. */
    val cpuScore: Long? get() = kernels.cpuIntegerSingle

    /** Weighted composite. CPU (single + multi) accounts for half,
     *  GPU + memory + AES split the other half. Returns null when no
     *  kernel ran (e.g. all-failure outcome). */
    val overallScore: Long?
        get() {
            val parts = listOfNotNull(
                kernels.cpuIntegerSingle?.let { it * 1.0 },
                kernels.cpuIntegerMulti?.let { it * 1.0 },
                kernels.cpuFloat?.let { it * 1.0 },
                kernels.cpuAes?.let { it * 0.5 },
                kernels.memoryBandwidthMBps?.let { it * 10.0 },
                kernels.gpuFps?.let { it * 100.0 },
            )
            if (parts.isEmpty()) return null
            return parts.average().toLong()
        }
}

/** Per-kernel results. Every field nullable — a flavor that doesn't
 *  run a given kernel leaves its slot null. */
@Serializable
data class KernelScores(
    val cpuIntegerSingle: Long? = null,
    val cpuIntegerMulti: Long? = null,
    val cpuFloat: Long? = null,
    val cpuAes: Long? = null,
    val memoryBandwidthMBps: Double? = null,
    val gpuFps: Double? = null,
    /** CPU busy % averaged across all cores during the GPU bench.
     *  High = GPU bench was CPU-limited; low = pure GPU bottleneck. */
    val cpuUsageDuringGpuPct: Int? = null,
    /** CPU draw-call ceiling: synthetic FPS the CPU can issue + submit
     *  empty GLES draws at. Compare against gpuFps to see whether your
     *  workload would be CPU- or GPU-limited. */
    val cpuDrawCallFps: Double? = null,
)

@Serializable
enum class BenchFlavor { QUICK, STANDARD, FULL }

@Serializable
enum class BenchOutcome {
    COMPLETED,
    ABORTED_TEMP,
    ABORTED_BATTERY,
    ABORTED_DURATION,
    ABORTED_USER,
    FAILED_NATIVE,
}

/**
 * One sample during the sustained throttle test. Sampled at 4 Hz
 * (250 ms interval) for the full duration of the test.
 */
@Serializable
data class ThrottleSample(
    val elapsedMs: Long,
    val cpuMaxMhz: Int,
    val cpuMaxTempC: Float,
    val gpuTempC: Float?,
    val batteryTempC: Float,
    val batteryDrawMw: Long?,
)
