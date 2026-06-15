package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the best available source for per-core CPU load and returns a
 * [CpuLoadReading] that honestly labels which source was used.
 *
 * ## Fallback chain (tried in order on every tick)
 *
 * 1. **ROOT_PROC_STAT** — `cat /proc/stat` via [PServerWriter.executeShell].
 *    Only attempted when [PServerWriter.transactableNow] is true (memoised
 *    probe; no overhead when PServer is absent). Passes the raw text to
 *    [CpuStatSampler.sampleFromRawText] which maintains its own delta state.
 *    Gives true busy-time jiffies even under Android 12+ proc restrictions.
 *
 * 2. **DIRECT_PROC_STAT** — [CpuStatSampler.sample] reads /proc/stat from the
 *    app UID. Works on Android 11 and below, or OEM kernels with hidepid off.
 *    Validated by checking that at least one core reports nonzero load in the
 *    first tick where a baseline exists. If every core is 0 AND frequencies are
 *    measurably nonzero (indicating real activity), the result is treated as a
 *    frozen read and we fall through to FREQ_PROXY.
 *
 * 3. **FREQ_PROXY** — `scaling_cur_freq / scaling_max_freq` per core.
 *    A coarse approximation: high frequency ≈ high load, but this cannot detect
 *    a core running at max frequency while waiting on memory. Better than
 *    silently reporting 0%. The [CpuLoadReading.source] is explicitly
 *    [CpuLoadReading.Source.FREQ_PROXY] so the engine and HUD can handle it.
 *
 * ## Thread safety
 * Called from Dispatchers.IO inside MonitorService's coroutine scope. All
 * state is confined to that single coroutine; no synchronisation is needed.
 *
 * ## Honesty contract
 * When NONE of the sources can return data (first tick before any baseline,
 * or all paths genuinely unavailable), returns [CpuLoadReading.Source.UNAVAILABLE]
 * with an empty list. The engine interprets an empty perCoreLoadPct as "blind"
 * and holds (existing behaviour), which is honest — it is not the same as
 * "all cores at 0%".
 */
