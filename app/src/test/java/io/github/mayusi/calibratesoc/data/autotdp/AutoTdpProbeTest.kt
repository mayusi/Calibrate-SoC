package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import org.junit.Test

/**
 * Unit tests for [AutoTdpProbe] — the pure aggregation + delta math behind the
 * revived sampler. Proves that, given real telemetry windows, the probe produces
 * a non-null [SavingsResult] and honest temp/fps deltas.
 */
class AutoTdpProbeTest {

    /**
     * Build a Telemetry sample with a known draw (via current/voltage), CPU/GPU
     * zone temps, so the pure aggregator can be exercised without Android.
     */
    private fun sample(
        currentUa: Long,
        voltageUv: Long = 4_000_000L,
        cpuTempC: Float? = null,
        gpuTempC: Float? = null,
    ): Telemetry {
        val zones = buildList {
            if (cpuTempC != null) add(ZoneTemp(0, "cpu-0-0", (cpuTempC * 1000).toInt()))
            if (gpuTempC != null) add(ZoneTemp(1, "gpu", (gpuTempC * 1000).toInt()))
        }
        return Telemetry(
            timestampMs = 0L,
            perCoreCpuFreqKhz = List(8) { 1_000_000 },
            perCoreLoadPct = List(8) { 30 },
            gpuLoadPct = 40,
            gpuFreqHz = 800_000_000L,
            zoneTempsMilliC = zones,
            ramTotalKb = 8_000_000L,
            ramAvailableKb = 4_000_000L,
            batteryTempDeciC = 280,
            batteryCurrentUa = currentUa,
            batteryVoltageUv = voltageUv,
            fanRpm = null,
        )
    }

    /** Convert a desired mW draw to the µA that batteryDrawMilliW will round to. */
    private fun uaForMw(mw: Long, voltageUv: Long = 4_000_000L): Long {
        // mW = (uA * uV) / 1e9  →  uA = mW * 1e9 / uV
        return mw * 1_000_000_000L / voltageUv
    }

    // ── Savings wiring: probe produces a non-null SavingsResult ──────────────────

    @Test
    fun `aggregate plus computeSavings yields a measured non-null SavingsResult`() {
        // 20 baseline samples at 4000 mW, 20 tuned at 3200 mW.
        val baselineSamples = List(20) { sample(currentUa = uaForMw(4_000)) }
        val tunedSamples = List(20) { sample(currentUa = uaForMw(3_200)) }

        // Sanity: the per-sample helper produces the intended draw.
        assertThat(baselineSamples.first().batteryDrawMilliW).isEqualTo(4_000L)

        val baseWindow = AutoTdpProbe.aggregate(baselineSamples, emptyList())
        val tunedWindow = AutoTdpProbe.aggregate(tunedSamples, emptyList())

        assertThat(baseWindow.drawMilliW).hasSize(20)
        assertThat(tunedWindow.drawMilliW).hasSize(20)

        val savings = AutoTdpSavings.computeSavings(baseWindow.drawMilliW, tunedWindow.drawMilliW)
        assertThat(savings.enoughData).isTrue()
        assertThat(savings.baselineMw).isEqualTo(4_000L)
        assertThat(savings.tunedMw).isEqualTo(3_200L)
        assertThat(savings.deltaMw).isEqualTo(800L)
    }

    @Test
    fun `zero-draw samples are excluded from the window`() {
        // current=0 → draw 0 → filtered out by aggregate.
        val samples = List(15) { sample(currentUa = uaForMw(4_000)) } +
            List(5) { sample(currentUa = 0L) }
        val window = AutoTdpProbe.aggregate(samples, emptyList())
        assertThat(window.drawMilliW).hasSize(15)
    }

    // ── Temp delta ──────────────────────────────────────────────────────────────

    @Test
    fun `temp delta is baseline minus tuned (positive when cooler)`() {
        val baseSamples = List(10) { sample(currentUa = uaForMw(4_000), cpuTempC = 60f, gpuTempC = 58f) }
        val tunedSamples = List(10) { sample(currentUa = uaForMw(3_200), cpuTempC = 55f, gpuTempC = 54f) }

        val base = AutoTdpProbe.aggregate(baseSamples, emptyList())
        val tuned = AutoTdpProbe.aggregate(tunedSamples, emptyList())

        // peakMean(base) = max(60, 58) = 60; peakMean(tuned) = max(55, 54) = 55 → +5.
        val delta = AutoTdpProbe.tempDeltaC(base, tuned)
        assertThat(delta).isNotNull()
        assertThat(delta!!).isWithin(0.01f).of(5f)
    }

    @Test
    fun `temp delta null when a window has no temp samples`() {
        val baseSamples = List(10) { sample(currentUa = uaForMw(4_000), cpuTempC = 60f) }
        val tunedSamples = List(10) { sample(currentUa = uaForMw(3_200)) } // no zones

        val base = AutoTdpProbe.aggregate(baseSamples, emptyList())
        val tuned = AutoTdpProbe.aggregate(tunedSamples, emptyList())

        assertThat(AutoTdpProbe.tempDeltaC(base, tuned)).isNull()
    }

    // ── FPS delta (real only) ────────────────────────────────────────────────────

    @Test
    fun `fps delta is tuned minus baseline when both windows have real fps`() {
        val baseSamples = List(10) { sample(currentUa = uaForMw(4_000)) }
        val tunedSamples = List(10) { sample(currentUa = uaForMw(3_200)) }

        // Real FPS readings: baseline ~55, tuned ~60.
        val base = AutoTdpProbe.aggregate(baseSamples, List(10) { 55 })
        val tuned = AutoTdpProbe.aggregate(tunedSamples, List(10) { 60 })

        val delta = AutoTdpProbe.fpsDelta(base, tuned)
        assertThat(delta).isEqualTo(5)
    }

    @Test
    fun `fps delta null when a window has no real fps (honest hide)`() {
        val baseSamples = List(10) { sample(currentUa = uaForMw(4_000)) }
        val tunedSamples = List(10) { sample(currentUa = uaForMw(3_200)) }

        // Baseline has real FPS but tuned does not (e.g. refresh-rate fallback → nulls).
        val base = AutoTdpProbe.aggregate(baseSamples, List(10) { 60 })
        val tuned = AutoTdpProbe.aggregate(tunedSamples, List(10) { null })

        assertThat(AutoTdpProbe.fpsDelta(base, tuned)).isNull()
    }

    @Test
    fun `null fps entries are dropped from the window`() {
        val samples = List(10) { sample(currentUa = uaForMw(4_000)) }
        // Mix of real and null FPS — only the reals survive.
        val window = AutoTdpProbe.aggregate(samples, List(10) { i -> if (i % 2 == 0) 60 else null })
        assertThat(window.realFps).hasSize(5)
        assertThat(window.realFps).containsExactly(60, 60, 60, 60, 60)
    }
}
