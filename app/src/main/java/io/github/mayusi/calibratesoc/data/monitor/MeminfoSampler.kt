package io.github.mayusi.calibratesoc.data.monitor

import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * /proc/meminfo parser. Reads MemTotal + MemAvailable in kB.
 * MemAvailable is what apps actually have room to allocate; it's more
 * honest than MemFree (which excludes reclaimable caches).
 */
@Singleton
class MeminfoSampler @Inject constructor(
    private val fs: FileSystem,
) {
    fun sample(): MemSample {
        val raw = runCatching {
            fs.read("/proc/meminfo".toPath()) { readUtf8() }
        }.getOrNull() ?: return MemSample(0, 0)

        var total = 0L
        var available = 0L
        for (line in raw.lineSequence()) {
            when {
                line.startsWith("MemTotal:") -> total = extractKb(line)
                line.startsWith("MemAvailable:") -> available = extractKb(line)
            }
        }
        return MemSample(total, available)
    }

    private fun extractKb(line: String): Long {
        // Lines look like: "MemTotal:       16384912 kB"
        val parts = line.split(Regex("\\s+"))
        return parts.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    data class MemSample(val totalKb: Long, val availableKb: Long)
}
