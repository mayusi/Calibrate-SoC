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
        stabilityPct = stabilityPct,
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
                stabilityPct = result.stabilityPct,
                minFps = result.minFps,
                maxFps = result.maxFps,
                peakTempC = result.peakTempC,
                outcome = result.outcome.name,
                loopFpsJson = json.encodeToString<List<Double>>(result.loopFps),
                samplesJson = json.encodeToString<List<ThrottleSample>>(result.samples),
            )
    }
}
