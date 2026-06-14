package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain file-based sysfs writer for nodes that the one-time unlock script has
 * made world-writable (chmod 666).  No su / libsu / Shizuku required — the
 * kernel grants the write because the node's DAC permissions allow our UID.
 *
 * Used by [WriterRegistry] for the "unlocked but no Magisk" tier:
 *   - unlock script ran → cpufreq, GPU devfreq, DDR devfreq, I/O queue,
 *     input-boost, Adreno extras, CPU-gov tunables are chmod 666
 *   - those nodes are routed here instead of [NoopWriter]
 *
 * `printf %s value > path` semantics are approximated as:
 *   File.writeText(value)  (no trailing newline — same as printf %s)
 * The write is wrapped in a try/catch so any remaining 444-mode node
 * produces a clean [WriteResult.Rejected] instead of a crash.
 */
@Singleton
class UnlockedFileWriter @Inject constructor() : SysfsWriter {

    override suspend fun read(id: TunableId): String? {
        if (id.kind != TunableKind.SYSFS) return null
        return withContext(Dispatchers.IO) {
            runCatching { File(id.target).readText().trim().ifBlank { null } }.getOrNull()
        }
    }

    override suspend fun canWrite(id: TunableId): Boolean {
        if (id.kind != TunableKind.SYSFS) return false
        return withContext(Dispatchers.IO) {
            val f = File(id.target)
            f.exists() && f.canWrite()
        }
    }

    override suspend fun write(id: TunableId, value: String): WriteResult {
        if (id.kind != TunableKind.SYSFS) {
            return WriteResult.CapabilityDenied(id, "UnlockedFileWriter handles SYSFS only.")
        }
        return withContext(Dispatchers.IO) {
            val file = File(id.target)
            val previous = runCatching { file.readText().trim().ifBlank { null } }.getOrNull()
            val result = runCatching {
                // printf %s semantics: no trailing newline.
                file.writeText(value)
            }
            if (result.isSuccess) {
                WriteResult.Success(id, previous, value)
            } else {
                val msg = result.exceptionOrNull()?.message ?: "File write failed"
                // errno is unavailable from Java — surface the exception class as a hint.
                WriteResult.Rejected(id = id, errno = null, message = msg)
            }
        }
    }
}
