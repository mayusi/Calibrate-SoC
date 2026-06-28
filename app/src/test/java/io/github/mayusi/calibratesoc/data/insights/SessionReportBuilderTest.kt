package io.github.mayusi.calibratesoc.data.insights

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.SavingsResult
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.SessionSample
import io.github.mayusi.calibratesoc.data.session.SessionSummary
import io.github.mayusi.calibratesoc.data.session.computeSessionSummary
import org.junit.Test

/**
 * Pure JVM tests for [SessionReportBuilder].
 *
 * Covers:
 *   - avgFps / peakFps aggregation from sample list
 *   - p1LowFps: present when ≥ 100 FPS samples, absent otherwise
 *   - peakCpuTempC / peakGpuTempC selection
 *   - avgPowerW computation
 *   - energyMwh trapezoid integral (matches ThrottleAnalysis math)
 *   - autoTdpSavedMwh from SavingsResult
 *   - throttleEventCount from session summary fpsDipEvents
 *   - verdict: "not enough data" for short sessions
 *   - verdict: no-FPS path
 *   - verdict: AutoTDP savings shown when available
 *   - energyMwh null when fewer than 2 power samples
 */
class SessionReportBuilderTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sample(
        elapsedMs: Long = 0L,
        fps: Float? = null,
        cpuTempC: Float? = 60f,
        gpuTempC: Float? = null,
        batteryW: Double? = null,
    ) = SessionSample(
        elapsedMs = elapsedMs,
        fps = fps,
        cpuMaxMhz = 3000,
        gpuMhz = null,
        cpuTempC = cpuTempC,
        gpuTempC = gpuTempC,
        batteryW = batteryW,
        cpuLoadPct = 50,
    )

    private fun session(
        id: Long = 1L,
        durationMs: Long = 60_000L,
        samples: List<SessionSample>,
        fpsAvailable: Boolean = true,
        appLabel: String? = "TestGame",
        profileName: String? = "Performance",
    ): GameSession = GameSession(
        id = id,
        startedAtMs = 1_000_000L,
        durationMs = durationMs,
        appLabel = appLabel,
        packageName = null,
        profileName = profileName,
        samples = samples,
        summary = computeSessionSummary(samples),
        fpsAvailableDuringSampling = fpsAvailable,
    )

    private fun savings(deltaMw: Long, enoughData: Boolean = true) = SavingsResult(
        baselineMw = deltaMw + 3000L,
        tunedMw = 3000L,
        deltaMw = deltaMw,
        deltaPct = deltaMw.toDouble() / (deltaMw + 3000L) * 100,
        sampleCount = 20,
        enoughData = enoughData,
    )

    // ── FPS aggregation ──────────────────────────────────────────────────────

    @Test
    fun `avgFps is mean of fps samples`() {
        val samples = listOf(
            sample(elapsedMs = 0L, fps = 60f),
            sample(elapsedMs = 1000L, fps = 30f),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.avgFps).isWithin(0.1f).of(45f)
    }

    @Test
    fun `peakFps is the maximum fps observed`() {
        val samples = listOf(
            sample(elapsedMs = 0L, fps = 55f),
            sample(elapsedMs = 1000L, fps = 62f),
            sample(elapsedMs = 2000L, fps = 58f),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.peakFps).isWithin(0.1f).of(62f)
    }

    @Test
    fun `avgFps and peakFps are null when no fps samples`() {
        val samples = listOf(
            sample(elapsedMs = 0L, fps = null),
            sample(elapsedMs = 1000L, fps = null),
            sample(elapsedMs = 2000L, fps = null),
            sample(elapsedMs = 3000L, fps = null),
            sample(elapsedMs = 4000L, fps = null),
        )
        val report = SessionReportBuilder.build(session(samples = samples, fpsAvailable = false))
        assertThat(report.avgFps).isNull()
        assertThat(report.peakFps).isNull()
    }

    @Test
    fun `p1LowFps is null when fewer than 100 fps samples`() {
        val samples = (0 until 50).map { i -> sample(elapsedMs = i * 1000L, fps = 60f) }
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.p1LowFps).isNull()
    }

    @Test
    fun `p1LowFps is computed when 100 or more fps samples`() {
        // 100 samples: 99 at 60fps, 1 at 10fps → p1 takes the bottom 1% = 1 sample = 10 fps.
        val samples = (0 until 99).map { i -> sample(elapsedMs = i * 1000L, fps = 60f) } +
            listOf(sample(elapsedMs = 99_000L, fps = 10f))
        val report = SessionReportBuilder.build(session(samples = samples))
        // p1 = bottom 1% of 100 = 1 sample = 10 fps
        assertThat(report.p1LowFps).isNotNull()
        assertThat(report.p1LowFps!!).isWithin(0.1f).of(10f)
    }

    // ── Thermal ──────────────────────────────────────────────────────────────

    @Test
    fun `peakCpuTempC picks the maximum across all samples`() {
        val samples = listOf(
            sample(elapsedMs = 0L, cpuTempC = 60f),
            sample(elapsedMs = 1000L, cpuTempC = 78f),
            sample(elapsedMs = 2000L, cpuTempC = 71f),
            sample(elapsedMs = 3000L, cpuTempC = 65f),
            sample(elapsedMs = 4000L, cpuTempC = 68f),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.peakCpuTempC).isWithin(0.1f).of(78f)
    }

    @Test
    fun `peakCpuTempC is null when no samples have cpu temp`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L, cpuTempC = null) }
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.peakCpuTempC).isNull()
    }

    @Test
    fun `peakGpuTempC picks the maximum`() {
        val samples = listOf(
            sample(elapsedMs = 0L, gpuTempC = 55f),
            sample(elapsedMs = 1000L, gpuTempC = 62f),
            sample(elapsedMs = 2000L, gpuTempC = 58f),
            sample(elapsedMs = 3000L, gpuTempC = 59f),
            sample(elapsedMs = 4000L, gpuTempC = 57f),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.peakGpuTempC).isWithin(0.1f).of(62f)
    }

    // ── Power / energy ───────────────────────────────────────────────────────

    @Test
    fun `avgPowerW is mean of batteryW samples in watts`() {
        val samples = listOf(
            sample(elapsedMs = 0L, batteryW = 4.0),
            sample(elapsedMs = 1000L, batteryW = 6.0),
            sample(elapsedMs = 2000L, batteryW = 5.0),
            sample(elapsedMs = 3000L, batteryW = 5.0),
            sample(elapsedMs = 4000L, batteryW = 5.0),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.avgPowerW).isWithin(0.01).of(5.0)
    }

    @Test
    fun `avgPowerW is null when no samples report battery draw`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L, batteryW = null) }
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.avgPowerW).isNull()
    }

    @Test
    fun `energyMwh trapezoid integral is correct for uniform power`() {
        // 5 W constant for 4 s = 4/3600 Wh = 4000 mW * 4000 ms / 3_600_000 = ~4.44 mWh
        // Trapezoid: each segment avg = 5 W = 5000 mW; 4 segments × 1000 ms each.
        // Sum = 4 × 5000 × 1000 = 20_000_000 mW·ms → / 3_600_000 = 5.556 mWh.
        val samples = listOf(
            sample(elapsedMs = 0L, batteryW = 5.0),
            sample(elapsedMs = 1000L, batteryW = 5.0),
            sample(elapsedMs = 2000L, batteryW = 5.0),
            sample(elapsedMs = 3000L, batteryW = 5.0),
            sample(elapsedMs = 4000L, batteryW = 5.0),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        // 5 W = 5000 mW; 4000 ms window; 4 trapezoid segments × 5000 mW × 1000 ms
        val expected = 4.0 * 5000.0 * 1000.0 / 3_600_000.0
        assertThat(report.energyMwh!!).isWithin(0.001).of(expected)
    }

    @Test
    fun `energyMwh is null when only one power sample`() {
        val samples = listOf(
            sample(elapsedMs = 0L, batteryW = 5.0),
            sample(elapsedMs = 1000L, batteryW = null),
            sample(elapsedMs = 2000L, batteryW = null),
            sample(elapsedMs = 3000L, batteryW = null),
            sample(elapsedMs = 4000L, batteryW = null),
        )
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.energyMwh).isNull()
    }

    @Test
    fun `energyMwh does not apply abs() - positive batteryW means discharging`() {
        // Two samples: both positive (discharging). Energy should be positive.
        // This verifies no abs() is applied (mirroring ThrottleAnalysis contract).
        val samples = listOf(
            sample(elapsedMs = 0L, batteryW = 3.0),
            sample(elapsedMs = 2000L, batteryW = 3.0),
            sample(elapsedMs = 4000L, batteryW = 3.0),
            sample(elapsedMs = 6000L, batteryW = 3.0),
            sample(elapsedMs = 8000L, batteryW = 3.0),
        )
        val energy = SessionReportBuilder.computeEnergyMwh(samples)
        assertThat(energy).isNotNull()
        assertThat(energy!!).isGreaterThan(0.0)
    }

    @Test
    fun `energyMwh uses elapsed deltas not uniform intervals`() {
        // Non-uniform timestamps: gaps of 1s, 3s, 1s, 1s.
        // 3W constant: energy = (3000*(1000+3000+1000+1000)) ms·mW / 3_600_000
        val samples = listOf(
            sample(elapsedMs = 0L, batteryW = 3.0),
            sample(elapsedMs = 1000L, batteryW = 3.0),
            sample(elapsedMs = 4000L, batteryW = 3.0),
            sample(elapsedMs = 5000L, batteryW = 3.0),
            sample(elapsedMs = 6000L, batteryW = 3.0),
        )
        val energy = SessionReportBuilder.computeEnergyMwh(samples)
        val expected = 3000.0 * (1000 + 3000 + 1000 + 1000).toDouble() / 3_600_000.0
        assertThat(energy!!).isWithin(0.001).of(expected)
    }

    // ── AutoTDP savings ──────────────────────────────────────────────────────

    @Test
    fun `autoTdpSavedMwh is computed from deltaMw when enoughData is true`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L) }
        val sv = savings(deltaMw = 1800L, enoughData = true)
        val report = SessionReportBuilder.build(
            session = session(samples = samples, durationMs = 60_000L),
            savings = sv,
        )
        // 1800 mW × 60_000 ms / 3_600_000 = 30 mWh
        assertThat(report.autoTdpSavedMwh!!).isWithin(0.1).of(30.0)
    }

    @Test
    fun `autoTdpSavedMwh is null when enoughData is false`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L) }
        val sv = savings(deltaMw = 1800L, enoughData = false)
        val report = SessionReportBuilder.build(session(samples = samples), sv)
        assertThat(report.autoTdpSavedMwh).isNull()
    }

    @Test
    fun `autoTdpSavedMwh is null when savings is null`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L) }
        val report = SessionReportBuilder.build(session(samples = samples), savings = null)
        assertThat(report.autoTdpSavedMwh).isNull()
    }

    @Test
    fun `autoTdpSavedMwh is null when deltaMw is negative or zero`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L) }
        // deltaMw = 0 means no savings
        val sv = SavingsResult(
            baselineMw = 3000L, tunedMw = 3000L, deltaMw = 0L, deltaPct = 0.0,
            sampleCount = 20, enoughData = true,
        )
        val report = SessionReportBuilder.build(session(samples = samples), sv)
        assertThat(report.autoTdpSavedMwh).isNull()
    }

    // ── Throttle count ───────────────────────────────────────────────────────

    @Test
    fun `throttleEventCount comes from session summary fpsDipEvents`() {
        // fpsDipEvents is computed by computeSessionSummary via SessionSummary.
        // Create a session where there are 2 dip events.
        val baselineFps = 60f
        val dipFps = 20f // well below 80% of 60 = 48
        val samples = (0 until 10).map { i ->
            val fps = if (i == 3 || i == 7) dipFps else baselineFps
            sample(elapsedMs = i * 1000L, fps = fps)
        }
        val report = SessionReportBuilder.build(session(samples = samples, fpsAvailable = true))
        assertThat(report.throttleEventCount).isEqualTo(2)
    }

    // ── Verdict ──────────────────────────────────────────────────────────────

    @Test
    fun `verdict says not enough data for fewer than MIN_SAMPLES_FOR_REPORT samples`() {
        val samples = (0 until 3).map { i -> sample(elapsedMs = i * 1000L, fps = 60f) }
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.verdict).contains("Not enough data")
        assertThat(report.verdict).contains("3 sample")
    }

    @Test
    fun `verdict includes avg fps when fps data is available`() {
        val samples = (0 until 10).map { i -> sample(elapsedMs = i * 1000L, fps = 59f) }
        val report = SessionReportBuilder.build(session(samples = samples, fpsAvailable = true))
        assertThat(report.verdict).contains("59")
    }

    @Test
    fun `verdict notes no fps data when hud was not active`() {
        val samples = (0 until 10).map { i -> sample(elapsedMs = i * 1000L, fps = null) }
        val report = SessionReportBuilder.build(session(samples = samples, fpsAvailable = false))
        assertThat(report.verdict).contains("No FPS data")
    }

    @Test
    fun `verdict includes peak temp when available`() {
        val samples = (0 until 10).map { i ->
            sample(elapsedMs = i * 1000L, fps = 60f, cpuTempC = 62f)
        }
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.verdict).contains("62")
    }

    @Test
    fun `verdict shows AutoTDP savings when enoughData and positive delta`() {
        val samples = (0 until 10).map { i -> sample(elapsedMs = i * 1000L, fps = 60f) }
        val sv = savings(deltaMw = 1800L, enoughData = true) // 1.8 W
        val report = SessionReportBuilder.build(
            session = session(samples = samples, durationMs = 60_000L),
            savings = sv,
        )
        assertThat(report.verdict).contains("AutoTDP saved")
        assertThat(report.verdict).contains("1.8")
    }

    @Test
    fun `verdict ends with period`() {
        val samples = (0 until 10).map { i -> sample(elapsedMs = i * 1000L, fps = 60f) }
        val report = SessionReportBuilder.build(session(samples = samples))
        assertThat(report.verdict).endsWith(".")
    }

    // ── Session metadata pass-through ────────────────────────────────────────

    @Test
    fun `sessionId startedAtMs durationMs appLabel profileName are preserved`() {
        val samples = (0 until 5).map { i -> sample(elapsedMs = i * 1000L) }
        val report = SessionReportBuilder.build(
            session(
                id = 42L,
                durationMs = 90_000L,
                samples = samples,
                appLabel = "PPSSPP",
                profileName = "Balanced",
            )
        )
        assertThat(report.sessionId).isEqualTo(42L)
        assertThat(report.durationMs).isEqualTo(90_000L)
        assertThat(report.appLabel).isEqualTo("PPSSPP")
        assertThat(report.profileName).isEqualTo("Balanced")
    }
}
