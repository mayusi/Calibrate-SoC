package io.github.mayusi.calibratesoc.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: SessionEntity): Long

    /** All sessions, newest first. Flow so the UI updates the moment a
     *  session finishes. */
    @Query("SELECT * FROM game_sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM game_sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("DELETE FROM game_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Prune: after inserting a new session, delete any rows beyond the 10
     * newest. Runs in the same transaction context as [insert] when called
     * from the repository; a bare @Query is safe because Room wraps
     * suspend DAO calls in a transaction automatically.
     *
     * The subquery selects the 10 newest startedAtMs values; anything NOT
     * in that set is deleted. This deliberately uses startedAtMs DESC
     * (not id DESC) so manual inserts for testing don't accidentally prune
     * the real most-recent session if IDs were out of order.
     */
    @Query(
        """
        DELETE FROM game_sessions
        WHERE id NOT IN (
            SELECT id FROM game_sessions
            ORDER BY startedAtMs DESC
            LIMIT 10
        )
        """,
    )
    suspend fun pruneToTen()
}
