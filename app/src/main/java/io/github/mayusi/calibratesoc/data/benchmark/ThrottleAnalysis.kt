package io.github.mayusi.calibratesoc.data.benchmark

/** Derived sustained-load signals from throttleSamples. All from existing
 *  ThrottleSample data (FULL runs). killTempC defaults to BenchConfig.killTempC (85).
 *
 *  Power/energy use ThrottleSample.batteryDrawMw directly (NO abs): the
 *  monitor extension `Telemetry.batteryDrawMilliW` already normalizes to
 *  "positive = discharging", which is exactly the benchmark case. */
data class ThrottleAnalysis(
    val startMhz: Int,
    val sustainedMhz: Int,        // avg of last 25% of samples
    val endMhz: Int,
    val dropPct: Double,          // 100 * (startMhz - sustainedMhz)/startMhz
    val timeToThrottleMs: Long?,  // first elapsed where cpuMaxMhz <= 95% of startMhz; null if never
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
            val tailFrom = (samples.size * 0.75).toInt().coerceAtMost(samples.size - 1)
            val sustainedMhz = samples.drop(tailFrom).map { it.cpuMaxMhz }.average().toInt()
            val dropPct = if (startMhz > 0) (startMhz - sustainedMhz) * 100.0 / startMhz else 0.0

            val throttleThreshold = startMhz * 0.95
            val timeToThrottleMs = samples.firstOrNull { it.cpuMaxMhz <= throttleThreshold }?.elapsedMs

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
                startMhz, sustainedMhz, endMhz, dropPct, timeToThrottleMs,
                peakCpuTempC, peakGpuTempC, headroom, avgPowerMw, energyMwh,
            )
        }
    }
}
