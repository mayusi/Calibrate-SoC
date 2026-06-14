package io.github.mayusi.calibratesoc.data.session

import kotlin.math.roundToInt

/**
 * Computed summary derived from [List<SessionSample>]. All maths is
 * pure (no I/O, no side effects) so it can be unit-tested on the JVM
 * without any Android dependency.
 *
 * [avgFps] ignores null-FPS samples: if FPS was unavailable for the
 * whole session (no HUD) this is null.
 *
 * [p1LowFps] is the 1st-percentile FPS (the worst 1 % of frames) — the
 * single most useful stutter signal. Null when fewer than 100 samples
 * have a real FPS value.
 *
 * [fpsDipEvents] is the count of samples where FPS dropped ≥ 20 % below
 * the session average (or below 40 fps) — used to annotate "why did my
 * FPS drop?" moments on the timeline.
 */
data class SessionSummary(
    val avgFps: Float?,
    val p1LowFps: Float?,
    val minFps: Float?,
    val peakCpuTempC: Float?,
    val peakGpuTempC: Float?,
    val avgWatts: Double?,
    val fpsDipEvents: Int,
)

/**
 * Compute [SessionSummary] from raw samples. Pure function — safe to
 * call anywhere, including unit tests.
 */
fun computeSessionSummary(samples: List<SessionSample>): SessionSummary {
    if (samples.isEmpty()) {
        return SessionSummary(
            avgFps = null,
            p1LowFps = null,
            minFps = null,
            peakCpuTempC = null,
            peakGpuTempC = null,
            avgWatts = null,
            fpsDipEvents = 0,
        )
    }

    val fpsSamples = samples.mapNotNull { it.fps }
    val avgFps: Float? = if (fpsSamples.isEmpty()) null
        else fpsSamples.sum() / fpsSamples.size
    val minFps: Float? = fpsSamples.minOrNull()

    // 1 % low: only computed when we have enough samples for it to be
    // meaningful. Sort ascending, take bottom 1 %.
    val p1LowFps: Float? = if (fpsSamples.size < 100) null else {
        val sorted = fpsSamples.sorted()
        val p1Count = (sorted.size * 0.01).roundToInt().coerceAtLeast(1)
        sorted.take(p1Count).average().toFloat()
    }

    val peakCpuTempC = samples.mapNotNull { it.cpuTempC }.maxOrNull()
    val peakGpuTempC = samples.mapNotNull { it.gpuTempC }.maxOrNull()

    val wattSamples = samples.mapNotNull { it.batteryW }
    val avgWatts: Double? = if (wattSamples.isEmpty()) null
        else wattSamples.sum() / wattSamples.size

    // FPS dip = dropped ≥ 20 % below session average, or below 40 fps
    // hard floor. Count runs of consecutive dip-samples as one "event"
    // (so a 3-second drop counts as 1 event, not 3).
    val dipThreshold = avgFps?.let { it * 0.80f } ?: 40f
    val absoluteFloor = 40f
    var fpsDipEvents = 0
    var inDip = false
    for (s in samples) {
        val fps = s.fps
        val isDip = fps != null && (fps < dipThreshold || fps < absoluteFloor)
        if (isDip && !inDip) {
            fpsDipEvents++
            inDip = true
        } else if (!isDip) {
            inDip = false
        }
    }

    return SessionSummary(
        avgFps = avgFps,
        p1LowFps = p1LowFps,
        minFps = minFps,
        peakCpuTempC = peakCpuTempC,
        peakGpuTempC = peakGpuTempC,
        avgWatts = avgWatts,
        fpsDipEvents = fpsDipEvents,
    )
}
