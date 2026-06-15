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
    fun `ramp-up then hold reads zero drop not negative`() {
        // First sample is idle at 1017 MHz, then clocks ramp to 3187 MHz and hold.
        // peakMhz=3187, sustainedMhz≈3187 → dropPct must be 0.0, never negative.
        val samples = listOf(
            sample(0,    1017),   // idle / ramp-up
            sample(500,  3187),
            sample(1000, 3187),
            sample(1500, 3187),
            sample(2000, 3187),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.peakMhz).isEqualTo(3187)
        assertThat(a.sustainedMhz).isEqualTo(3187)
        assertThat(a.dropPct).isWithin(0.001).of(0.0)
        // No throttle after ramp-up (all post-ramp samples are at peak).
        assertThat(a.timeToThrottleMs).isNull()
    }

    @Test
    fun `real throttle case uses peak as reference`() {
        // Peak 3187 MHz, sustained drops to 2200 MHz → ~31% drop.
        val samples = listOf(
            sample(0,    3187),
            sample(500,  3187),
            sample(1000, 3187),
            sample(1500, 2200),
            sample(2000, 2200),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.peakMhz).isEqualTo(3187)
        val expectedDrop = (3187 - 2200) * 100.0 / 3187
        assertThat(a.dropPct).isWithin(0.5).of(expectedDrop)
        assertThat(a.dropPct).isAtLeast(0.0)
    }

    @Test
    fun `flat run reads zero drop`() {
        val samples = listOf(
            sample(0,    3000),
            sample(500,  3000),
            sample(1000, 3000),
            sample(1500, 3000),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.dropPct).isWithin(0.001).of(0.0)
    }

    @Test
    fun `time to throttle returns DELTA from ramp-up point not absolute elapsed`() {
        // BUG 6 fix: timeToThrottleMs is now delta (time held peak before dropping).
        // peakMhz=3000, rampThreshold=2700, throttleThreshold=2850.
        // rampedIndex=0 (3000 @ 0ms ≥ 2700), rampElapsedMs=0ms.
        // First drop after ramp: 2800 @ 500ms → delta = 500ms - 0ms = 500ms.
        val samples = listOf(
            sample(0,   3000),
            sample(250, 3000),
            sample(500, 2800),
        )
        val a = ThrottleAnalysis.from(samples)!!
        assertThat(a.timeToThrottleMs).isEqualTo(500L)
    }

    @Test
    fun `time to throttle is delta when ramp-up starts mid-run`() {
        // Ramp-up completes at 1000ms (first sample ≥ 90% of 3000 = 2700).
        // Throttle at 1500ms (2800 ≤ 2850). Delta = 1500ms - 1000ms = 500ms.
        val samples = listOf(
            sample(0,    1000),   // below ramp threshold (2700)
            sample(500,  2000),   // still ramping
            sample(1000, 3000),   // ramp complete
            sample(1500, 2800),   // throttle
        )
        val a = ThrottleAnalysis.from(samples)!!
        // rampedIndex=2 (3000 @ 1000ms), rampElapsedMs=1000ms.
        // First drop after: 2800 @ 1500ms → delta = 1500 - 1000 = 500ms.
        assertThat(a.timeToThrottleMs).isEqualTo(500L)
    }

    @Test
    fun `never throttle yields null time and ~zero drop`() {
        val samples = listOf(
            sample(0,   3000),
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
