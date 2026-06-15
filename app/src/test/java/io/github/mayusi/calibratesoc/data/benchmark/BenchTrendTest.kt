package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [BenchTrend].
 *
 * Tests verify:
 *  - Chronological ordering (oldest→newest)
 *  - Flavor filtering (only matching flavor appears)
 *  - Skipping aborted runs (only COMPLETED)
 *  - Delta / best computation
 *  - Empty series (0 completed runs) → null delta
 *  - Single-run series (1 completed run) → null delta
 *  - Multi-metric extraction (each metric series is independent)
 *  - defaultFlavor() picks the flavor with the most completed runs
 */
class BenchTrendTest {

    // ─── Factory helpers ──────────────────────────────────────────────────

    private fun makeSnapshot() = SystemSnapshot(
        capturedAtMs   = 0L,
        deviceModel    = "TestDevice",
        socModel       = "TestSoC",
        androidVersion = "14",
        privilegeTier  = "NONE",
        cpuPolicies    = emptyList(),
        gpuMinHz       = null,
        gpuMaxHz       = null,
        gpuGovernor    = null,
        appVersion     = "test",
    )

    private fun makeRun(
        id: Long,
        flavor: BenchFlavor,
        outcome: BenchOutcome,
        startedAtMs: Long,
        cpuSingle: Long? = null,
        cpuMulti: Long? = null,
        gpuFps: Double? = null,
        memBwMBps: Double? = null,
        name: String = "run-$id",
    ) = BenchRun(
        id             = id,
        name           = name,
        flavor         = flavor,
        startedAtMs    = startedAtMs,
        durationMs     = 60_000L,
        snapshot       = makeSnapshot(),
        kernels        = KernelScores(
            cpuIntegerSingle = cpuSingle,
            cpuIntegerMulti  = cpuMulti,
            gpuFps           = gpuFps,
            memoryBandwidthMBps = memBwMBps,
        ),
        throttleSamples = emptyList(),
        outcome         = outcome,
    )

    // ─── Ordering ─────────────────────────────────────────────────────────

