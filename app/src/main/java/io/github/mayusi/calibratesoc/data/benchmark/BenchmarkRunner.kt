package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.hardware.StorageSpeedTester
import io.github.mayusi.calibratesoc.data.monitor.BatteryChargeReader
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-kernel benchmark runner.
 *
 * Flavor menu:
 *   QUICK    — CPU integer single-thread only. ~20s. Lightweight
 *              before/after when iterating on a tune.
 *   STANDARD — CPU int single + CPU int multi (thread fanout) + CPU
 *              float + CPU AES + memory bandwidth + GPU triangle
 *              storm. ~1 minute. The "main" flavor that returns
 *              comparable numbers across all the workloads users
 *              actually care about.
 *   FULL     — STANDARD + 2-minute sustained throttle test. ~3
 *              minutes. The only flavor that surfaces the under-
 *              heat clock floor a tune actually delivers.
 *
 * Each kernel reports its own score; the composite is computed in
 * BenchRun.overallScore. Per-kernel scores let the compare view
 * show "GPU dropped 8% but CPU integer is the same" instead of
 * one blended number that hides the detail.
 *
 * Runner is single-flight; only one BenchRun executes at a time.
 * Native crashes go through [BenchOutcome.FAILED_NATIVE] rather
 * than crashing the dashboard process.
 */
