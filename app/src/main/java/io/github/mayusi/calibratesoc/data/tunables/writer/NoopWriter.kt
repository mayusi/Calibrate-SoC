package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only writer for the no-privilege tier. Reads work (sysfs is
 * world-readable on most paths), every write is a deterministic deny.
 *
 * Used as the fallback in [WriterRegistry] so callers never have to
 * null-check the writer they got back — the UI just never surfaces
 * write controls when the privilege tier is NONE.
 */
@Singleton
class NoopWriter @Inject constructor(
    private val fs: FileSystem,
) : SysfsWriter {

    override suspend fun read(id: TunableId): String? = runCatching {
        val path = id.target.toPath()
        if (!fs.exists(path)) null
        else fs.read(path) { readUtf8() }.trim().ifBlank { null }
    }.getOrNull()

    override suspend fun canWrite(id: TunableId): Boolean = false

    override suspend fun write(id: TunableId, value: String): WriteResult =
        WriteResult.CapabilityDenied(
            id = id,
            reason = "No privilege tier available (need root or Shizuku).",
        )
}
