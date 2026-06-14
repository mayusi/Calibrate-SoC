package io.github.mayusi.calibratesoc.data.tunables

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only journal of every tunable write the app has performed since
 * the last successful boot-revert. Persisted to the app's private files
 * dir so neither root nor Shizuku is required to read it back at boot.
 *
 * Concurrency: writes can come from any coroutine (Tune UI, Profile
 * engine, benchmark watchdog), so we serialize through a Mutex. Reads
 * are not synchronized — the file is whole-rewritten on each append, so
 * a stale read is at worst missing the very latest entry.
 *
 * Invariant: the journal is cleared by [TunableWriter] AFTER the boot
 * receiver successfully replays it. If replay fails partway, the
 * surviving entries stay so the next reboot/launch can try again.
 */
@Singleton
class TunableSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fs: FileSystem,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    suspend fun read(): SnapshotJournal = withContext(Dispatchers.IO) {
        runCatching {
            val path = file.toOkioPath()
            if (!fs.exists(path)) SnapshotJournal()
            else json.decodeFromString<SnapshotJournal>(fs.read(path) { readUtf8() })
        }.getOrElse { SnapshotJournal() }
    }

    /**
     * Append a single snapshot. Coalesces duplicates: if we've already
     * recorded a snapshot for `id` in this session, we KEEP the older
     * `previousValue` (it represents the original stock value before any
     * of our writes). This is what makes boot-revert correct after
     * multiple consecutive writes to the same tunable.
     */
    suspend fun append(snapshot: TunableSnapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = read()
            val alreadyHave = current.entries.any { it.id == snapshot.id }
            val next = if (alreadyHave) current else current.copy(entries = current.entries + snapshot)
            writeAll(next)
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { writeAll(SnapshotJournal()) }
    }

    private fun writeAll(journal: SnapshotJournal) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(journal))
        // Atomic on POSIX — neither half-written nor half-deleted is
        // visible to a concurrent reader. Critical because the boot
        // receiver fires while the journal is still hot.
        if (!tmp.renameTo(file)) {
            // renameTo failed (e.g. cross-filesystem move). Fall back to copy +
            // delete, but guarantee the temp file is removed even if copyTo throws.
            try {
                tmp.copyTo(file, overwrite = true)
            } finally {
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "last_known_good.json"
    }
}
