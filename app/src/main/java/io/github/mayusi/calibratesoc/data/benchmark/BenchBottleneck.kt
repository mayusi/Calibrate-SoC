package io.github.mayusi.calibratesoc.data.benchmark

/**
 * Bottleneck diagnosis — the #1 "why" feature.
 *
 * Uses ONLY existing measured data (no invented numbers):
 *   - cpuUsageDuringGpuPct  : aggregate /proc/stat busy% while GPU bench ran
 *   - cpuDrawCallFps        : how fast the CPU can submit GLES draw commands
 *   - gpuFps                : average GPU frame rate from the triangle storm
 *   - memoryBandwidthMBps   : RAM throughput from mem-triad kernel
 *   - ThrottleAnalysis.dropPct / timeToThrottleMs : FULL-run sustained data
 *
 * Rules (mutually exclusive, ordered by priority):
 *   1. THERMAL_THROTTLE  — dropPct >= 15% AND timeToThrottleMs <= 30 s
 *   2. CPU_DRAW_CALL     — cpuUsageDuringGpuPct >= 70%
 *                          OR (cpuDrawCallFps != null AND gpuFps != null
 *                              AND cpuDrawCallFps < gpuFps * 1.5)
 *   3. GPU_BOUND         — cpuUsageDuringGpuPct < 40% (default confident case)
 *   4. MEMORY_BOUND      — memBw < 20_000 MB/s (rough DDR4/5 floor; context only)
 *   5. BALANCED          — fallback when data is absent / rules don't fire
 *
 * All thresholds are documented; callers can see exactly which numbers
 * fired. The verdict is self-relative — no cross-device implication.
 */
object BenchBottleneck {

    enum class BottleneckType {
        GPU_BOUND,
        CPU_DRAW_CALL,
        THERMAL_THROTTLE,
        MEMORY_BOUND,
        BALANCED,
    }

    /**
     * Full bottleneck verdict.
     *
     * @param type         Which category fired.
     * @param headline     One-sentence plain-English summary.
     * @param detail       2–3 sentence explanation of the evidence.
     * @param knob         What to change to move the number (tuning advice).
     * @param confidence   HIGH / MEDIUM / LOW — reflects data availability.
     */
    data class BottleneckVerdict(
        val type: BottleneckType,
        val headline: String,
        val detail: String,
        val knob: String,
        val confidence: Confidence,
    ) {
        enum class Confidence { HIGH, MEDIUM, LOW }
    }

