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

private const val TAG = "ProfileRepository"

/**
 * File-backed user-profile store. One JSON document atomically
 * rewritten on every change; never queried by sub-field. The store
 * lives in the app's private files dir so root/Shizuku are not needed
 * to read it back at boot — important because BootRevertReceiver
 * consults it before deciding whether to revert or re-apply.
 *
 * Reads are cached in a StateFlow for UI consumption. Writes go
 * through a Mutex to keep concurrent edits from clobbering each other
 * — saving a new profile while the Accessibility service is updating
 * an override is a real race.
 */
@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File = File(context.filesDir, FILE_NAME)
    private val bakFile: File = File(context.filesDir, "$FILE_NAME.bak")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // BUG FIX (BUG 6): previously called loadBlocking() (runBlocking) directly
    // from the field initializer during Hilt singleton construction, blocking
    // the main thread and causing jank / potential ANR on first launch when the
    // file has grown (many profiles). Fixed by initialising with an empty store
    // and loading asynchronously in the init block.
    private val _store = MutableStateFlow(ProfileStore())
    val store: Flow<ProfileStore> = _store.asStateFlow()

    fun snapshot(): ProfileStore = _store.value

    init {
        scope.launch { _store.value = loadFromDisk() }
    }

    suspend fun saveProfile(profile: UserProfile) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _store.value
            val without = current.profiles.filterNot { it.id == profile.id }
            val next = current.copy(profiles = without + profile)
            persist(next)
        }
    }

    suspend fun deleteProfile(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _store.value
            val next = current.copy(
                profiles = current.profiles.filterNot { it.id == id },
                // Strip overrides that point at the deleted profile so
                // the Accessibility service doesn't try to apply a
                // missing target.
                perAppOverrides = current.perAppOverrides.filterValues { it != id },
            )
            persist(next)
        }
    }

    suspend fun setOverride(packageName: String, profileId: String?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _store.value
            val updated = if (profileId == null) {
                current.perAppOverrides - packageName
            } else {
                current.perAppOverrides + (packageName to profileId)
            }
            persist(current.copy(perAppOverrides = updated))
        }
    }

    private fun persist(next: ProfileStore) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(next))
        // Write a .bak of the current live file BEFORE overwriting it, so we
        // have a recovery copy if the rename/copy step is interrupted.
        if (file.exists()) {
            runCatching { file.copyTo(bakFile, overwrite = true) }
        }
        // Atomic on POSIX. Keeps the file from ever appearing half-written
        // to a concurrent reader (BootRevertReceiver, ProfileApplier).
        if (!tmp.renameTo(file)) {
            // renameTo failed (e.g. cross-filesystem move). Fall back to copy +
            // delete, but guarantee the temp file is removed even if copyTo throws.
            try {
                tmp.copyTo(file, overwrite = true)
            } finally {
                tmp.delete()
            }
        }
        _store.value = next
    }

    private suspend fun loadFromDisk(): ProfileStore = withContext(Dispatchers.IO) {
        // Try the primary file first.
        val primary = runCatching {
            if (!file.exists()) null
            else json.decodeFromString<ProfileStore>(file.readText())
        }.getOrNull()
        if (primary != null) return@withContext primary

        // Primary missing or corrupt — attempt restore from .bak.
        if (bakFile.exists()) {
            Log.w(TAG, "loadFromDisk(): primary file missing/corrupt — trying .bak")
            val backup = runCatching {
                json.decodeFromString<ProfileStore>(bakFile.readText())
            }.getOrNull()
            if (backup != null) {
                Log.w(TAG, "loadFromDisk(): restored from .bak successfully")
                // Restore bak → primary so the next persist works correctly.
                runCatching { bakFile.copyTo(file, overwrite = true) }
                return@withContext backup
            }
            Log.e(TAG, "loadFromDisk(): .bak also corrupt — starting fresh")
        }
        ProfileStore()
    }

    private companion object {
        const val FILE_NAME = "profiles.json"
    }
}
