package io.github.mayusi.calibratesoc.data.scorelog

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for user-entered external benchmark scores.
 *
 * These are scores the USER typed in after running a third-party benchmark
 * (3DMark, AnTuTu, etc.). This app does not generate, verify, or auto-read
 * these scores. Every entry is self-reported by the user.
 */
@Singleton
class ExternalScoreRepository @Inject constructor(
    private val dao: ExternalScoreDao,
) {
    fun observeAll(): Flow<List<ExternalScoreEntity>> = dao.observeAll()

    fun observeByBenchmark(name: String): Flow<List<ExternalScoreEntity>> =
        dao.observeByBenchmark(name)

    fun observeDistinctNames(): Flow<List<String>> = dao.observeDistinctNames()

    suspend fun save(score: ExternalScoreEntity): Long = dao.insert(score)

    suspend fun delete(id: Long) = dao.delete(id)
}
