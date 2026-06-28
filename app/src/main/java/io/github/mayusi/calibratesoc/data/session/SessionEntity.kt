package io.github.mayusi.calibratesoc.data.session

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Flat Room row for one saved gaming session. Mirrors the BenchRunEntity
 * pattern: heavy list payloads live in a JSON-blob column; scalar summary
 * fields are denormalised so the history list can render without parsing.
 *
 * JSON-blob columns:
 *   - samplesJson : List<SessionSample>
 *
 * Denormalised summary scalars:
 *   avgFps, minFps, peakCpuTempC, peakGpuTempC, avgWatts, fpsDipEvents
 * These are nullable because a session with no FPS data will have null
 * avgFps / minFps, and a short outdoor session might have no GPU zones.
 */
@Entity(tableName = "game_sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAtMs: Long,
    val durationMs: Long,
    val appLabel: String?,
    /**
     * Foreground app package name at session end (e.g. "com.rp.retroarch").
     * Nullable — absent on rows written before v10 (additive column, default null).
     * This is the stable machine-readable key used for per-game learned params.
     */
    val packageName: String?,
    val profileName: String?,
    // Denormalised summary
    val avgFps: Float?,
    val minFps: Float?,
    val peakCpuTempC: Float?,
    val peakGpuTempC: Float?,
    val avgWatts: Double?,
    val fpsDipEvents: Int,
    val fpsAvailableDuringSampling: Boolean,
    // Full sample history as JSON blob
    val samplesJson: String,
) {
    fun toDomain(json: Json): GameSession {
        val samples = json.decodeFromString<List<SessionSample>>(samplesJson)
        val summary = SessionSummary(
            avgFps = avgFps,
            p1LowFps = null, // recomputed on demand in detail screen
            minFps = minFps,
            peakCpuTempC = peakCpuTempC,
            peakGpuTempC = peakGpuTempC,
            avgWatts = avgWatts,
            fpsDipEvents = fpsDipEvents,
        )
        return GameSession(
            id = id,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            appLabel = appLabel,
            packageName = packageName,
            profileName = profileName,
            samples = samples,
            summary = summary,
            fpsAvailableDuringSampling = fpsAvailableDuringSampling,
        )
    }

    companion object {
        fun fromSession(session: GameSession, json: Json): SessionEntity {
            val summary = session.summary
            return SessionEntity(
                id = session.id,
                startedAtMs = session.startedAtMs,
                durationMs = session.durationMs,
                appLabel = session.appLabel,
                packageName = session.packageName,
                profileName = session.profileName,
                avgFps = summary.avgFps,
                minFps = summary.minFps,
                peakCpuTempC = summary.peakCpuTempC,
                peakGpuTempC = summary.peakGpuTempC,
                avgWatts = summary.avgWatts,
                fpsDipEvents = summary.fpsDipEvents,
                fpsAvailableDuringSampling = session.fpsAvailableDuringSampling,
                samplesJson = json.encodeToString<List<SessionSample>>(session.samples),
            )
        }
    }
}
