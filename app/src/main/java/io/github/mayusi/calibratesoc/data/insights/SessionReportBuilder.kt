package io.github.mayusi.calibratesoc.data.insights

import io.github.mayusi.calibratesoc.data.autotdp.SavingsResult
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.SessionSample
import kotlin.math.roundToInt

/**
 * Pure function that converts a completed [GameSession] into a [SessionReport].
 *
 * No Android, no I/O, no coroutines — fully JVM-testable. The only inputs are
 * the session itself (which carries its sample list) and an optional measured
 * [SavingsResult] from AutoTDP. If [savings] is null or [SavingsResult.enoughData]
 * is false, [SessionReport.autoTdpSavedMwh] will be null.
 *
 * Energy math mirrors [ThrottleAnalysis.from]: trapezoid integral of batteryW
 * (already positive = discharging, NO abs()) over real elapsed-ms deltas.
 *
 * Honesty rules applied here:
 *   - avgFps / peakFps / p1LowFps are null when the HUD was not active.
 *   - p1LowFps requires ≥ [MIN_SAMPLES_FOR_P1] FPS samples.
 *   - energyMwh requires ≥ 2 samples with power data.
 *   - autoTdpSavedMwh requires [SavingsResult.enoughData] == true.
 *   - Sessions with fewer than [MIN_SAMPLES_FOR_REPORT] total samples receive
 *     a "Not enough data" verdict.
 */
object SessionReportBuilder {

    /** Fewer than this many total samples → "Not enough data" verdict. */
    const val MIN_SAMPLES_FOR_REPORT = 5

    /** Fewer than this many FPS samples → p1LowFps is null (not meaningful). */
    const val MIN_SAMPLES_FOR_P1 = 100

    fun build(session: GameSession, savings: SavingsResult? = null): SessionReport {
        val samples = session.samples

        // ── FPS ────────────────────────────────────────────────────────────
        val fpsSamples: List<Float> = samples.mapNotNull { it.fps }
        val avgFps: Float? = if (fpsSamples.isEmpty()) null
            else fpsSamples.sum() / fpsSamples.size
        val peakFps: Float? = fpsSamples.maxOrNull()
        val p1LowFps: Float? = if (fpsSamples.size < MIN_SAMPLES_FOR_P1) null else {
            val sorted = fpsSamples.sorted()
            val count = (sorted.size * 0.01).roundToInt().coerceAtLeast(1)
            sorted.take(count).average().toFloat()
        }

        // ── Thermal ────────────────────────────────────────────────────────
        val peakCpuTempC: Float? = samples.mapNotNull { it.cpuTempC }.maxOrNull()
        val peakGpuTempC: Float? = samples.mapNotNull { it.gpuTempC }.maxOrNull()

        // ── Power / energy ─────────────────────────────────────────────────
        val powerSamples: List<Double> = samples.mapNotNull { it.batteryW }
        val avgPowerW: Double? = if (powerSamples.isEmpty()) null
            else powerSamples.sum() / powerSamples.size

        // Trapezoid integral over real elapsed-ms deltas (same as ThrottleAnalysis).
        // batteryW is already positive = discharging — no abs() applied.
        val energyMwh: Double? = computeEnergyMwh(samples)

        // AutoTDP saved energy: deltaMw × durationMs / 3_600_000.
        // Only when enoughData is true (caller measured a real baseline).
        val autoTdpSavedMwh: Double? = if (savings != null && savings.enoughData && savings.deltaMw > 0) {
            (savings.deltaMw.toDouble() / 1.0) * session.durationMs / 3_600_000.0
        } else null

        // ── Throttle ───────────────────────────────────────────────────────
        val throttleEventCount: Int = session.summary.fpsDipEvents

        // ── Verdict ────────────────────────────────────────────────────────
        val verdict = buildVerdict(
            sampleCount = samples.size,
            avgFps = avgFps,
            peakFps = peakFps,
            peakCpuTempC = peakCpuTempC,
            avgPowerW = avgPowerW,
            autoTdpSavedMwh = autoTdpSavedMwh,
            savings = savings,
            fpsAvailable = session.fpsAvailableDuringSampling,
            throttleEventCount = throttleEventCount,
        )

        return SessionReport(
            sessionId = session.id,
            startedAtMs = session.startedAtMs,
            durationMs = session.durationMs,
            appLabel = session.appLabel,
            profileName = session.profileName,
            avgFps = avgFps,
            peakFps = peakFps,
            p1LowFps = p1LowFps,
            peakCpuTempC = peakCpuTempC,
            peakGpuTempC = peakGpuTempC,
            avgPowerW = avgPowerW,
            energyMwh = energyMwh,
            autoTdpSavedMwh = autoTdpSavedMwh,
            throttleEventCount = throttleEventCount,
            verdict = verdict,
        )
    }

