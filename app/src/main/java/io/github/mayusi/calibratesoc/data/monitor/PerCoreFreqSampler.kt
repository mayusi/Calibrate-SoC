package io.github.mayusi.calibratesoc.data.monitor

import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads each CPU's current scaling frequency in kHz. Indexed by cpuN
 * directory name — same order CpuStatSampler emits load percentages.
 *
 * scaling_cur_freq is typically world-readable on Android 10+ stock
 * kernels (the file is in `sysfs_devices_system_cpu`, which AOSP
 * sepolicy grants `r_file_perms` to `untrusted_app`). If a particular
 * OEM kernel locks it down, we degrade to an empty list and the
 * dashboard renders "—" per lane.
 */
@Singleton
class PerCoreFreqSampler @Inject constructor(
    private val fs: FileSystem,
) {
    // CPU topology is fixed at boot; cache the sorted cpuN directory list
    // on first successful enumeration and reuse it every tick.
    private var cachedCpuDirs: List<okio.Path>? = null

    fun sample(): List<Int> {
        val cpus = cachedCpuDirs ?: run {
            val cpuRoot = "/sys/devices/system/cpu".toPath()
            val found = runCatching {
                fs.list(cpuRoot)
            }.getOrDefault(emptyList())
                .filter { it.name.matches(CPU_NAME) }
                .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
            // Only cache a non-empty result; retry next tick if enumeration fails.
            if (found.isNotEmpty()) cachedCpuDirs = found
            found
        }

        return cpus.map { cpu ->
            val freqPath = cpu / "cpufreq" / "scaling_cur_freq"
            runCatching {
                fs.read(freqPath) { readUtf8() }.trim().toIntOrNull() ?: 0
            }.getOrDefault(0)
        }
    }

    companion object {
        private val CPU_NAME = Regex("cpu\\d+")
    }
}
