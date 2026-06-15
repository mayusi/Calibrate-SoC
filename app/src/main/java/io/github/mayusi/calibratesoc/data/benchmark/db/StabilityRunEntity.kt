package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome
import io.github.mayusi.calibratesoc.data.benchmark.StabilityResult
import io.github.mayusi.calibratesoc.data.benchmark.StabilityRun
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleSample
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Flat Room row for a persisted stability run. Mirrors BenchRunEntity:
 * the heavy List<*> payloads live in JSON-blob columns, while the cheap
 * scalar summary fields are denormalised so the history list can render
 * and sort without parsing JSON.
 *
 * JSON-blob columns:
 *   - loopFpsJson:  List<Double>
 *   - samplesJson:  List<ThrottleSample>   (ThrottleSample is @Serializable)
 */
@Entity(tableName = "stability_runs")
data class StabilityRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAtMs: Long,
    val loopCount: Int,
    val loopMs: Long,
    val stabilityPct: Int,
    val minFps: Double,
    val maxFps: Double,
    val peakTempC: Float,
    val outcome: String,
    val loopFpsJson: String,
    val samplesJson: String,
) {
    fun toDomain(json: Json): StabilityRun = StabilityRun(
        id = id,
        startedAtMs = startedAtMs,
        loopCount = loopCount,
        loopMs = loopMs,
        // -1 is the DB sentinel for "N/A (fewer than 2 loops ran)".
        stabilityPct = if (stabilityPct < 0) null else stabilityPct,
        minFps = minFps,
        maxFps = maxFps,
        peakTempC = peakTempC,
        outcome = BenchOutcome.valueOf(outcome),
        loopFps = json.decodeFromString(loopFpsJson),
        samples = json.decodeFromString(samplesJson),
    )

    companion object {
        /** Derive a row from a fresh StabilityResult. loopMs isn't on
         *  StabilityResult, so the caller passes it through explicitly. */
        fun fromResult(result: StabilityResult, loopMs: Long, json: Json): StabilityRunEntity =
            StabilityRunEntity(
                startedAtMs = result.startedAtMs,
                loopCount = result.loopCount,
                loopMs = loopMs,
                // -1 is the DB sentinel for null (no schema change needed).
                stabilityPct = result.stabilityPct ?: -1,
                minFps = result.minFps,
                maxFps = result.maxFps,
                peakTempC = result.peakTempC,
                outcome = result.outcome.name,
                loopFpsJson = json.encodeToString<List<Double>>(result.loopFps),
                samplesJson = json.encodeToString<List<ThrottleSample>>(result.samples),
            )

        /** Re-create a row from a domain StabilityRun (for undo-delete). */
        fun fromDomain(run: StabilityRun, json: Json): StabilityRunEntity =
            StabilityRunEntity(
                id = run.id,
                startedAtMs = run.startedAtMs,
                loopCount = run.loopCount,
                loopMs = run.loopMs,
                stabilityPct = run.stabilityPct ?: -1,
                minFps = run.minFps,
                maxFps = run.maxFps,
                peakTempC = run.peakTempC,
                outcome = run.outcome.name,
                loopFpsJson = json.encodeToString<List<Double>>(run.loopFps),
                samplesJson = json.encodeToString<List<ThrottleSample>>(run.samples),
            )
    }
}
