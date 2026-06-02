package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sustained GPU stability runner (3DMark Wild Life Extreme Stress-test
 * style). Loops the existing [GpuTriangleStorm] flat-out, back-to-back,
 * and reports a stability % = (lowest loop FPS / highest loop FPS) × 100.
 * 100% means performance held under sustained load; lower means the GPU
 * (or its thermal envelope) throttled as the run heated up.
 *
 * A telemetry sampler runs concurrently for the whole loop so the UI can
 * draw the thermal/throttle curve alongside the per-loop FPS curve.
 *
 * Single-flight: only one run executes at a time, mirroring
 * [BenchmarkRunner]'s structure.
 */
@Singleton
class StabilityTestRunner @Inject constructor(
    private val monitorService: MonitorService,
    private val gpuStorm: GpuTriangleStorm,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun run(
        loopCount: Int = 20,
        loopMs: Long = 30_000L,
        killTempC: Float = 90f,
    ): StabilityResult = coroutineScope {
        check(_state.value is State.Idle) { "Stability test already in progress" }

        val startedAt = System.currentTimeMillis()
        val samples = mutableListOf<ThrottleSample>()
        val loopFps = mutableListOf<Double>()
        var outcome = BenchOutcome.COMPLETED

        // Concurrent telemetry sampler — collects until cancelled.
        val samplerJob = launch(Dispatchers.IO) {
            monitorService.telemetry(MonitorService.STRESS_INTERVAL_MS).collect { telemetry ->
                samples += sampleFromTelemetry(telemetry, startedAt)
            }
        }

        try {
            for (i in 0 until loopCount) {
                _state.value = State.Running(
                    loopIndex = i,
                    loopCount = loopCount,
                    progress = i.toFloat() / loopCount,
                )
                val fps = gpuStorm.run(loopMs) ?: 0.0
                loopFps += fps

                // After each loop, honor the thermal kill switch.
                val lastTemp = samples.lastOrNull()?.cpuMaxTempC ?: 0f
                if (lastTemp >= killTempC) {
                    outcome = BenchOutcome.ABORTED_TEMP
                    break
                }
            }
        } finally {
            samplerJob.cancel()
            _state.value = State.Idle
        }

        StabilityResult(
            loopFps = loopFps.toList(),
            stabilityPct = StabilityResult.compute(loopFps),
            samples = samples.toList(),
            outcome = outcome,
            loopCount = loopCount,
            startedAtMs = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** Local copy of BenchmarkRunner's telemetry → ThrottleSample mapping;
     *  kept self-contained so this runner does not depend on BenchmarkRunner. */
    private fun sampleFromTelemetry(t: Telemetry, runStartedAt: Long): ThrottleSample {
        val cpuMaxMhz = (t.perCoreCpuFreqKhz.maxOrNull() ?: 0) / 1000
        val cpuTempC = t.zoneTempsMilliC
            .filter { it.label.contains("cpu", ignoreCase = true) }
            .maxOfOrNull { it.tempMilliC / 1000f } ?: 0f
        val gpuTempC = t.zoneTempsMilliC
            .filter { it.label.contains("gpu", ignoreCase = true) || it.label.contains("kgsl", ignoreCase = true) }
            .maxOfOrNull { it.tempMilliC / 1000f }
        val batteryTempC = (t.batteryTempDeciC ?: 0) / 10f
        return ThrottleSample(
            elapsedMs = System.currentTimeMillis() - runStartedAt,
            cpuMaxMhz = cpuMaxMhz,
            cpuMaxTempC = cpuTempC,
            gpuTempC = gpuTempC,
            batteryTempC = batteryTempC,
            batteryDrawMw = t.batteryDrawMilliW,
        )
    }

    sealed interface State {
        data object Idle : State
        data class Running(val loopIndex: Int, val loopCount: Int, val progress: Float) : State
    }
}