@Singleton
class CpuLoadSourceSelector @Inject constructor(
    private val cpuStatSampler: CpuStatSampler,
    private val pServerWriter: PServerWriter,
    private val fs: FileSystem,
) {
    // Cache the topology list so we don't enumerate /sys/devices/system/cpu every tick.
    private var cachedCpuPaths: List<okio.Path>? = null

    // Remember whether we observed frozen direct reads so we stop retrying for this session.
    // Reset on reset() so a cadence change re-evaluates.
    @Volatile private var directFrozenDetected = false

    /**
     * Obtain per-core CPU loads using the best available source.
     * This must be called from a single coroutine; it is NOT thread-safe.
     */
    suspend fun sample(): CpuLoadReading {
        // The chain NEVER dead-ends on a higher rung: if a rung cannot produce a
        // usable reading on this tick it falls through to the next. Only a genuine
        // first-tick-no-baseline on a rung that DID produce raw data is allowed to
        // skip to the proxy floor for this single tick. The freq-proxy (rung 3) is
        // the guaranteed floor — scaling_cur/max_freq are app-readable on every
        // device — so we should essentially never return UNAVAILABLE in steady state.

        // ── Rung 1: root /proc/stat via PServer ───────────────────────────────
        // Treat ANY nonzero PServer status, null result, or blank stdout as a
        // miss and fall through (do NOT early-return UNAVAILABLE — that was the
        // bug that pinned PServer devices to "load unavailable" forever).
        if (pServerWriter.transactableNow()) {
            val result = runCatching { pServerWriter.executeShell("cat /proc/stat") }.getOrNull()
            val stdout = result?.takeIf { it.first == 0 }?.second
            if (!stdout.isNullOrBlank()) {
                val loads = cpuStatSampler.sampleFromRawText(stdout)
                if (loads.isNotEmpty() && loads.any { it > 0 }) {
                    return CpuLoadReading(loads, CpuLoadReading.Source.ROOT_PROC_STAT)
                }
                // loads empty = first root tick (baseline now primed); loads all-zero
                // = either truly idle OR the root read is also frozen. Either way we
                // fall through to the proxy floor this tick instead of dead-ending.
            }
            // else: PServer miss — fall through.
        }

        // ── Rung 2: direct /proc/stat (app UID) ───────────────────────────────
        if (!directFrozenDetected) {
            val loads = cpuStatSampler.sample()
            if (loads.isNotEmpty()) {
                if (loads.all { it == 0 } && freqsLookBusy()) {
                    // All-zero loads while frequencies are measurably nonzero →
                    // Android 12+ proc_stat hidepid restriction. Latch it off and
                    // fall through to FREQ_PROXY for the rest of the session.
                    directFrozenDetected = true
                } else {
                    return CpuLoadReading(loads, CpuLoadReading.Source.DIRECT_PROC_STAT)
                }
            }
            // loads empty = first direct tick (baseline primed) → fall through.
        }

        // ── Rung 3: scaling_cur_freq / scaling_max_freq proxy (guaranteed floor) ─
        val proxyLoads = sampleFreqProxy()
        if (proxyLoads.isNotEmpty()) {
            return CpuLoadReading(proxyLoads, CpuLoadReading.Source.FREQ_PROXY)
        }

        // Only reachable if the cpu topology itself could not be enumerated.
        return CpuLoadReading(emptyList(), CpuLoadReading.Source.UNAVAILABLE)
    }

    /**
     * Reset both the stat-sampler baselines and the frozen-detection flag.
     * Call when the sample cadence changes (same contract as [CpuStatSampler.reset]).
     */
    fun reset() {
        cpuStatSampler.reset()
        directFrozenDetected = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * True when at least one core is running well above idle frequency.
     * Used to distinguish "all cores really idle" from "kernel returned frozen zeros".
     * Threshold: any core at >= 50% of its reported maximum frequency.
     */
    private fun freqsLookBusy(): Boolean {
        val cpus = cpuPaths()
        for (cpu in cpus) {
            val cur = readLongSysfs(cpu / "cpufreq" / "scaling_cur_freq") ?: continue
            val max = readLongSysfs(cpu / "cpufreq" / "scaling_max_freq") ?: continue
            if (max > 0 && cur * 2 >= max) return true
        }
        return false
    }

    /**
     * Compute per-core load as (scaling_cur_freq / scaling_max_freq) * 100.
     * Returns values in cpu-index order matching [CpuStatSampler.sample].
     * Returns empty list when the cpu directory cannot be enumerated.
     */
    private fun sampleFreqProxy(): List<Int> {
        return cpuPaths().map { cpu ->
            val cur = readLongSysfs(cpu / "cpufreq" / "scaling_cur_freq") ?: return@map 0
            val max = readLongSysfs(cpu / "cpufreq" / "scaling_max_freq") ?: return@map 0
            if (max <= 0) 0
            else ((cur.toDouble() / max) * 100.0).toInt().coerceIn(0, 100)
        }
    }

    /** Returns the sorted list of /sys/devices/system/cpu/cpuN directories. */
    private fun cpuPaths(): List<okio.Path> {
        return cachedCpuPaths ?: run {
            val cpuRoot = "/sys/devices/system/cpu".toPath()
            val found = runCatching { fs.list(cpuRoot) }
                .getOrDefault(emptyList())
                .filter { it.name.matches(CPU_NAME) }
                .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
            if (found.isNotEmpty()) cachedCpuPaths = found
            found
        }
    }

    private fun readLongSysfs(path: okio.Path): Long? = runCatching {
        fs.read(path) { readUtf8() }.trim().toLongOrNull()
    }.getOrNull()

    companion object {
        private val CPU_NAME = Regex("cpu\\d+")
    }
}
