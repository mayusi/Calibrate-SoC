package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.monitor.BatteryChargeReader
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
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
    private val batteryChargeReader: BatteryChargeReader,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // BUG 11: mutex prevents two concurrent run() calls from both passing the
    // `is Idle` check before either writes Running (TOCTOU race).
    private val runMutex = Mutex()

    suspend fun run(
        loopCount: Int = 6,
        loopMs: Long = 20_000L,
        killTempC: Float = 95f,
    ): StabilityResult = coroutineScope {
        if (!runMutex.tryLock()) error("Stability test already in progress")
        try {
            check(_state.value is State.Idle) { "Stability test already in progress (state)" }
        } catch (e: IllegalStateException) {
            runMutex.unlock()
            throw e
        }

        val startedAt = System.currentTimeMillis()
        val samples = mutableListOf<ThrottleSample>()
        val loopFps = mutableListOf<Double>()
        var outcome = BenchOutcome.COMPLETED

        // CPU work jobs — peg N-1 cores on a dedicated pool so Dispatchers.Default
        // (used by the GPU storm + orchestration loop) is never starved.
        val cpuThreads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val cpuDispatcher = kotlinx.coroutines.newFixedThreadPoolContext(cpuThreads, "stability-cpu")
        val cpuJobs = (0 until cpuThreads).map {
            launch(cpuDispatcher) {
                val iterations = 2000  // inner iterations for NativeBench.runCpu
                while (isActive) {
                    runCatching { NativeBench.runCpu(iterations) }
                    yield()  // cooperative: let cancellation propagate + free the thread briefly
                }
            }
        }

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
                // Use heavier GPU stress variant (256 iterations vs baseline 80)
                val fps = gpuStorm.runStress(loopMs, shaderIterations = 256) ?: 0.0
                loopFps += fps

                // After each loop, honor the thermal kill switch.
                val lastTemp = samples.lastOrNull()?.cpuMaxTempC ?: 0f
                if (lastTemp >= killTempC) {
                    outcome = BenchOutcome.ABORTED_TEMP
                    break
                }
                // Battery low abort — INDEPENDENT of the thermal abort above.
                // Only aborts on a REAL percent reading; null = unavailable = no abort.
                val lastSample = samples.lastOrNull()
                if (shouldAbortLowBattery(lastSample?.batteryPercent, lastSample?.charging)) {
                    outcome = BenchOutcome.ABORTED_BATTERY_LOW
                    break
                }
            }
        } finally {
            cpuJobs.forEach { it.cancel() }
            samplerJob.cancel()
            runCatching { cpuDispatcher.close() }  // release the dedicated thread pool
            _state.value = State.Idle
            runMutex.unlock()   // release the single-flight guard (BUG 11)
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

    private fun sampleFromTelemetry(t: Telemetry, runStartedAt: Long): ThrottleSample =
        telemetryToThrottleSample(
            t = t,
            runStartedAt = runStartedAt,
            batteryPercent = batteryChargeReader.readPercent(),
            charging = batteryChargeReader.isCharging(),
        )

    sealed interface State {
        data object Idle : State
        data class Running(val loopIndex: Int, val loopCount: Int, val progress: Float) : State
    }
}
