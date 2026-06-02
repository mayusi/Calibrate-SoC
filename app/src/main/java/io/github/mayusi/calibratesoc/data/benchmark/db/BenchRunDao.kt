package io.github.mayusi.calibratesoc.data.benchmark.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: BenchRunEntity): Long

    /** History list — reverse chronological. Flow so the UI updates
     *  the moment a new run finishes. */
    @Query("SELECT * FROM bench_runs ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<BenchRunEntity>>

    @Query("SELECT * FROM bench_runs WHERE id = :id")
    suspend fun getById(id: Long): BenchRunEntity?

    @Query("DELETE FROM bench_runs WHERE id = :id")
    suspend fun delete(id: Long)
}