@Singleton
class BenchmarkRunner @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val monitorService: MonitorService,
    private val gpuStorm: GpuTriangleStorm,
    private val gpuScene: GpuSceneBenchmark,
    private val storageTester: StorageSpeedTester,
    private val batteryChargeReader: BatteryChargeReader,
    private val json: Json,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun run(
        flavor: BenchFlavor,
        config: BenchConfig = BenchConfig(),
        appVersion: String,
        name: String = defaultName(flavor),
    ): BenchRun {
        check(_state.value is State.Idle) { "Benchmark already in progress" }

        val report = capabilityProbe.refresh()
        val startedAt = System.currentTimeMillis()
        val snapshot = SystemSnapshot.fromReport(report, appVersion)

        preflightCheck(config)?.let { abort ->
            _state.value = State.Idle
            return BenchRun(
                id = 0L,
                name = name,
                flavor = flavor,
                startedAtMs = startedAt,
                durationMs = 0,
                snapshot = snapshot,
                kernels = KernelScores(),
                throttleSamples = emptyList(),
                outcome = abort,
            )
        }

        val etaMs = when (flavor) {
            BenchFlavor.QUICK -> 20_000L
            // STANDARD now includes a short 3-loop scene phase (~45 s) + 1 s storage probe
            BenchFlavor.STANDARD -> 60_000L + config.stdScene3dLoopCount * config.stdScene3dLoopMs + 5_000L
            BenchFlavor.FULL -> 60_000L + config.stdScene3dLoopCount * config.stdScene3dLoopMs + 5_000L + config.throttleDurationMs
            BenchFlavor.SCENE_3D -> config.scene3dLoopCount * config.scene3dLoopMs
        }
        _state.value = State.Running(flavor, progress = 0f, etaMs = etaMs)

        try {
            val kernels = try {
                when (flavor) {
                    BenchFlavor.QUICK -> runQuick(config)
                    BenchFlavor.STANDARD, BenchFlavor.FULL -> runStandard(config)
                    BenchFlavor.SCENE_3D -> runScene3D(config)
                }
            } catch (t: UnsatisfiedLinkError) {
                _state.value = State.Idle
                return BenchRun(
                    id = 0L,
                    name = name,
                    flavor = flavor,
                    startedAtMs = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt,
                    snapshot = snapshot,
                    kernels = KernelScores(),
                    throttleSamples = emptyList(),
                    outcome = BenchOutcome.FAILED_NATIVE,
                )
            }

            var throttleSamples: List<ThrottleSample> = emptyList()
            var outcome = BenchOutcome.COMPLETED

            if (flavor == BenchFlavor.FULL) {
                val result = runThrottleTest(config)
                throttleSamples = result.samples
                outcome = result.outcome
            }

            return BenchRun(
                id = 0L,
                name = name,
                flavor = flavor,
                startedAtMs = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                snapshot = snapshot,
                kernels = kernels,
                throttleSamples = throttleSamples,
                outcome = outcome,
            )
        } finally {
            // Always reset state to Idle — covers both normal completion and
            // coroutine cancellation triggered by the user hitting Cancel.
            _state.value = State.Idle
        }
    }

    private fun defaultName(flavor: BenchFlavor): String {
        val ts = java.text.SimpleDateFormat("MMM d • HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "${flavor.name.lowercase().replaceFirstChar { it.uppercase() }} run — $ts"
    }

    // --- Flavor implementations -----------------------------------

    /** Loop [oneShot] until [budgetMs] of wall-clock elapses, summing the
     *  returned scores. Always runs at least once. Fixed wall-clock makes
     *  benchmark duration chip-independent — a faster chip simply accumulates
     *  more iterations (and a higher score) in the same time. */
    private inline fun runForBudget(budgetMs: Long, oneShot: () -> Long): Long {
        val deadline = System.currentTimeMillis() + budgetMs
        var total = 0L
        do { total += oneShot() } while (System.currentTimeMillis() < deadline)
        return total
    }

    private suspend fun runQuick(config: BenchConfig): KernelScores =
        withContext(Dispatchers.Default) {
            KernelScores(
                cpuIntegerSingle = runForBudget(config.quickCpuBudgetMs) {
                    NativeBench.runCpu(config.cpuIterations)
                },
            )
        }

    /**
     * STANDARD: every kernel sequentially. Multi-threaded CPU is
     * dispatched as `cores` parallel coroutines each calling runCpu;
     * the score is the sum (rough Multi-Thread proxy — equivalent
     * to what Geekbench reports as "MT integer"). GPU storm runs at
     * the end so any GPU init failures don't sabotage the CPU
     * numbers.
     */
    private suspend fun runStandard(config: BenchConfig): KernelScores =
        coroutineScope {
            val cpuSingle = withContext(Dispatchers.Default) {
                runForBudget(config.stdCpuSingleBudgetMs) {
                    NativeBench.runCpu(config.cpuIterations)
                }
            }
            val cpuMulti = withContext(Dispatchers.Default) {
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val deferreds = (0 until cores).map {
                    async(Dispatchers.Default) {
                        runForBudget(config.stdCpuMultiBudgetMs) {
                            NativeBench.runCpu(config.cpuIterations)
                        }
                    }
                }
                deferreds.awaitAll().sum()
            }
            val cpuFloat = withContext(Dispatchers.Default) {
                runForBudget(config.stdFloatBudgetMs) {
                    NativeBench.runFloat(config.floatIterations)
                }
            }
            val cpuAes = withContext(Dispatchers.Default) {
                runForBudget(config.stdAesBudgetMs) {
                    NativeBench.runAes(config.aesIterations)
                }
            }
            val memBw = withContext(Dispatchers.Default) {
                val deadline = System.currentTimeMillis() + config.stdMemBudgetMs
                var last = 0.0; var n = 0
                do { last = NativeBench.runMemTriad(config.memArrayMb, config.memIters) / 1000.0; n++ }
                while (System.currentTimeMillis() < deadline)
                last  // bandwidth is a rate; keep the final stable reading, don't accumulate
            }
            // GPU bench with concurrent CPU-busy sampling. The sampler reads
            // aggregate /proc/stat busy% deltas — high values mean the GPU
            // bench was CPU-limited rather than truly GPU-bound.
            val cpuDuringGpu = CpuBusySampler()
            val samplerJob = launch(Dispatchers.IO) { cpuDuringGpu.sampleWhileActive() }
            val gpuResult = runCatching { gpuStorm.runDetailed(config.gpuDurationMs) }.getOrNull()
            val gpuSummary = gpuResult?.summarize(downsampleTo = 600)
            samplerJob.cancel()
            val cpuUsageDuringGpuPct = cpuDuringGpu.averagePct()

            val drawCallFps = withContext(Dispatchers.Default) {
                runCatching { gpuStorm.runDrawCallCeiling(config.drawCallDurationMs) }
                    .getOrNull()
            }

            // ── Short 3D scene phase (P2 integration into STANDARD) ────────
            // 3 loops × 15 s = ~45 s extra. Serialised into sceneJson so the
            // Scene3DResultCard renders inline. Graceful: null on EGL failure.
            val sceneResult = runCatching {
                gpuScene.run(
                    requestedTier = SceneTier.EXTREME,
                    loopCount = config.stdScene3dLoopCount,
                    loopMs = config.stdScene3dLoopMs,
                    killTempC = config.killTempC,
                )
            }.getOrNull()
            val sceneJson = sceneResult?.let { json.encodeToString(it) }

            // ── Quick sequential-read storage probe ────────────────────────
            // Best-effort: 1-second sequential read only (no full 4-pass test).
            val storageReadMBps = runCatching { storageTester.quickSeqRead() }.getOrNull()

            KernelScores(
                cpuIntegerSingle = cpuSingle,
                cpuIntegerMulti = cpuMulti,
                cpuFloat = cpuFloat,
                cpuAes = cpuAes,
                memoryBandwidthMBps = memBw,
                gpuFps = gpuSummary?.avgFps,
                cpuUsageDuringGpuPct = cpuUsageDuringGpuPct,
                cpuDrawCallFps = drawCallFps,
                gpuAvgFrameMs = gpuSummary?.avgFrameMs,
                gpuP50Fps = gpuSummary?.p50Fps,
                gpuP1LowFps = gpuSummary?.p1LowFps,
                gpuP99FrameMs = gpuSummary?.p99FrameMs,
                gpuFrameConsistencyPct = gpuSummary?.consistencyPct,
                gpuFrameTimesMs = gpuSummary?.frameTimesMsDownsampled,
                sceneJson = sceneJson,
                storageReadMBps = storageReadMBps,
            )
        }

    /**
     * Heavy 3D scene benchmark flavor.
     *
     * Runs [GpuSceneBenchmark] at the EXTREME (1440p) tier, collecting
     * per-loop FPS and telemetry for stability%. The result is serialised
     * into [KernelScores.sceneJson] (nullable addition — no DB bump needed).
     *
     * Honesty: "Our own benchmark — compare your own runs, not other chips."
     */
    private suspend fun runScene3D(config: BenchConfig): KernelScores {
        val sceneResult = runCatching {
            gpuScene.run(
                requestedTier = SceneTier.EXTREME,
                loopCount = config.scene3dLoopCount,
                loopMs = config.scene3dLoopMs,
                killTempC = config.killTempC,
            )
        }.getOrNull()

        val sceneJson = sceneResult?.let { json.encodeToString(it) }
        return KernelScores(
            // GPU avg FPS also surfaced in the standard gpuFps slot so
            // the compare card and overall score can use it even without
            // knowing about sceneJson.
            gpuFps = sceneResult?.avgFps,
            gpuP1LowFps = sceneResult?.p1LowFps,
            gpuP50Fps = sceneResult?.p50Fps,
            gpuP99FrameMs = sceneResult?.p99FrameMs,
            gpuFrameConsistencyPct = sceneResult?.consistencyPct,
            gpuAvgFrameMs = sceneResult?.avgFrameMs,
            gpuFrameTimesMs = sceneResult?.frameTimesMsDownsampled,
            sceneJson = sceneJson,
        )
    }

    /**
     * Lightweight CPU-busy sampler. Reads aggregate /proc/stat at ~5 Hz
     * and tracks busy% across deltas. Used during the GPU bench so we
     * can tell users whether their GPU score was held back by the CPU.
     */
    private class CpuBusySampler {
        private val samples = mutableListOf<Int>()
        suspend fun sampleWhileActive() {
            var prev = readJiffies() ?: return
            while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                kotlinx.coroutines.delay(200)
                val cur = readJiffies() ?: continue
                val totalDelta = cur.total - prev.total
                val idleDelta = cur.idle - prev.idle
                if (totalDelta > 0) {
                    val busyPct = (100.0 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
                    samples += busyPct
                }
                prev = cur
            }
        }
        fun averagePct(): Int? = if (samples.isEmpty()) null else samples.average().toInt()

        private data class Jiffies(val total: Long, val idle: Long)
        private fun readJiffies(): Jiffies? {
            return try {
                val line = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.firstOrNull() != "cpu") return null
                val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (nums.size < 5) return null
                // Fields: user, nice, system, idle, iowait, irq, softirq, steal, ...
                val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
                Jiffies(total = nums.sum(), idle = idle)
            } catch (_: Throwable) {
                null
            }
        }
    }

    // --- Sustained-throttle test (unchanged from previous version) ---

    private suspend fun runThrottleTest(config: BenchConfig): ThrottleResult = coroutineScope {
        val samples = mutableListOf<ThrottleSample>()
        var outcome = BenchOutcome.COMPLETED
        val startedAt = System.currentTimeMillis()

        val workJob = launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { NativeBench.runCpu(config.throttleInnerIterations) }
            }
        }
        val samplerJob = launch(Dispatchers.IO) {
            monitorService.telemetry(MonitorService.STRESS_INTERVAL_MS).collect { telemetry ->
                samples += sampleFromTelemetry(telemetry, startedAt)
            }
        }
        val watchdog = withTimeoutOrNull(config.throttleDurationMs) {
            while (isActive) {
                delay(500)
                val last = samples.lastOrNull() ?: continue
                if (last.cpuMaxTempC >= config.killTempC) {
                    outcome = BenchOutcome.ABORTED_TEMP
                    return@withTimeoutOrNull
                }
                if (config.respectBatteryFloor && batteryTooHotDuringRun(last)) {
                    outcome = BenchOutcome.ABORTED_BATTERY_TEMP
                    return@withTimeoutOrNull
                }
                // Battery low abort — INDEPENDENT of the thermal abort above.
                // Only aborts on a REAL percent reading; null = unavailable = no abort.
                if (shouldAbortLowBattery(last.batteryPercent, last.charging)) {
                    outcome = BenchOutcome.ABORTED_BATTERY_LOW
                    return@withTimeoutOrNull
                }
            }
        }
        // watchdog == null is the NORMAL case: withTimeoutOrNull returns null
        // when throttleDurationMs elapses, which is exactly when the sustained
        // test is meant to finish. The inner loop only ever *returns* early to
        // signal a temp/battery kill (which already set `outcome`). So a null
        // watchdog with no kill set means the test ran its full duration =
        // COMPLETED. (Previously this was mislabeled ABORTED_DURATION, making
        // every healthy Full run read as "Aborted — time limit".)
        workJob.cancel()
        samplerJob.cancel()
        ThrottleResult(samples = samples.toList(), outcome = outcome)
    }

    private fun sampleFromTelemetry(t: Telemetry, runStartedAt: Long): ThrottleSample =
        telemetryToThrottleSample(
            t = t,
            runStartedAt = runStartedAt,
            batteryPercent = batteryChargeReader.readPercent(),
            charging = batteryChargeReader.isCharging(),
        )

    private fun preflightCheck(config: BenchConfig): BenchOutcome? = null

    /**
     * Returns true when the battery is TOO HOT to continue the sustained
     * throttle test. The check is on battery TEMPERATURE (≥ 45 °C), NOT on
     * battery charge level — the old name `lowBatteryDuringRun` was dishonest.
     * The outcome is reported as [BenchOutcome.ABORTED_BATTERY_TEMP].
     */
    private fun batteryTooHotDuringRun(sample: ThrottleSample): Boolean =
        sample.batteryTempC >= 45f

    private data class ThrottleResult(
        val samples: List<ThrottleSample>,
        val outcome: BenchOutcome,
    )

    sealed interface State {
        data object Idle : State
        data class Running(val flavor: BenchFlavor, val progress: Float, val etaMs: Long) : State
    }
}

