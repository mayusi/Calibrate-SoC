package io.github.mayusi.calibratesoc.data.monitor

import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes per-core CPU load by diffing two consecutive reads of
 * /proc/stat. The first read primes the baseline; subsequent reads
 * yield a percentage in [0, 100].
 *
 * /proc/stat semantics:
 *   cpuN user nice system idle iowait irq softirq steal guest guest_nice
 * "Busy" = total - (idle + iowait). Load% = (busyDelta / totalDelta) * 100.
 *
 * This sampler is stateful — keep one instance per MonitorService so the
 * deltas are aligned to the dashboard's sample cadence.
 */
@Singleton
class CpuStatSampler @Inject constructor(
    private val fs: FileSystem,
) {
    private val previous: MutableMap<Int, CpuSnapshot> = mutableMapOf()

    /** Returns one load percentage per cpuN entry, in CPU-index order.
     *  On the very first call (no baseline) returns an empty list. */
    fun sample(): List<Int> {
        val current = readProcStat()
        val loads = mutableListOf<Int>()
        // We iterate in CPU-index order so the dashboard sparkline lanes
        // stay stable from sample to sample.
        for ((idx, now) in current.toSortedMap()) {
            val prev = previous[idx]
            if (prev != null) {
                val totalDelta = now.total - prev.total
                val busyDelta = now.busy - prev.busy
                val pct = if (totalDelta > 0) {
                    ((busyDelta.toDouble() / totalDelta) * 100.0).toInt().coerceIn(0, 100)
                } else 0
                loads += pct
            }
            previous[idx] = now
        }
        return loads
    }

    /** Reset the baseline. Call when the sample cadence changes (1 Hz ↔
     *  4 Hz) so the first sample after the switch isn't a wild delta. */
    fun reset() {
        previous.clear()
    }

    private fun readProcStat(): Map<Int, CpuSnapshot> {
        val raw = runCatching {
            fs.read("/proc/stat".toPath()) { readUtf8() }
        }.getOrNull() ?: return emptyMap()

        val result = mutableMapOf<Int, CpuSnapshot>()
        for (line in raw.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            // Skip the aggregate line ("cpu  ..."), we only want cpuN.
            val name = line.substringBefore(' ')
            if (name == "cpu") continue
            val cpuIdx = name.removePrefix("cpu").toIntOrNull() ?: continue
            val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) continue
            val user = parts.getOrElse(0) { 0 }
            val nice = parts.getOrElse(1) { 0 }
            val system = parts.getOrElse(2) { 0 }
            val idle = parts.getOrElse(3) { 0 }
            val iowait = parts.getOrElse(4) { 0 }
            val irq = parts.getOrElse(5) { 0 }
            val softirq = parts.getOrElse(6) { 0 }
            val steal = parts.getOrElse(7) { 0 }
            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val busy = total - idle - iowait
            result[cpuIdx] = CpuSnapshot(total = total, busy = busy)
        }
        return result
    }

    private data class CpuSnapshot(val total: Long, val busy: Long)
}
