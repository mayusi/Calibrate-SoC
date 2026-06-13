package io.github.mayusi.calibratesoc.data.session

import kotlinx.serialization.Serializable

/**
 * One second of captured state. Lean by design: 1 Hz × up to 3 h =
 * ~10 800 samples; still fits in a JSON blob (~1 MB worst case).
 *
 * [elapsedMs] is milliseconds since session start (not epoch time), so
 * the timeline always reads 0 → N regardless of wall clock.
 *
 * [fps] is nullable: real game FPS is only available when the HUD is
 * running and the SurfaceFlinger/PServer path succeeds. Sessions recorded
 * without the HUD will have fps == null for all samples (and the UI says
 * so explicitly — no fake data ever).
 *
 * [cpuLoadPct] is the average across all cores, matching the value the
 * Dashboard shows.
 */
@Serializable
data class SessionSample(
    val elapsedMs: Long,
    val fps: Float?,
    val cpuMaxMhz: Int,
    val gpuMhz: Int?,
    val cpuTempC: Float?,
    val gpuTempC: Float?,
    val batteryW: Double?,
    val cpuLoadPct: Int,
)