    /**
     * Diagnose the bottleneck from a completed run's kernel scores and
     * optional throttle analysis.
     *
     * All parameters are nullable — the function degrades gracefully when
     * only a subset of kernels ran (e.g. QUICK has no GPU data).
     */
    fun diagnose(
        kernels: KernelScores,
        throttle: ThrottleAnalysis?,
    ): BottleneckVerdict {
        val cpuPct       = kernels.cpuUsageDuringGpuPct
        val drawCallFps  = kernels.cpuDrawCallFps
        val gpuFps       = kernels.gpuFps
        val memBw        = kernels.memoryBandwidthMBps

        // ── Rule 1: Thermal throttle (FULL runs) ─────────────────────────
        // Fires first — throttle masks everything else.
        if (throttle != null && throttle.dropPct >= 15.0) {
            val ttSec = throttle.timeToThrottleMs?.let { "%.0f s".format(it / 1000.0) } ?: "quickly"
            val headroom = throttle.thermalHeadroomC?.let { "%.1f°C".format(it) }
            val headStr  = if (headroom != null) " (only $headroom below kill temp)" else ""
            return BottleneckVerdict(
                type      = BottleneckType.THERMAL_THROTTLE,
                headline  = "Thermal throttle is capping your sustained score.",
                detail    = "The CPU dropped ${throttle.dropPct.toInt()}% (peak ${throttle.peakMhz} → " +
                    "sustained ${throttle.sustainedMhz} MHz) after $ttSec under sustained load$headStr. " +
                    "A tune that lowers the sustained freq floor costs less score but runs much cooler — " +
                    "if your gaming sessions last >1 min, the sustained floor matters more than the peak burst.",
                knob      = "Lower the sustained or max freq cap to reduce heat; or raise the throttle floor " +
                    "to keep clocks up longer at the cost of more heat. Use Full benchmark to compare.",
                confidence = if (throttle.timeToThrottleMs != null) BottleneckVerdict.Confidence.HIGH
                             else BottleneckVerdict.Confidence.MEDIUM,
            )
        }

        // ── Rule 2: CPU draw-call / CPU-limited GPU ──────────────────────
        // Two sub-signals: aggregate CPU busy% during GPU bench is high,
        // OR draw-call ceiling is close to (or below) GPU FPS.
        val cpuBusy    = cpuPct != null && cpuPct >= 70
        val dcTight    = drawCallFps != null && gpuFps != null && drawCallFps < gpuFps * 1.5
        if (cpuBusy || dcTight) {
            val cpuStr  = cpuPct?.let { "CPU was $it% busy during the GPU test" }
            val dcStr   = if (drawCallFps != null && gpuFps != null)
                "draw-call ceiling (${drawCallFps.toInt()} calls/s) is only ${(drawCallFps / gpuFps.coerceAtLeast(1.0)).let { "%.1fx".format(it) }} above GPU FPS"
                else null
            val evidence = listOfNotNull(cpuStr, dcStr).joinToString("; ")
            return BottleneckVerdict(
                type      = BottleneckType.CPU_DRAW_CALL,
                headline  = "The CPU is the bottleneck for your GPU workload.",
                detail    = "Evidence: $evidence. " +
                    "At this headroom the GPU is waiting on the CPU to submit draw commands. " +
                    "Raising CPU clock / using a more aggressive governor will help the GPU score more than raising GPU freq.",
                knob      = "Raise the CPU clock or switch to a performance governor. " +
                    "If draw-call rate is close to GPU FPS, the CPU can't feed the GPU fast enough — " +
                    "CPU clock matters more than GPU clock here.",
                confidence = if (cpuBusy && dcTight) BottleneckVerdict.Confidence.HIGH
                             else BottleneckVerdict.Confidence.MEDIUM,
            )
        }

        // ── Rule 3: GPU-bound (primary happy path) ───────────────────────
        if (cpuPct != null && cpuPct < 40) {
            return BottleneckVerdict(
                type      = BottleneckType.GPU_BOUND,
                headline  = "Your GPU clock is the ceiling — the CPU is not in the way.",
                detail    = "CPU was only $cpuPct% busy during the GPU test, so the CPU is not stalling the GPU. " +
                    "The GPU's fill rate and shader throughput are the limiting factor. " +
                    "Raising GPU freq (if your tune allows it) will improve this score directly.",
                knob      = "Raise GPU clock or boost GPU governor aggressiveness. CPU clock has little effect here.",
                confidence = BottleneckVerdict.Confidence.HIGH,
            )
        }

        // ── Rule 4: Memory-bound hint ────────────────────────────────────
        if (memBw != null && memBw < 20_000.0) {
            return BottleneckVerdict(
                type      = BottleneckType.MEMORY_BOUND,
                headline  = "Low memory bandwidth may be limiting both CPU and GPU.",
                detail    = "RAM bandwidth measured at ${"%.0f".format(memBw)} MB/s, " +
                    "which is below typical LPDDR5 throughput. " +
                    "Both the CPU and GPU share this bus — texture fetches and cache misses " +
                    "can stall both cores and the GPU's sampler units.",
                knob      = "Memory frequency tuning (if available) may help. " +
                    "Compare this score before/after changing memory-bus settings.",
                confidence = BottleneckVerdict.Confidence.LOW,
            )
        }

        // ── Rule 5: Balanced / no data ───────────────────────────────────
        val hasGpuData = cpuPct != null || drawCallFps != null
        return BottleneckVerdict(
            type      = BottleneckType.BALANCED,
            headline  = if (hasGpuData) "Load is balanced across CPU and GPU."
                        else "Run Standard or Full for a bottleneck diagnosis.",
            detail    = if (hasGpuData)
                "CPU usage during the GPU test ($cpuPct%) sits in the middle range — " +
                    "neither the CPU nor the GPU is clearly dominant. " +
                    "Both CPU and GPU clock changes may help roughly equally."
                else
                    "Quick benchmarks run CPU kernels only — " +
                        "bottleneck diagnosis needs GPU data from a Standard or Full run.",
            knob      = if (hasGpuData)
                "Try raising GPU clock first (it's the render bottleneck for most games), " +
                    "then CPU clock if score doesn't improve."
                else
                    "Run a Standard benchmark to get GPU data for bottleneck diagnosis.",
            confidence = BottleneckVerdict.Confidence.LOW,
        )
    }
}
