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

    /**
     * Weighted composite. Fixed-weight formula so cross-flavor scores are
     * honest: a QUICK run (only cpuIntegerSingle) produces a lower score
     * than a STANDARD run (all kernels) because the missing kernels
     * contribute 0 to the numerator while the denominator stays fixed at
     * the full kernel weight sum (4.6). Do NOT compare QUICK vs STANDARD
     * scores — they measure different things.
     *
     * Kernel weights and scale factors (chosen so typical values land in
     * the same 1 000–15 000 range):
     *   cpuIntegerSingle × 1.0   (raw score ~1 000–15 000)
     *   cpuIntegerMulti  × 1.0   (raw score ~4 000–60 000 — multi inflates naturally)
     *   cpuFloat         × 1.0   (raw score ~1 000–15 000)
     *   cpuAes           × 0.5   (raw score ~2 000–30 000, halved to match integer)
     *   memoryBandwidthMBps × 0.1 (raw ~30 000–80 000 MB/s → 3 000–8 000 in composite)
     *   gpuFps           × 100.0 (raw FPS 20–120 → 2 000–12 000 in composite)
     *
     * Returns null when no kernel ran at all (e.g. FAILED_NATIVE outcome).
     */
    val overallScore: Long?
        get() {
            // Fixed weight sum. All six slots always participate in the denominator;
            // null kernels contribute 0 so the score is always comparable within a flavor.
            val WEIGHT_TOTAL = 1.0 + 1.0 + 1.0 + 0.5 + 0.1 + 1.0  // = 4.6
            val weightedSum =
                (kernels.cpuIntegerSingle?.let { it * 1.0 } ?: 0.0) +
                (kernels.cpuIntegerMulti?.let  { it * 1.0 } ?: 0.0) +
                (kernels.cpuFloat?.let         { it * 1.0 } ?: 0.0) +
                (kernels.cpuAes?.let           { it * 0.5 } ?: 0.0) +
                (kernels.memoryBandwidthMBps?.let { it * 0.1 } ?: 0.0) +
                (kernels.gpuFps?.let           { it * 100.0 } ?: 0.0)
            // Return null only when truly nothing ran.
            val anyRan =
                kernels.cpuIntegerSingle != null || kernels.cpuIntegerMulti != null ||
                kernels.cpuFloat != null || kernels.cpuAes != null ||
                kernels.memoryBandwidthMBps != null || kernels.gpuFps != null
            if (!anyRan) return null
            return (weightedSum / WEIGHT_TOTAL).toLong()
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
    // GPU frame-time detail (nullable + default-null so old rows
    // deserialize fine under ignoreUnknownKeys + defaults):
    val gpuAvgFrameMs: Double? = null,
    val gpuP50Fps: Double? = null,
    val gpuP1LowFps: Double? = null,
    val gpuP99FrameMs: Double? = null,
    val gpuFrameConsistencyPct: Double? = null,
    /** Downsampled (<=600) per-frame time series in ms, for the chart. */
    val gpuFrameTimesMs: List<Float>? = null,
    /**
     * JSON-serialised [GpuSceneResult] from the heavy 3D scene benchmark,
     * or null when the scene test did not run / is not yet supported for
     * this flavor. Null-default keeps this field JSON-safe: old rows that
     * lack it deserialise as null with ignoreUnknownKeys — no DB bump needed.
     *
     * Persistence strategy: rides inside the existing kernelsJson column.
     * The result is serialised separately here (rather than embedding the
     * full GpuSceneResult type inline) so the kernelsJson column stays
     * backward-compatible — callers that do not know about this field
     * simply ignore it. Decode with:
     *   json.decodeFromString<GpuSceneResult>(kernels.sceneJson!!)
     */
    val sceneJson: String? = null,

    /**
     * Sequential read speed from the storage tester (MB/s), or null when
     * the storage phase did not run (QUICK, SCENE_3D) or timed out.
     * Nullable addition — rides in kernelsJson; no DB bump needed.
     */
    val storageReadMBps: Double? = null,
)

@Serializable
enum class BenchFlavor {
    QUICK,
    STANDARD,
    FULL,
    /**
     * Standalone heavy 3D scene benchmark. Runs the [GpuSceneBenchmark]
     * sustained-loop renderer at the EXTREME (1440p) tier and returns the
     * result encoded in [KernelScores.sceneJson]. No CPU kernels run; the
     * focus is GPU-only sustained load + thermal stability.
     *
     * Honesty caption: "Our own benchmark — compare your own runs, not
     * other chips." Results are NOT comparable to 3DMark or AnTuTu.
     */
    SCENE_3D,
}

@Serializable
enum class BenchOutcome {
    COMPLETED,
    ABORTED_TEMP,
    /**
     * The battery got TOO HOT (temperature ≥ 45 °C) during the sustained
     * throttle test. This is a THERMAL condition on the battery, not a
     * low-charge condition. Renamed from the legacy ABORTED_BATTERY value
     * for honesty; ABORTED_BATTERY is kept below for DB backward-compatibility
     * (rows written before this rename deserialise without crashing).
     */
    ABORTED_BATTERY_TEMP,
    /** Legacy name kept for backward-compatibility with persisted DB rows. Do
     *  not use in new code — emit [ABORTED_BATTERY_TEMP] instead. */
    ABORTED_BATTERY,
    /**
     * Battery charge dropped below 15% while NOT charging during the sustained
     * run. This is a CHARGE LEVEL condition, completely distinct from
     * [ABORTED_BATTERY_TEMP] (which is a temperature condition). Only emitted
     * when a real percent reading is available — never emitted on a null read.
     */
    ABORTED_BATTERY_LOW,
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
    val gpuMaxMhz: Int? = null,  // GPU frequency in MHz; gpuFreqHz from Telemetry / 1_000_000
    /**
     * Battery state-of-charge in whole percent (0–100), or null when the
     * device does not expose this property. Nullable default keeps this
     * JSON-safe: old rows that lack the field deserialise as null with
     * ignoreUnknownKeys — no DB version bump required (rides inside the
     * existing throttleSamplesJson / samplesJson column).
     *
     * HONESTY: null means "unavailable" — never substitute a default value
     * here and never abort a run based on a null reading.
     */
    val batteryPercent: Int? = null,
    /**
     * Whether the battery was charging at sample time. Null when unknown.
     * Same JSON-safe nullable addition as [batteryPercent] — no DB bump.
     */
    val charging: Boolean? = null,
)