/**
 * Tunable knobs for one run.
 *
 * Each CPU/mem kernel now runs for a fixed wall-clock budget rather
 * than a fixed iteration count, so STANDARD finishes in ~1 minute and
 * QUICK in ~20s on ANY chip — a faster SoC simply accumulates more
 * iterations (a higher score) inside the same time window. The
 * iteration counts below are just inner granularity per kernel call.
 */
data class BenchConfig(
    val cpuIterations: Int = 1500,
    val floatIterations: Int = 200,
    val aesIterations: Int = 100,
    val memArrayMb: Int = 64,
    val memIters: Int = 10,
    // Duration budgets — these set total wall-clock per kernel (chip-
    // independent). The iteration counts above are now just inner
    // granularity, not total time.
    val quickCpuBudgetMs: Long = 20_000L,
    val stdCpuSingleBudgetMs: Long = 12_000L,
    val stdCpuMultiBudgetMs: Long = 12_000L,
    val stdFloatBudgetMs: Long = 8_000L,
    val stdAesBudgetMs: Long = 8_000L,
    val stdMemBudgetMs: Long = 8_000L,
    val gpuDurationMs: Long = 8_000L,
    val drawCallDurationMs: Long = 4_000L,
    val throttleDurationMs: Long = 120_000L,
    val throttleInnerIterations: Int = 200,
    val killTempC: Float = 85f,
    val respectBatteryFloor: Boolean = true,
    // ── SCENE_3D flavor knobs ────────────────────────────────────────────────
    /** Number of sustained loops for the heavy 3D scene benchmark (standalone). */
    val scene3dLoopCount: Int = 10,
    /** Duration of each scene benchmark loop, ms (standalone). */
    val scene3dLoopMs: Long = 20_000L,
    // ── Embedded scene phase inside STANDARD ────────────────────────────────
    /** Number of scene loops run as part of STANDARD. Shorter than standalone. */
    val stdScene3dLoopCount: Int = 3,
    /** Duration of each embedded scene loop in STANDARD, ms. */
    val stdScene3dLoopMs: Long = 15_000L,
)
