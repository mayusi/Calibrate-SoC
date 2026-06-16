package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW

/**
 * Pure aggregation of one sampling window (baseline OR tuned) for the AutoTDP
 * proof-of-effect probe.
 *
 * Separated from [AutoTdpSampler] (which owns the coroutine/MonitorService
 * plumbing) so the window math is directly unit-testable without Android.
 *
 * @property drawMilliW   Valid (>0) battery-draw readings (mW) collected this window.
 * @property cpuTempC      Per-sample mean CPU zone temperature (°C); empty when no CPU zones.
 * @property gpuTempC      Per-sample max GPU/kgsl zone temperature (°C); empty when no GPU zones.
 * @property realFps       Real (SurfaceFlinger-backed) FPS readings; empty when FPS not real/unavailable.
 */
data class ProbeWindow(
    val drawMilliW: List<Long>,
    val cpuTempC: List<Float>,
    val gpuTempC: List<Float>,
    val realFps: List<Int>,
) {
    companion object {
        /** An empty window — no samples collected. */
        val EMPTY = ProbeWindow(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Supplies the CURRENT real-game FPS for the probe, or null when no real FPS is
 * available (no foreground game, refresh-rate fallback, PServer unreachable).
 *
 * HONESTY: this MUST return null whenever the underlying FPS is NOT a genuine
 * SurfaceFlinger frame measurement. The probe only feeds non-null values into the
 * FPS delta; a refresh-rate fallback must surface as null so [AutoTdpEffect.fpsDelta]
 * stays null and the UI hides it.
 *
 * The data layer has no FPS source of its own (FPS lives in the overlay/UI
 * [io.github.mayusi.calibratesoc.ui.overlay.GameFpsSampler]); the default
 * implementation therefore always returns null. A wired supplier can bridge the
 * real sampler in when the HUD is active.
 */
fun interface RealFpsSupplier {
    /** @return current real-game FPS, or null when not real/unavailable. */
    fun currentRealFps(): Int?

    companion object {
        /** The honest default: never claims an FPS. */
        val NONE = RealFpsSupplier { null }
    }
}

/**
 * The complete result of one baseline→tuned probe cycle: the measured draw
 * [savings] plus the measured temp/fps deltas. The temp/fps deltas are null when
 * their windows lacked the corresponding measurement (honest hide).
 *
 * @property savings     Measured draw delta (gate display on [SavingsResult.enoughData]).
 * @property tempDeltaC  Measured baseline−tuned temp delta (°C, +=cooler); null = no measured base.
 * @property fpsDelta    Measured tuned−baseline real-FPS delta; null = no real FPS in both windows.
 */
data class ProbeResult(
    val savings: SavingsResult,
    val tempDeltaC: Float?,
    val fpsDelta: Int?,
)

/**
 * Pure aggregation + delta math for the AutoTDP probe. No Android, no coroutines.
 */
object AutoTdpProbe {

    /**
     * Reduce a list of collected [Telemetry] samples (plus the matching real-FPS
     * readings for those ticks) into a [ProbeWindow].
     *
     * Temp extraction mirrors HudStateAssembler exactly:
     *   - CPU temp  = mean of zones whose label starts with "cpu" (case-insensitive).
     *   - GPU temp  = max of zones whose label contains "gpu" or "kgsl".
     *
     * @param samples     Telemetry collected during the window.
     * @param realFpsPerTick Real FPS for each sample tick (same length as [samples] when
     *                    available); non-null entries are kept, nulls dropped. Pass an
     *                    empty list when no real-FPS source is wired.
     */
    fun aggregate(samples: List<Telemetry>, realFpsPerTick: List<Int?>): ProbeWindow {
        val draws = samples.mapNotNull { it.batteryDrawMilliW }.filter { it > 0 }

        val cpuTemps = samples.mapNotNull { t ->
            val cpuZones = t.zoneTempsMilliC
                .filter { it.label.startsWith("cpu", ignoreCase = true) }
                .map { it.tempMilliC / 1000f }
            if (cpuZones.isEmpty()) null else cpuZones.average().toFloat()
        }

        val gpuTemps = samples.mapNotNull { t ->
            t.zoneTempsMilliC
                .filter {
                    it.label.contains("gpu", ignoreCase = true) ||
                        it.label.contains("kgsl", ignoreCase = true)
                }
                .maxOfOrNull { it.tempMilliC / 1000f }
        }

        val fps = realFpsPerTick.filterNotNull().filter { it > 0 }

        return ProbeWindow(
            drawMilliW = draws,
            cpuTempC = cpuTemps,
            gpuTempC = gpuTemps,
            realFps = fps,
        )
    }

    /**
     * Measured baseline−tuned CPU/GPU temperature delta (°C, positive = AutoTDP
     * runs cooler), or null when EITHER window lacks temperature samples.
     *
     * Uses the worse (max) of the CPU/GPU means in each window so the delta
     * reflects the hottest measured component, then baseline − tuned.
     */
    fun tempDeltaC(baseline: ProbeWindow, tuned: ProbeWindow): Float? {
        val baseTemp = peakMeanTemp(baseline) ?: return null
        val tunedTemp = peakMeanTemp(tuned) ?: return null
        return baseTemp - tunedTemp
    }

    private fun peakMeanTemp(w: ProbeWindow): Float? {
        val cpu = if (w.cpuTempC.isEmpty()) null else w.cpuTempC.average().toFloat()
        val gpu = if (w.gpuTempC.isEmpty()) null else w.gpuTempC.average().toFloat()
        return when {
            cpu != null && gpu != null -> maxOf(cpu, gpu)
            cpu != null -> cpu
            gpu != null -> gpu
            else -> null
        }
    }

    /**
     * Measured tuned−baseline FPS delta (positive = AutoTDP raised FPS), or null
     * when EITHER window lacks REAL FPS samples.
     *
     * HONESTY: only real (SurfaceFlinger-backed) FPS reaches [ProbeWindow.realFps]
     * (the supplier returns null for refresh-rate fallbacks), so a non-null delta
     * here is always a genuine measurement.
     */
    fun fpsDelta(baseline: ProbeWindow, tuned: ProbeWindow): Int? {
        if (baseline.realFps.isEmpty() || tuned.realFps.isEmpty()) return null
        val baseFps = baseline.realFps.average()
        val tunedFps = tuned.realFps.average()
        return (tunedFps - baseFps).toInt()
    }
}
