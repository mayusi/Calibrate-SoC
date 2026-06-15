package io.github.mayusi.calibratesoc.data.benchmark

/**
 * A persisted stability run. UI-facing domain type that the history list
 * renders directly — carries both the cheap summary scalars and the full
 * per-loop / telemetry series rehydrated from the Room row's JSON blobs.
 *
 * Parallels BenchRun: the repository maps StabilityRunEntity <-> this, so
 * the UI never touches the Entity/JSON layer.
 */
data class StabilityRun(
    val id: Long,
    val startedAtMs: Long,
    val loopCount: Int,
    val loopMs: Long,
    /** Null means the run had fewer than 2 loops (no sustained-vs-peak signal). */
    val stabilityPct: Int?,
    val minFps: Double,
    val maxFps: Double,
    val peakTempC: Float,
    val outcome: BenchOutcome,
    val loopFps: List<Double>,
    val samples: List<ThrottleSample>,
)
