package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.benchmark.db.BenchRunDao
import io.github.mayusi.calibratesoc.data.benchmark.db.BenchRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin repo wrapping the Room DAO + JSON codec. UI never touches the
 * Entity layer directly so the JSON-blob encoding stays hidden.
 */
@Singleton
class BenchRepository @Inject constructor(
    private val dao: BenchRunDao,
    private val json: Json,
) {
    fun observeAll(): Flow<List<BenchRun>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain(json) } }

    suspend fun get(id: Long): BenchRun? =
        dao.getById(id)?.toDomain(json)

    suspend fun save(run: BenchRun): Long =
        dao.insert(BenchRunEntity.fromDomain(run, json))

    suspend fun delete(id: Long) = dao.delete(id)
}
