package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThrottleAnalysisTest {

    private fun sample(
        elapsedMs: Long,
        cpuMaxMhz: Int,
        cpuTempC: Float = 60f,
        gpuTempC: Float? = null,
        batteryDrawMw: Long? = null,
    ) = ThrottleSample(
        elapsedMs = elapsedMs,
        cpuMaxMhz = cpuMaxMhz,
        cpuMaxTempC = cpuTempC,
        gpuTempC = gpuTempC,
        batteryTempC = 30f,
        batteryDrawMw = batteryDrawMw,
    )

    @Test
    fun `empty samples returns null`() {
        assertThat(ThrottleAnalysis.from(emptyList())).isNull()
    }

    @Test
    fun `energy integral via trapezoid over elapsed deltas`() {
        // Two samples 1000ms apart at 2000mW each:
        // avgMw=2000, dt=1000ms -> 2000*1000/3_600_000 = 0.5555..mWh
        val samples = listOf(
            sample(0, 3000, batteryDrawMw = 2000L),
            sample(1000, 3000, batteryDrawMw = 2000L),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.energyMwh).isWithin(0.001).of(2000.0 * 1000.0 / 3_600_000.0)
    }

    @Test
    fun `time to throttle returns elapsed of first sub-threshold sample`() {
        // startMhz=3000, threshold = 0.95*3000 = 2850. 2800 sample crosses it.
        val samples = listOf(
            sample(0, 3000),
            sample(250, 3000),
            sample(500, 2800),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.timeToThrottleMs).isEqualTo(500L)
    }

    @Test
    fun `never throttle yields null time and ~zero drop`() {
        val samples = listOf(
            sample(0, 3000),
            sample(250, 3000),
            sample(500, 3000),
            sample(750, 3000),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.timeToThrottleMs).isNull()
        assertThat(a.dropPct).isWithin(0.001).of(0.0)
    }

    @Test
    fun `thermal headroom is kill temp minus peak cpu temp`() {
        val samples = listOf(
            sample(0, 3000, cpuTempC = 65f),
            sample(250, 3000, cpuTempC = 70f),
        )
        val a = ThrottleAnalysis.from(samples, killTempC = 85f)!!
        assertThat(a.peakCpuTempC).isEqualTo(70f)
        assertThat(a.thermalHeadroomC).isWithin(0.001f).of(15f)
    }

    @Test
    fun `avg power uses only non-null battery draw`() {
        val samples = listOf(
            sample(0, 3000, batteryDrawMw = 1000L),
            sample(250, 3000, batteryDrawMw = null),
            sample(500, 3000, batteryDrawMw = 3000L),
        )
        val a = ThrottleAnalysis.from(samples)!!
        // mean of (1000, 3000) = 2000
        assertThat(a.avgPowerMw).isWithin(0.001).of(2000.0)
    }
}
