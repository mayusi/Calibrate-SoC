package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.benchmark.KernelScores
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleSample
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Flat Room row. Three JSON-blob columns:
 *   - snapshotJson:        SystemSnapshot
 *   - throttleSamplesJson: List<ThrottleSample>
 *   - kernelsJson:         KernelScores (added in schema v2)
 *
 * cpuScore is retained as a denormalised column so existing list
 * rendering paths can still sort/filter on it without parsing JSON.
 */
@Entity(tableName = "bench_runs")
data class BenchRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val flavor: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val outcome: String,
    val cpuScore: Long?,
    val snapshotJson: String,
    val throttleSamplesJson: String,
    val kernelsJson: String,
) {
    fun toDomain(json: Json): BenchRun = BenchRun(
        id = id,
        name = name,
        flavor = BenchFlavor.valueOf(flavor),
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        snapshot = json.decodeFromString(snapshotJson),
        kernels = json.decodeFromString(kernelsJson),
        throttleSamples = json.decodeFromString(throttleSamplesJson),
        outcome = BenchOutcome.valueOf(outcome),
    )

    companion object {
        fun fromDomain(run: BenchRun, json: Json): BenchRunEntity = BenchRunEntity(
            id = run.id,
            name = run.name,
            flavor = run.flavor.name,
            startedAtMs = run.startedAtMs,
            durationMs = run.durationMs,
            outcome = run.outcome.name,
            cpuScore = run.cpuScore,
            snapshotJson = json.encodeToString(run.snapshot),
            throttleSamplesJson = json.encodeToString<List<ThrottleSample>>(run.throttleSamples),
            kernelsJson = json.encodeToString<KernelScores>(run.kernels),
        )
    }
}
