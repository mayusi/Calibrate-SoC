package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW

/**
 * Pure mapper: [Telemetry] → [ThrottleSample].
 *
 * Extracted from the identical private `sampleFromTelemetry` copies that
 * previously lived in both [BenchmarkRunner] and [StabilityTestRunner].
 * Keeping the mapping in one place ensures both runners always produce
 * identical [ThrottleSample] values from the same telemetry input.
 *
 * No Android framework dependencies — pure JVM, trivially unit-testable.
 */
internal fun telemetryToThrottleSample(t: Telemetry, runStartedAt: Long): ThrottleSample {
    val cpuMaxMhz = (t.perCoreCpuFreqKhz.maxOrNull() ?: 0) / 1000
    val cpuTempC = t.zoneTempsMilliC
        .filter { it.label.contains("cpu", ignoreCase = true) }
        .maxOfOrNull { it.tempMilliC / 1000f } ?: 0f
    val gpuTempC = t.zoneTempsMilliC
        .filter { it.label.contains("gpu", ignoreCase = true) || it.label.contains("kgsl", ignoreCase = true) }
        .maxOfOrNull { it.tempMilliC / 1000f }
    val gpuMaxMhz = t.gpuFreqHz?.let { (it / 1_000_000L).toInt() }
    val batteryTempC = (t.batteryTempDeciC ?: 0) / 10f
    return ThrottleSample(
        elapsedMs = System.currentTimeMillis() - runStartedAt,
        cpuMaxMhz = cpuMaxMhz,
        cpuMaxTempC = cpuTempC,
        gpuTempC = gpuTempC,
        batteryTempC = batteryTempC,
        batteryDrawMw = t.batteryDrawMilliW,
        gpuMaxMhz = gpuMaxMhz,
    )
}
