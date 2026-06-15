package io.github.mayusi.calibratesoc.data.monitor

/**
 * Strategy for reading per-core CPU load percentages.
 *
 * On Android 12+ the kernel's proc_stat hidepid restrictions freeze per-cpu
 * jiffies for untrusted_app UIDs, making every /proc/stat delta read as 0%.
 * This interface lets CpuStatSampler swap in the appropriate source at runtime
 * without changing the decision-engine contract.
 *
 * Three implementations (tried in order by [CpuLoadSourceSelector]):
 *   1. [RootProcStatSource]  — reads /proc/stat AS ROOT via PServerWriter.executeShell.
 *      Returns true per-core busy-time when PServer is live. Stateful: keeps a
 *      previous-snapshot map so deltas align to the sampler's tick cadence.
 *   2. [DirectProcStatSource] — reads /proc/stat directly from the app process.
 *      Works on Android 11 and below, or on devices where the OEM left hidepid off.
 *      Returns an empty list (not 0%) when the deltas are all-zero (frozen).
 *   3. [FreqProxySource] — reads scaling_cur_freq / scaling_max_freq per core.
 *      A coarse proxy: frequency ratio ≈ load, not real jiffie-based load.
 *      Labelled distinctly in [CpuLoadReading.source] so callers can flag it.
 *
 * [CpuLoadReading.source] is carried in the sample so the HUD and AutoTDP
 * engine can make informed decisions when they receive proxy data.
 */
data class CpuLoadReading(
    /** Per-core load [0..100] in CPU-index order. Empty = no data yet (first tick). */
    val loads: List<Int>,
    /** How the data was obtained. */
    val source: Source,
) {
    enum class Source {
        /** Real /proc/stat jiffies read as root via PServer. */
        ROOT_PROC_STAT,
        /** /proc/stat read from the app UID (works only when hidepid is off). */
        DIRECT_PROC_STAT,
        /** scaling_cur_freq ÷ scaling_max_freq — a coarse frequency-ratio proxy. */
        FREQ_PROXY,
        /** No data available at all (first tick / all paths failed). */
        UNAVAILABLE,
    }
}
