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
    fun `memory scales bandwidth by ten`() {
        val run = runWith(KernelScores(memoryBandwidthMBps = 100.0))
        assertThat(BenchScores.from(run).memory).isEqualTo(1000L)
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
}
