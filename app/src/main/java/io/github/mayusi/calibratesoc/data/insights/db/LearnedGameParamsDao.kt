package io.github.mayusi.calibratesoc.data.insights.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for [LearnedGameParamsEntity].
 *
 * One row per package name. [upsert] uses REPLACE conflict strategy so
 * Unit 1 can blindly write updated learned params after every session
 * without checking for prior existence.
 *
 * [getAll] is provided for a future UI that surfaces per-game learned
 * history to the user (not yet built in Unit 0).
 */
@Dao
interface LearnedGameParamsDao {

    /** Returns the learned params for [pkg], or null if none recorded yet. */
    @Query("SELECT * FROM learned_game_params WHERE pkg = :pkg LIMIT 1")
    suspend fun getByPkg(pkg: String): LearnedGameParamsEntity?

    /**
     * Insert or replace the learned params for a package.
     * REPLACE conflict strategy updates the existing row in-place when the
     * package is already known, preserving the single-row-per-game invariant.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LearnedGameParamsEntity)

    /** All learned-param rows, ordered by most-recently updated first. */
    @Query("SELECT * FROM learned_game_params ORDER BY lastUpdatedMs DESC")
    suspend fun getAll(): List<LearnedGameParamsEntity>
}