    /**
     * Trapezoid integral: sum( avgMw_segment * dt_ms ) / 3_600_000.
     * Only samples that have batteryW data contribute.
     * Returns null when fewer than 2 power-reporting samples exist.
     *
     * batteryW is [SessionSample.batteryW] which is already positive = discharging;
     * no abs() is applied here (mirrors ThrottleAnalysis.from energy math exactly).
     */
    internal fun computeEnergyMwh(samples: List<SessionSample>): Double? {
        // Convert W → mW for consistency with ThrottleAnalysis (which uses mW).
        val withPower = samples.filter { it.batteryW != null }
        if (withPower.size < 2) return null

        var acc = 0.0
        for (i in 1 until withPower.size) {
            val prev = withPower[i - 1]
            val curr = withPower[i]
            val dtMs = (curr.elapsedMs - prev.elapsedMs).coerceAtLeast(0)
            // batteryW is in W; convert to mW (*1000) for the mWh integral.
            val prevMw = prev.batteryW!! * 1000.0
            val currMw = curr.batteryW!! * 1000.0
            val avgMw = (prevMw + currMw) / 2.0
            acc += avgMw * dtMs
        }
        return acc / 3_600_000.0
    }

    /**
     * Compose a one-line verdict string from the computed metrics.
     * All numbers are from measured data — nothing is fabricated.
     */
    private fun buildVerdict(
        sampleCount: Int,
        avgFps: Float?,
        peakFps: Float?,
        peakCpuTempC: Float?,
        avgPowerW: Double?,
        autoTdpSavedMwh: Double?,
        savings: SavingsResult?,
        fpsAvailable: Boolean,
        throttleEventCount: Int,
    ): String {
        if (sampleCount < MIN_SAMPLES_FOR_REPORT) {
            return "Not enough data (only $sampleCount sample${if (sampleCount == 1) "" else "s"} recorded)."
        }

        val parts = mutableListOf<String>()

        // FPS part
        if (avgFps != null && fpsAvailable) {
            val fps = "Held %.0f fps".format(avgFps)
            parts += if (peakFps != null && peakFps > avgFps + 2f) {
                "$fps (peak %.0f)".format(peakFps)
            } else {
                fps
            }
        } else {
            parts += "No FPS data (HUD was not active)"
        }

        // Temp part
        if (peakCpuTempC != null) {
            parts += "peaked %.0f °C".format(peakCpuTempC)
        }

        // Power / AutoTDP part
        when {
            autoTdpSavedMwh != null && savings != null -> {
                val savedW = savings.deltaMw / 1000.0
                parts += "AutoTDP saved ~%.1f W".format(savedW)
            }
            avgPowerW != null -> {
                parts += "avg %.1f W".format(avgPowerW)
            }
        }

        // Throttle note (only when noteworthy — 2+ events)
        if (throttleEventCount >= 2) {
            parts += "$throttleEventCount throttle events (heuristic)"
        }

        return parts.joinToString(", ") + "."
    }
}
