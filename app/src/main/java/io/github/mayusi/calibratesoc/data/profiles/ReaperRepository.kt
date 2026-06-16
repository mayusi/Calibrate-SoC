package io.github.mayusi.calibratesoc.data.profiles

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReaperRepository"

/**
 * File-backed persistence for [ReaperConfig].
 *
 * Mirrors [ProfileRepository]'s atomic-write + .bak recovery pattern so the
 * reaper config survives a crash during a write. Lives in the app's private
 * files dir — no root required to read back.
 *
 * Writes go through a [Mutex] to serialize concurrent edits. The Wave 4 UI
 * calls [setEnabled], [setDenylist], and [addToDenylist]/[removeFromDenylist]
 * to build and update the user's config.
 */
@Singleton
class ReaperRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File = File(context.filesDir, FILE_NAME)
    private val bakFile: File = File(context.filesDir, "$FILE_NAME.bak")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(ReaperConfig())
    val config: Flow<ReaperConfig> = _config.asStateFlow()

    fun snapshot(): ReaperConfig = _config.value

    init {
        scope.launch { _config.value = loadFromDisk() }
    }

    /** Enable or disable the reaper globally. */
    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            persist(_config.value.copy(enabled = enabled))
        }
    }

    /** Replace the entire denylist. */
    suspend fun setDenylist(packages: Set<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            persist(_config.value.copy(denylist = packages))
        }
    }

    /** Add a single package to the denylist. Idempotent. */
    suspend fun addToDenylist(packageName: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            persist(_config.value.copy(denylist = _config.value.denylist + packageName))
        }
    }

    /** Remove a single package from the denylist. Idempotent. */
    suspend fun removeFromDenylist(packageName: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            persist(_config.value.copy(denylist = _config.value.denylist - packageName))
        }
    }

    private fun persist(next: ReaperConfig) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(next))
        if (file.exists()) runCatching { file.copyTo(bakFile, overwrite = true) }
        if (!tmp.renameTo(file)) {
            try { tmp.copyTo(file, overwrite = true) } finally { tmp.delete() }
        }
        _config.value = next
    }

    private suspend fun loadFromDisk(): ReaperConfig = withContext(Dispatchers.IO) {
        val primary = runCatching {
            if (!file.exists()) null
            else json.decodeFromString<ReaperConfig>(file.readText())
        }.getOrNull()
        if (primary != null) return@withContext primary

        if (bakFile.exists()) {
            Log.w(TAG, "loadFromDisk(): primary missing/corrupt — trying .bak")
            val backup = runCatching {
                json.decodeFromString<ReaperConfig>(bakFile.readText())
            }.getOrNull()
            if (backup != null) {
                Log.w(TAG, "loadFromDisk(): restored from .bak")
                runCatching { bakFile.copyTo(file, overwrite = true) }
                return@withContext backup
            }
        }
        ReaperConfig()
    }

    private companion object {
        const val FILE_NAME = "reaper_config.json"
    }
}
