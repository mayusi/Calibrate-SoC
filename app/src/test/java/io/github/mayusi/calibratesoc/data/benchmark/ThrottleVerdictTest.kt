package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThrottleVerdictTest {

    /** Helper to build a ThrottleAnalysis with peakMhz defaulting to startMhz (no ramp-up case). */
    private fun analysis(
        startMhz: Int,
        sustainedMhz: Int,
        dropPct: Double,
        peakMhz: Int = startMhz,
        timeToThrottleMs: Long? = null,
        peakCpuTempC: Float = 75f,
        peakGpuTempC: Float? = null,
        thermalHeadroomC: Float? = 20f,
        avgPowerMw: Double? = 5000.0,
        energyMwh: Double? = 1.5,
    ) = ThrottleAnalysis(
        startMhz = startMhz,
        sustainedMhz = sustainedMhz,
        endMhz = sustainedMhz,
        peakMhz = peakMhz,
        dropPct = dropPct,
        timeToThrottleMs = timeToThrottleMs,
        peakCpuTempC = peakCpuTempC,
        peakGpuTempC = peakGpuTempC,
        thermalHeadroomC = thermalHeadroomC,
        avgPowerMw = avgPowerMw,
        energyMwh = energyMwh,
    )

    // --- KEY REGRESSION: the live-device bug scenario ---

    @Test
    fun `99 percent sustained FPS with ramp-up-and-hold clocks must be Rock solid not Heavy`() {
        // Reproduces the exact live bug: first sample idle 1017 MHz, peak 3187 MHz, held.
        // peakMhz=3187, sustainedMhz=3187, dropPct=0.0 (clamped). sustainedPct=99.
        val a = analysis(
            startMhz = 1017,         // idle/first sample (kept for compat)
            sustainedMhz = 3187,
            peakMhz = 3187,          // real peak the device reached
            dropPct = 0.0,           // clamped: no drop
            peakCpuTempC = 95f,
            thermalHeadroomC = 0f,
        )
        val verdict = makeThrottleVerdict(a, peakTempC = 95f, killTempC = 95f, sustainedPct = 99)
        assertThat(verdict.word).isEqualTo("Rock solid")
        assertThat(verdict.colorHint).isEqualTo("tertiary")
        // Explanation must not contain a negative percentage.
        assertThat(verdict.explanation).doesNotContain("-")
    }

    @Test
    fun `rock solid when clocks held and headroom is good`() {
        // Held 98% clock (2% drop), 20°C headroom, sustainedPct=99 -> "Rock solid"
        val a = analysis(startMhz = 2000, sustainedMhz = 1960, peakMhz = 2000, dropPct = 2.0,
            thermalHeadroomC = 20f, peakCpuTempC = 75f)
        val verdict = makeThrottleVerdict(a, peakTempC = 75f, killTempC = 95f, sustainedPct = 99)
        assertThat(verdict.word).isEqualTo("Rock solid")
        assertThat(verdict.colorHint).isEqualTo("tertiary")
    }

    @Test
    fun `mild throttling when moderate clock drop and fps still decent`() {
        // Dropped 10% clock, sustainedPct=88 -> "Mild throttling"
        val a = analysis(startMhz = 2000, sustainedMhz = 1800, peakMhz = 2000, dropPct = 10.0,
            timeToThrottleMs = 5000, peakCpuTempC = 80f, thermalHeadroomC = 15f)
        val verdict = makeThrottleVerdict(a, peakTempC = 80f, killTempC = 95f, sustainedPct = 88)
        assertThat(verdict.word).isEqualTo("Mild throttling")
        assertThat(verdict.colorHint).isEqualTo("primary")
    }

    @Test
    fun `heavy throttling when large clock drop and fps dropped too`() {
        // Dropped 31% clock, sustainedPct=70 -> "Heavy throttling"
        val a = analysis(startMhz = 3187, sustainedMhz = 2200, peakMhz = 3187, dropPct = 31.0,
            timeToThrottleMs = 2000, peakCpuTempC = 92f, thermalHeadroomC = 3f)
        val verdict = makeThrottleVerdict(a, peakTempC = 92f, killTempC = 95f, sustainedPct = 70)
        assertThat(verdict.word).isEqualTo("Heavy throttling")
        assertThat(verdict.colorHint).isEqualTo("error")
    }

    @Test
    fun `99 percent fps with transient clock dip stays not Heavy`() {
        // 99% FPS but a brief clock dip → still not Heavy throttling.
        val a = analysis(startMhz = 3000, sustainedMhz = 2950, peakMhz = 3000, dropPct = 1.7,
            thermalHeadroomC = 10f, peakCpuTempC = 85f)
        val verdict = makeThrottleVerdict(a, peakTempC = 85f, killTempC = 95f, sustainedPct = 99)
        assertThat(verdict.word).isNotEqualTo("Heavy throttling")
    }

    @Test
    fun `null analysis returns dash verdict`() {
        val verdict = makeThrottleVerdict(null, peakTempC = 50f, killTempC = 95f)
        assertThat(verdict.word).isEqualTo("—")
        assertThat(verdict.colorHint).isEqualTo("primary")
    }

    @Test
    fun `heavy throttling when large clock drop no fps supplied`() {
        // Without sustainedPct (legacy path), 25% drop > 15% -> "Heavy throttling"
        val a = analysis(startMhz = 2000, sustainedMhz = 1500, peakMhz = 2000, dropPct = 25.0,
            timeToThrottleMs = 2000, peakCpuTempC = 88f, thermalHeadroomC = 7f)
        val verdict = makeThrottleVerdict(a, peakTempC = 88f, killTempC = 95f)
        assertThat(verdict.word).isEqualTo("Heavy throttling")
        assertThat(verdict.colorHint).isEqualTo("error")
    }

    @Test
    fun `drop pct is never negative in verdict explanation`() {
        // Ramp-up scenario: dropPct=0 (clamped). Explanation must not show negative number.
        val a = analysis(startMhz = 1017, sustainedMhz = 3187, peakMhz = 3187, dropPct = 0.0,
            thermalHeadroomC = 5f, peakCpuTempC = 90f)
        val verdict = makeThrottleVerdict(a, peakTempC = 90f, killTempC = 95f, sustainedPct = 99)
        assertThat(verdict.explanation).doesNotContain("-")
    }
}
