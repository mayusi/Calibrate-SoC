package io.github.mayusi.calibratesoc.data.tunables

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent rolling log of every tune the user actually applied.
 *
 * Different from [TunableSnapshotStore]: that one is a per-session
 * journal that gets cleared on boot-revert. This one survives reboots
 * so the user can scroll back and see "what did I apply yesterday at
 * 14:30 that gave me good battery life?" — a usability win for the
 * iterate-and-compare workflow the app's built around.
 *
 * Cap is 100 entries — past that the oldest entries are dropped on
 * write. Keeps the file under ~50 KB even after a year of heavy use.
 */
@Singleton
class TuneHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File by lazy { File(context.filesDir, FILE_NAME) }
    private val mutex = Mutex()

    private val _entries = MutableStateFlow(loadBlocking())
    val entries: Flow<List<TuneHistoryEntry>> = _entries.asStateFlow()

    suspend fun append(entry: TuneHistoryEntry) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _entries.value
            // Keep newest first.
            val next = (listOf(entry) + current).take(MAX_ENTRIES)
            persist(next)
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { persist(emptyList()) }
    }

    private fun persist(next: List<TuneHistoryEntry>) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(TuneHistory(entries = next)))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        _entries.value = next
    }

    private fun loadBlocking(): List<TuneHistoryEntry> = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) emptyList()
                else json.decodeFromString<TuneHistory>(file.readText()).entries
            }.getOrElse { emptyList() }
        }
    }

    private companion object {
        const val FILE_NAME = "tune_history.json"
        const val MAX_ENTRIES = 100
    }
}

@Serializable
private data class TuneHistory(
    val version: Int = 1,
    val entries: List<TuneHistoryEntry> = emptyList(),
)

/**
 * One entry in the tune history.
 *
 * pathway records HOW the tune was applied so the user can read it
 * back later — "did I run this via Odin Settings or did it just write
 * straight to sysfs?"
 */
@Serializable
data class TuneHistoryEntry(
    val appliedAtMs: Long,
    val presetName: String,
    val presetDescription: String,
    val pathway: ApplyPathway,
    /** Free-form notes the user can attach via the UI. */
    val notes: String = "",
    /** Per-policyId max freq in kHz at the moment of apply. */
    val cpuPolicyMaxKhz: Map<Int, Int> = emptyMap(),
    val cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),
    val cpuPolicyGovernor: Map<Int, String> = emptyMap(),
    val gpuMaxHz: Long? = null,
    val gpuMinHz: Long? = null,
    val gpuGovernor: String? = null,
)

@Serializable
enum class ApplyPathway {
    /** Wrote directly via root shell — Magisk / KernelSU. */
    DIRECT_ROOT,
    /** Wrote via Settings.System keys (AYN handheld vendor path). */
    AYN_SETTINGS_KEY,
    /** Generated a script + user ran it via Odin Settings. */
    GENERATED_SCRIPT,
    /** Installed a boot script via Magisk service.d / KernelSU. */
    BOOT_SCRIPT_INSTALL,
    /** User asked us to set a boot-reminder notification. */
    BOOT_REMINDER_REGISTERED,
}
