package io.github.mayusi.calibratesoc.data.scorelog

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A manually-entered benchmark score from a third-party app.
 *
 * LEGAL / HONESTY: every row here was typed by the user. This app never
 * scrapes, auto-reads, or imports scores from external apps. The UI always
 * labels these as "self-reported" to make the provenance clear.
 *
 * Fields:
 *   benchmarkName  - display name (e.g. "3DMark", "AnTuTu Benchmark")
 *   packageName    - optional; ties to BenchmarkAppRegistry for display logic
 *   scoreValue     - the raw number (stored as Double for flexibility)
 *   scoreLabel     - units / metric label (e.g. "Wild Life score", "Total score", "fps")
 *   deviceName     - optional free-text device description
 *   notedAtMs      - when the user logged it (epoch ms); defaults to now
 *   note           - optional free-text note ("after tuning governor", etc.)
 */
@Entity(tableName = "external_scores")
data class ExternalScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val benchmarkName: String,
    val packageName: String? = null,
    val scoreValue: Double,
    val scoreLabel: String,
    val deviceName: String? = null,
    val notedAtMs: Long,
    val note: String? = null,
)
