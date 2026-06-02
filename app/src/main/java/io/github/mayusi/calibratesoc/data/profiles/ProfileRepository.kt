package io.github.mayusi.calibratesoc.data.profiles

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    private val mutex = Mutex()

    private val _store = MutableStateFlow(loadBlocking())
    val store: Flow<ProfileStore> = _store.asStateFlow()

    fun snapshot(): ProfileStore = _store.value

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
        // Atomic on POSIX. Keeps the file from ever appearing
        // half-written to a concurrent reader.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        _store.value = next
    }

    /** Read the store eagerly at construction so first-frame UI doesn't
     *  see a brief empty state. runBlocking is fine on Hilt singleton
     *  construction — Application is on the main thread but the file
     *  is tiny (<5 KB). */
    private fun loadBlocking(): ProfileStore = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) ProfileStore()
                else json.decodeFromString<ProfileStore>(file.readText())
            }.getOrElse { ProfileStore() }
        }
    }

    private companion object {
        const val FILE_NAME = "profiles.json"
    }
}
