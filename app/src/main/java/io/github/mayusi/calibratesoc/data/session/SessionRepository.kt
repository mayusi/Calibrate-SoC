package io.github.mayusi.calibratesoc.data.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence gateway for [GameSession]. Keeps the DAO out of the
 * ViewModel/Recorder so they only depend on this single class.
 *
 * Prune-to-10 is enforced by calling [SessionDao.pruneToTen] after every
 * insert. Room wraps the suspend DAO calls in transactions, so the insert
 * and the prune are effectively atomic from the caller's perspective.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: SessionDao,
    private val json: Json,
) {
    /** Reverse-chronological stream of the ≤10 saved sessions. */
    fun observeAll(): Flow<List<GameSession>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain(json) } }

    /** Persist a completed session and prune to 10. Returns the new row id. */
    suspend fun save(session: GameSession): Long {
        val entity = SessionEntity.fromSession(session, json)
        val id = dao.insert(entity)
        dao.pruneToTen()
        return id
    }

    suspend fun getById(id: Long): GameSession? =
        dao.getById(id)?.toDomain(json)

    suspend fun delete(id: Long) = dao.delete(id)
}
