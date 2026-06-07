package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StabilityRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: StabilityRunEntity): Long

    /** History list — reverse chronological. Flow so the UI updates the
     *  moment a new run finishes. */
    @Query("SELECT * FROM stability_runs ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<StabilityRunEntity>>

    @Query("DELETE FROM stability_runs WHERE id = :id")
    suspend fun delete(id: Long)
}
