package io.github.mayusi.calibratesoc.data.benchmark

/** Same-device-relative category sub-scores. UNITLESS. NEVER cross-device.
 *  Each category is a blend of its kernels, scaled so typical values land in
 *  a readable 3-5 digit range like the composite. The ONLY honest use is
 *  comparing a user's own runs before/after a tune. */
data class BenchScores(
    val cpu: Long?,
    val gpu: Long?,
    val memory: Long?,
    val overall: Long?,   // mirrors BenchRun.overallScore for convenience
) {
    companion object {
        const val HONESTY = "Category scores are same-device only — compare your own runs, not other chips."

        fun from(run: BenchRun): BenchScores {
            val k = run.kernels

            // CPU: weighted blend matching overallScore's CPU weighting.
            // int1T*1 + intMT*1 + float*1 + AES*0.5, averaged over present parts.
            val cpuParts = listOfNotNull(
                k.cpuIntegerSingle?.let { it * 1.0 },
                k.cpuIntegerMulti?.let { it * 1.0 },
                k.cpuFloat?.let { it * 1.0 },
                k.cpuAes?.let { it * 0.5 },
            )
            val cpu = cpuParts.takeIf { it.isNotEmpty() }?.average()?.toLong()

            // GPU: avg FPS scaled (x100 like the composite) blended with the
            // 1% low (penalizes jitter) and a consistency multiplier.
            // gpuScore = ((avgFps*100)*0.6 + (p1LowFps*100)*0.4) * (consistency/100 clamped 0.5..1)
            val gpu = k.gpuFps?.let { avg ->
                val low = k.gpuP1LowFps ?: avg
                val cons = (k.gpuFrameConsistencyPct ?: 100.0) / 100.0
                val consMul = cons.coerceIn(0.5, 1.0)
                (((avg * 100.0) * 0.6 + (low * 100.0) * 0.4) * consMul).toLong()
            }

            // Memory: bandwidth scaled ×0.1 so 30 000–80 000 MB/s lands in the
            // same 3 000–8 000 range as CPU and GPU sub-scores. Matches the
            // composite weighting in BenchRun.overallScore (also ×0.1).
            val memory = k.memoryBandwidthMBps?.let { (it * 0.1).toLong() }

            return BenchScores(cpu = cpu, gpu = gpu, memory = memory, overall = run.overallScore)
        }
    }
}
