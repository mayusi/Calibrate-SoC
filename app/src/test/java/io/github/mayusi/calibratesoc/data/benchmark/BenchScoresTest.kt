package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BenchScoresTest {

    private fun runWith(kernels: KernelScores): BenchRun = BenchRun(
        id = 1L,
        name = "test",
        flavor = BenchFlavor.STANDARD,
        startedAtMs = 0L,
        durationMs = 60_000L,
        snapshot = SystemSnapshot(
            capturedAtMs = 0L,
            deviceModel = "Test",
            socModel = "Test SoC",
            androidVersion = "14",
            privilegeTier = "NONE",
            cpuPolicies = emptyList(),
            gpuMinHz = null,
            gpuMaxHz = null,
            gpuGovernor = null,
            appVersion = "0.1.5",
        ),
        kernels = kernels,
        throttleSamples = emptyList(),
        outcome = BenchOutcome.COMPLETED,
    )

    @Test
    fun `cpu blend averages weighted parts`() {
        // int1T*1 + intMT*1 + float*1 + aes*0.5 -> (1000+4000+2000+1000)/4 = 2000
        val run = runWith(
            KernelScores(
                cpuIntegerSingle = 1000L,
                cpuIntegerMulti = 4000L,
                cpuFloat = 2000L,
                cpuAes = 2000L,
            ),
        )
        assertThat(BenchScores.from(run).cpu).isEqualTo(2000L)
    }

    @Test
    fun `gpu blend applies consistency penalty deterministically`() {
        // avg=60, p1Low=30, consistency=50 -> consMul=0.5
        // ((60*100)*0.6 + (30*100)*0.4) * 0.5 = (3600 + 1200) * 0.5 = 2400
        val run = runWith(
            KernelScores(
                gpuFps = 60.0,
                gpuP1LowFps = 30.0,
                gpuFrameConsistencyPct = 50.0,
            ),
        )
        assertThat(BenchScores.from(run).gpu).isEqualTo(2400L)
    }

    @Test
    fun `memory scales bandwidth by 0 point 1`() {
        // BUG 3: was *10 → 1000; now *0.1 → 10 for 100 MB/s (in same order as CPU/GPU).
        val run = runWith(KernelScores(memoryBandwidthMBps = 100.0))
        assertThat(BenchScores.from(run).memory).isEqualTo(10L)
    }

    @Test
    fun `memory sub-score for typical bandwidth lands in 3-5 digit range`() {
        // 50 000 MB/s * 0.1 = 5 000 — same order of magnitude as CPU (~10 000) and GPU (~6 000)
        val run = runWith(KernelScores(memoryBandwidthMBps = 50_000.0))
        val score = BenchScores.from(run).memory!!
        assertThat(score).isAtLeast(1_000L)
        assertThat(score).isAtMost(99_999L)
    }

    @Test
    fun `all null kernels yield all null categories`() {
        val run = runWith(KernelScores())
        val s = BenchScores.from(run)
        assertThat(s.cpu).isNull()
        assertThat(s.gpu).isNull()
        assertThat(s.memory).isNull()
    }

    @Test
    fun `overall mirrors bench run overall score`() {
        val run = runWith(KernelScores(cpuIntegerSingle = 1234L))
        assertThat(BenchScores.from(run).overall).isEqualTo(run.overallScore)
    }

    // ─── BUG 3: memory must not dominate the composite ────────────────────────

    @Test
    fun `memory score is same order of magnitude as CPU and GPU in composite`() {
        // Typical device: cpuSingle=10000, memBw=50000, gpuFps=60
        // overallScore = (10000*1.0 + 50000*0.1 + 60*100.0) / 4.6
        //              = (10000 + 5000 + 6000) / 4.6 = 21000 / 4.6 ≈ 4565
        val run = runWith(KernelScores(
            cpuIntegerSingle = 10_000L,
            memoryBandwidthMBps = 50_000.0,
            gpuFps = 60.0,
        ))
        val score = run.overallScore!!
        // Memory contribution is 5000/4.6 ≈ 1087; CPU ≈ 2174; GPU ≈ 1304.
        // Score should be reasonable and not be dominated by memory alone.
        assertThat(score).isAtLeast(1_000L)
        assertThat(score).isAtMost(100_000L)
        // Without the fix: memBw*10 = 500000 → score ≈ (10000+500000+6000)/3 ≈ 172000 — absurd.
        // With the fix: score < 10000.
        assertThat(score).isAtMost(10_000L)
    }

    // ─── BUG 14: fixed-weight composite prevents QUICK from inflating vs STANDARD ─

    @Test
    fun `QUICK score lower than STANDARD for same CPU performance`() {
        // QUICK only has cpuSingle; STANDARD has all kernels.
        // With fixed denominator (4.6), QUICK always scores less than STANDARD.
        val cpuSingle = 10_000L
        val quick = runWith(KernelScores(cpuIntegerSingle = cpuSingle)).copy(flavor = BenchFlavor.QUICK)
        val standard = runWith(KernelScores(
            cpuIntegerSingle = cpuSingle,
            cpuIntegerMulti = 40_000L,
            cpuFloat = 10_000L,
            cpuAes = 20_000L,
            memoryBandwidthMBps = 50_000.0,
            gpuFps = 60.0,
        ))
        val qScore = quick.overallScore!!
        val sScore = standard.overallScore!!
        assertThat(qScore).isLessThan(sScore)
    }

    @Test
    fun `all null kernels yield null overallScore`() {
        val run = runWith(KernelScores())
        assertThat(run.overallScore).isNull()
    }

    @Test
    fun `overallScore with only memoryBandwidthMBps does not inflate`() {
        // 50000 MB/s * 0.1 = 5000 contribution → score = 5000 / 4.6 ≈ 1086
        val run = runWith(KernelScores(memoryBandwidthMBps = 50_000.0))
        val score = run.overallScore!!
        assertThat(score).isLessThan(5_000L)   // was > 100 000 before the fix
    }
}
