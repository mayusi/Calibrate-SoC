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
 *
 * ## /proc/stat restriction on Android 12+
 *
 * On Android 12+ (hidepid enforced for untrusted_app, uid ~10000+), per-cpu
 * jiffie lines in /proc/stat are frozen/zeroed from the app's viewpoint even
 * though the aggregate "cpu  " line is still accurate. This makes every
 * per-core delta read as 0 — [sample] will return a list of zeros, not an
 * empty list, which silently poisons the AutoTDP engine.
 *
 * The caller ([CpuLoadSourceSelector]) is responsible for detecting this
 * condition and routing around it via [sampleFromRawText] (for root-sourced
 * /proc/stat) or the freq-proxy fallback. This class only handles parsing
 * and delta state; it does not know which source supplied the raw text.
 */
@Singleton
class CpuStatSampler @Inject constructor(
    private val fs: FileSystem,
) {
    // Two independent delta-state maps: one for the direct-read path and
    // one for the root-sourced path. Keeping them separate prevents a
    // source-switch mid-session from producing a wild delta (the prev snapshot
    // for path A would be compared against a tick from path B).
    private val previousDirect: MutableMap<Int, CpuSnapshot> = mutableMapOf()
    private val previousRoot: MutableMap<Int, CpuSnapshot> = mutableMapOf()

    /** Returns one load percentage per cpuN entry, in CPU-index order.
     *  On the very first call (no baseline) returns an empty list.
     *  Uses the direct /proc/stat read (app UID). */
    fun sample(): List<Int> {
        val raw = runCatching {
            fs.read("/proc/stat".toPath()) { readUtf8() }
        }.getOrNull() ?: return emptyList()
        return applyDelta(parseProcStat(raw), previousDirect)
    }

    /**
     * Parse [rawText] (the full content of /proc/stat, obtained from any
     * source — e.g. root shell via PServerWriter.executeShell("cat /proc/stat"))
     * and return per-core loads using a SEPARATE delta-state map from [sample].
     *
     * This is the entry point for the root-sourced path. It is intentionally
     * NOT annotated @Synchronized — callers (MonitorService) guarantee single-
     * threaded access per the coroutine dispatcher contract.
     */
    fun sampleFromRawText(rawText: String): List<Int> {
        return applyDelta(parseProcStat(rawText), previousRoot)
    }

    /**
     * Reset BOTH delta baselines. Call when the sample cadence changes
     * (1 Hz ↔ 4 Hz) so the first sample after the switch isn't a wild delta.
     */
    fun reset() {
        previousDirect.clear()
        previousRoot.clear()
    }

    // ── Shared parsing + delta logic ──────────────────────────────────────────

    /** Parse /proc/stat text into a map of cpu-index → raw snapshot. */
    internal fun parseProcStat(raw: String): Map<Int, CpuSnapshot> {
        val result = mutableMapOf<Int, CpuSnapshot>()
        for (line in raw.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            // Skip the aggregate line ("cpu  ..."), we only want cpuN.
            val name = line.substringBefore(' ')
            if (name == "cpu") continue
            val cpuIdx = name.removePrefix("cpu").toIntOrNull() ?: continue
            val parts = line.split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) continue
            val user   = parts.getOrElse(0) { 0 }
            val nice   = parts.getOrElse(1) { 0 }
            val system = parts.getOrElse(2) { 0 }
            val idle   = parts.getOrElse(3) { 0 }
            val iowait = parts.getOrElse(4) { 0 }
            val irq    = parts.getOrElse(5) { 0 }
            val softirq= parts.getOrElse(6) { 0 }
            val steal  = parts.getOrElse(7) { 0 }
            val total  = user + nice + system + idle + iowait + irq + softirq + steal
            val busy   = total - idle - iowait
            result[cpuIdx] = CpuSnapshot(total = total, busy = busy)
        }
        return result
    }

    /** Diff [current] against [stateMap], update [stateMap], return load list. */
    private fun applyDelta(
        current: Map<Int, CpuSnapshot>,
        stateMap: MutableMap<Int, CpuSnapshot>,
    ): List<Int> {
        val loads = mutableListOf<Int>()
        for ((idx, now) in current.toSortedMap()) {
            val prev = stateMap[idx]
            if (prev != null) {
                val totalDelta = now.total - prev.total
                val busyDelta  = now.busy  - prev.busy
                val pct = if (totalDelta > 0) {
                    ((busyDelta.toDouble() / totalDelta) * 100.0).toInt().coerceIn(0, 100)
                } else 0
                loads += pct
            }
            stateMap[idx] = now
        }
        return loads
    }

    internal data class CpuSnapshot(val total: Long, val busy: Long)

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
