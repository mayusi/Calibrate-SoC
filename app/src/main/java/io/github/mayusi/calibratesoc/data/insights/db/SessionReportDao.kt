package io.github.mayusi.calibratesoc.data.insights.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [SessionReportEntity].
 *
 * Insert strategy is REPLACE: if a report for the same [sessionId] is
 * recomputed (e.g. after a session is re-analysed), the old row is
 * silently replaced. This keeps the table free of duplicate reports.
 *
 * Pruning mirrors [SessionDao]: we keep the 20 most-recent reports so the
 * table stays compact. Insights history is convenience data, not load-bearing.
 */
@Dao
interface SessionReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionReportEntity): Long

    /** All reports, newest first. Flow so insight screens update live. */
    @Query("SELECT * FROM session_reports ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionReportEntity>>

    /** All reports within a time window (inclusive), newest first. */
    @Query(
        "SELECT * FROM session_reports WHERE startedAtMs >= :fromMs AND startedAtMs <= :toMs " +
            "ORDER BY startedAtMs DESC",
    )
    fun observeInWindow(fromMs: Long, toMs: Long): Flow<List<SessionReportEntity>>

    @Query("SELECT * FROM session_reports WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: Long): SessionReportEntity?

    @Query("DELETE FROM session_reports WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)

    /**
     * Prune to the 20 most-recent reports. Called after every insert.
     * Keeps the table bounded independently of the game_sessions table.
     */
    @Query(
        """
        DELETE FROM session_reports
        WHERE id NOT IN (
            SELECT id FROM session_reports
            ORDER BY startedAtMs DESC
            LIMIT 20
        )
        """,
    )
    suspend fun pruneToTwenty()
}