    @Test
    fun `series is sorted oldest to newest`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 3000L, cpuSingle = 100L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 200L),
            makeRun(3, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 150L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        val times = result.cpuSeries.map { it.startedAtMs }
        assertThat(times).isInOrder()
    }

    @Test
    fun `runIndex is assigned 0-based in chronological order`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 100L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 200L),
            makeRun(3, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 3000L, cpuSingle = 300L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuSeries.map { it.runIndex }).containsExactly(0, 1, 2).inOrder()
    }

    // ─── Flavor filtering ─────────────────────────────────────────────────

    @Test
    fun `only runs of the requested flavor appear in series`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.QUICK,    BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 100L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 200L),
            makeRun(3, BenchFlavor.FULL,     BenchOutcome.COMPLETED, startedAtMs = 3000L, cpuSingle = 300L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuSeries).hasSize(1)
        assertThat(result.cpuSeries.first().score).isEqualTo(
            BenchScores.from(runs[1]).cpu
        )
    }

    @Test
    fun `quick flavor compute returns empty series for gpu and memory`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.QUICK, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 100L),
            makeRun(2, BenchFlavor.QUICK, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 120L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.QUICK)
        // Quick has no GPU / memory kernels → those series are empty.
        assertThat(result.gpuSeries).isEmpty()
        assertThat(result.memorySeries).isEmpty()
        // CPU is present.
        assertThat(result.cpuSeries).hasSize(2)
    }

    // ─── Skipping aborted runs ────────────────────────────────────────────

    @Test
    fun `aborted runs are excluded from all series`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED,     startedAtMs = 1000L, cpuSingle = 100L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.ABORTED_TEMP,  startedAtMs = 2000L, cpuSingle = 999L),
            makeRun(3, BenchFlavor.STANDARD, BenchOutcome.ABORTED_USER,  startedAtMs = 3000L, cpuSingle = 999L),
            makeRun(4, BenchFlavor.STANDARD, BenchOutcome.COMPLETED,     startedAtMs = 4000L, cpuSingle = 200L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuSeries).hasSize(2)
        assertThat(result.cpuSeries.map { it.startedAtMs }).containsExactly(1000L, 4000L).inOrder()
    }

    @Test
    fun `failed runs are excluded`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.FAILED_NATIVE, startedAtMs = 1000L, cpuSingle = 999L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED,     startedAtMs = 2000L, cpuSingle = 100L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuSeries).hasSize(1)
        assertThat(result.cpuSeries.first().score).isEqualTo(
            BenchScores.from(runs[1]).cpu
        )
    }

    // ─── Delta / best computation ─────────────────────────────────────────

    @Test
    fun `delta first latest best are correct`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 1000L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 1500L),
            makeRun(3, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 3000L, cpuSingle = 1200L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        val cpuDelta = result.cpuDelta!!
        assertThat(cpuDelta.first).isEqualTo(BenchScores.from(runs[0]).cpu)
        assertThat(cpuDelta.latest).isEqualTo(BenchScores.from(runs[2]).cpu)
        assertThat(cpuDelta.best).isEqualTo(BenchScores.from(runs[1]).cpu)
    }

    @Test
    fun `delta changePercent positive when latest greater than first`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 1000L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 1100L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuDelta!!.improved).isTrue()
        assertThat(result.cpuDelta!!.changePercent).isGreaterThan(0.0)
    }

    @Test
    fun `delta changePercent negative when latest less than first`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 1000L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, cpuSingle = 900L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuDelta!!.improved).isFalse()
        assertThat(result.cpuDelta!!.changePercent).isLessThan(0.0)
    }

    // ─── Empty / single-run cases ─────────────────────────────────────────

    @Test
    fun `zero completed runs yields empty series and null deltas`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.ABORTED_USER, startedAtMs = 1000L, cpuSingle = 100L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.overallSeries).isEmpty()
        assertThat(result.cpuSeries).isEmpty()
        assertThat(result.gpuSeries).isEmpty()
        assertThat(result.memorySeries).isEmpty()
        assertThat(result.overallDelta).isNull()
        assertThat(result.cpuDelta).isNull()
        assertThat(result.totalRuns).isEqualTo(0)
    }

    @Test
    fun `single completed run yields size-1 series and null delta`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, cpuSingle = 100L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.cpuSeries).hasSize(1)
        assertThat(result.cpuDelta).isNull()   // needs >=2 points
        assertThat(result.totalRuns).isEqualTo(1)
    }

    @Test
    fun `empty runs list yields empty result`() {
        val result = BenchTrend.compute(emptyList(), BenchFlavor.STANDARD)
        assertThat(result.overallSeries).isEmpty()
        assertThat(result.cpuSeries).isEmpty()
        assertThat(result.totalRuns).isEqualTo(0)
    }

    // ─── Multi-metric extraction ──────────────────────────────────────────

    @Test
    fun `each metric series is independent — run missing gpu still contributes cpu`() {
        val runs = listOf(
            // Run 1: CPU only (no GPU)
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L,
                cpuSingle = 100L, gpuFps = null),
            // Run 2: CPU + GPU
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L,
                cpuSingle = 110L, gpuFps = 60.0),
            // Run 3: CPU + GPU
            makeRun(3, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 3000L,
                cpuSingle = 120L, gpuFps = 65.0),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        // CPU has 3 points (all 3 runs contributed)
        assertThat(result.cpuSeries).hasSize(3)
        // GPU has only 2 points (run 1 had no GPU score)
        assertThat(result.gpuSeries).hasSize(2)
        // GPU delta is non-null because >=2 GPU points
        assertThat(result.gpuDelta).isNotNull()
        // CPU delta is non-null because >=2 CPU points
        assertThat(result.cpuDelta).isNotNull()
    }

    @Test
    fun `memory series uses BenchScores bandwidth scaling`() {
        val bwMBps = 100.0
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 1000L, memBwMBps = bwMBps),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L, memBwMBps = bwMBps * 1.1),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.memorySeries).hasSize(2)
        // BUG 3 fix: BenchScores.from scales memory ×0.1 (was ×10) so typical
        // bandwidth (30k–80k MB/s) lands in the same range as CPU/GPU sub-scores.
        // 100.0 * 0.1 = 10; 110.0 * 0.1 = 11.
        assertThat(result.memorySeries.first().score).isEqualTo(10L)
        assertThat(result.memorySeries.last().score).isEqualTo(11L)
    }

    // ─── defaultFlavor ────────────────────────────────────────────────────

    @Test
    fun `defaultFlavor picks flavor with most completed runs`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.QUICK,    BenchOutcome.COMPLETED, startedAtMs = 1000L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L),
            makeRun(3, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 3000L),
            makeRun(4, BenchFlavor.FULL,     BenchOutcome.COMPLETED, startedAtMs = 4000L),
        )
        assertThat(BenchTrend.defaultFlavor(runs)).isEqualTo(BenchFlavor.STANDARD)
    }

    @Test
    fun `defaultFlavor prefers STANDARD on tie`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.QUICK,    BenchOutcome.COMPLETED, startedAtMs = 1000L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.COMPLETED, startedAtMs = 2000L),
        )
        // 1 Quick vs 1 Standard → Standard wins tie-break
        assertThat(BenchTrend.defaultFlavor(runs)).isEqualTo(BenchFlavor.STANDARD)
    }

    @Test
    fun `defaultFlavor returns null when no completed runs`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.ABORTED_USER, startedAtMs = 1000L),
        )
        assertThat(BenchTrend.defaultFlavor(runs)).isNull()
    }

    @Test
    fun `defaultFlavor returns null for empty list`() {
        assertThat(BenchTrend.defaultFlavor(emptyList())).isNull()
    }

    // ─── totalRuns count ──────────────────────────────────────────────────

    @Test
    fun `totalRuns counts only completed runs of the selected flavor`() {
        val runs = listOf(
            makeRun(1, BenchFlavor.STANDARD, BenchOutcome.COMPLETED,    startedAtMs = 1000L),
            makeRun(2, BenchFlavor.STANDARD, BenchOutcome.ABORTED_USER, startedAtMs = 2000L),
            makeRun(3, BenchFlavor.QUICK,    BenchOutcome.COMPLETED,    startedAtMs = 3000L),
            makeRun(4, BenchFlavor.STANDARD, BenchOutcome.COMPLETED,    startedAtMs = 4000L),
        )
        val result = BenchTrend.compute(runs, BenchFlavor.STANDARD)
        assertThat(result.totalRuns).isEqualTo(2)
    }
}
