package io.github.mayusi.calibratesoc.data.insights.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mayusi.calibratesoc.data.insights.SessionReport

/**
 * Flat Room row for a [SessionReport]. Follows the same pattern as
 * [SessionEntity] / [BenchRunEntity]: all scalar fields are columns so
 * list queries can render without JSON parsing; there are no large blobs
 * here because the report is already a summary of the sample list.
 *
 * [sessionId] is a UNIQUE foreign key pointing at [SessionEntity.id]. We
 * store it as a plain Long (no Room ForeignKey annotation) to keep the
 * schema simple and avoid cascades; orphans are tolerated since session
 * history can be pruned independently.
 *
 * Schema first included in [BenchDatabase] v9.
 */
@Entity(tableName = "session_reports")
data class SessionReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** References the originating game_sessions row. Unique per report. */
    val sessionId: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val appLabel: String?,
    val profileName: String?,

    // FPS
    val avgFps: Float?,
    val peakFps: Float?,
    val p1LowFps: Float?,

    // Thermal
    val peakCpuTempC: Float?,
    val peakGpuTempC: Float?,

    // Power / energy
    val avgPowerW: Double?,
    val energyMwh: Double?,
    val autoTdpSavedMwh: Double?,

    // Throttle
    val throttleEventCount: Int,

    // Verdict
    val verdict: String,
) {
    fun toDomain(): SessionReport = SessionReport(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        appLabel = appLabel,
        profileName = profileName,
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

    companion object {
        fun fromDomain(report: SessionReport): SessionReportEntity = SessionReportEntity(
            id = 0L, // Room assigns the real id on insert
            sessionId = report.sessionId,
            startedAtMs = report.startedAtMs,
            durationMs = report.durationMs,
            appLabel = report.appLabel,
            profileName = report.profileName,
            avgFps = report.avgFps,
            peakFps = report.peakFps,
            p1LowFps = report.p1LowFps,
            peakCpuTempC = report.peakCpuTempC,
            peakGpuTempC = report.peakGpuTempC,
            avgPowerW = report.avgPowerW,
            energyMwh = report.energyMwh,
            autoTdpSavedMwh = report.autoTdpSavedMwh,
            throttleEventCount = report.throttleEventCount,
            verdict = report.verdict,
        )
    }
}
