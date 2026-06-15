package io.github.mayusi.calibratesoc.data.benchmark

/** Derived sustained-load signals from throttleSamples. All from existing
 *  ThrottleSample data (FULL runs). killTempC defaults to BenchConfig.killTempC (85).
 *
 *  Power/energy use ThrottleSample.batteryDrawMw directly (NO abs): the
 *  monitor extension `Telemetry.batteryDrawMilliW` already normalizes to
 *  "positive = discharging", which is exactly the benchmark case. */
data class ThrottleAnalysis(
    val startMhz: Int,            // first sample clock (kept for compatibility; use peakMhz for throttle math)
    val sustainedMhz: Int,        // avg of last 25% of samples
    val endMhz: Int,
    val peakMhz: Int,             // max cpuMaxMhz across all samples — true reference for throttle math
    val dropPct: Double,          // 100 * (peakMhz - sustainedMhz)/peakMhz, clamped >= 0
    val timeToThrottleMs: Long?,  // ms the chip held ≥95% of peak AFTER ramp-up before dropping; null if never throttled
    val peakCpuTempC: Float,
    val peakGpuTempC: Float?,
    val thermalHeadroomC: Float?, // killTempC - peakCpuTempC
    val avgPowerMw: Double?,      // mean batteryDrawMw over samples that report it
    val energyMwh: Double?,       // trapezoid integral of mW over elapsed deltas / 3.6e6
) {
    companion object {
        fun from(samples: List<ThrottleSample>, killTempC: Float = 85f): ThrottleAnalysis? {
            if (samples.isEmpty()) return null
            val startMhz = samples.first().cpuMaxMhz
            val endMhz = samples.last().cpuMaxMhz

            // Peak = highest clock the device actually reached under load (not the idle first sample).
            val peakMhz = samples.maxOf { it.cpuMaxMhz }

            val tailFrom = (samples.size * SUSTAINED_WINDOW_RATIO).toInt().coerceAtMost(samples.size - 1)
            val sustainedMhz = samples.drop(tailFrom).map { it.cpuMaxMhz }.average().toInt()

            // Drop is peak → sustained, clamped to 0 so a ramp-up-then-hold reads 0%, never negative.
            val dropPct = if (peakMhz > 0) ((peakMhz - sustainedMhz) * 100.0 / peakMhz).coerceAtLeast(0.0) else 0.0

            // Time-to-throttle: find when clocks first reach ≥90% of peak (ramp-up complete),
            // then find the first sample after that point where clocks drop to ≤95% of peak.
            // This prevents the idle ramp-up from being misread as a throttle event.
            //
            // BUG 6 fix: return the DELTA from the ramp-up point, not the absolute elapsed
            // from run start. Semantic: "held peak for X ms before dropping", not "first drop
            // at Xms absolute". KDoc and UI label updated to match.
            val rampThreshold = peakMhz * 0.90
            val throttleThreshold = peakMhz * 0.95
            val rampedIndex = samples.indexOfFirst { it.cpuMaxMhz >= rampThreshold }
            val timeToThrottleMs = if (rampedIndex >= 0) {
                val rampElapsedMs = samples[rampedIndex].elapsedMs
                samples.drop(rampedIndex + 1)
                    .firstOrNull { it.cpuMaxMhz <= throttleThreshold }
                    ?.let { it.elapsedMs - rampElapsedMs }
            } else null

            val peakCpuTempC = samples.maxOf { it.cpuMaxTempC }
            val peakGpuTempC = samples.mapNotNull { it.gpuTempC }.maxOrNull()
            val headroom = killTempC - peakCpuTempC

            val pw = samples.mapNotNull { it.batteryDrawMw }
            val avgPowerMw = pw.takeIf { it.isNotEmpty() }?.average()

            // Energy integral via trapezoid over real elapsed deltas.
            // mWh = sum( avgMw_segment * dt_ms ) / 3_600_000 ms-per-hour.
            // batteryDrawMw is already positive when discharging — no abs().
            var energyMwh: Double? = null
            val withPw = samples.filter { it.batteryDrawMw != null }
            if (withPw.size >= 2) {
                var acc = 0.0
                for (i in 1 until withPw.size) {
                    val dtMs = (withPw[i].elapsedMs - withPw[i - 1].elapsedMs).coerceAtLeast(0)
                    val avgMw = (withPw[i].batteryDrawMw!! + withPw[i - 1].batteryDrawMw!!) / 2.0
                    acc += avgMw * dtMs
                }
                energyMwh = acc / 3_600_000.0
            }

            return ThrottleAnalysis(
                startMhz, sustainedMhz, endMhz, peakMhz, dropPct, timeToThrottleMs,
                peakCpuTempC, peakGpuTempC, headroom, avgPowerMw, energyMwh,
            )
        }
    }
}
