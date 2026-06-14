package io.github.mayusi.calibratesoc.data.scorelog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalScoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(score: ExternalScoreEntity): Long

    /** All scores, newest first. */
    @Query("SELECT * FROM external_scores ORDER BY notedAtMs DESC")
    fun observeAll(): Flow<List<ExternalScoreEntity>>

    /** Scores for a specific benchmark, newest first (for per-benchmark trend). */
    @Query("SELECT * FROM external_scores WHERE benchmarkName = :name ORDER BY notedAtMs ASC")
    fun observeByBenchmark(name: String): Flow<List<ExternalScoreEntity>>

    /** All distinct benchmark names that have at least one score logged. */
    @Query("SELECT DISTINCT benchmarkName FROM external_scores ORDER BY benchmarkName ASC")
    fun observeDistinctNames(): Flow<List<String>>

    @Query("DELETE FROM external_scores WHERE id = :id")
    suspend fun delete(id: Long)
}
