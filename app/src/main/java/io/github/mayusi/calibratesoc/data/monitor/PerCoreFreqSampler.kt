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
    fun sample(): List<Int> {
        val cpuRoot = "/sys/devices/system/cpu".toPath()
        val cpus = runCatching {
            if (fs.exists(cpuRoot)) fs.list(cpuRoot) else emptyList()
        }.getOrDefault(emptyList())
            .filter { it.name.matches(Regex("cpu\\d+")) }
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }

        return cpus.map { cpu ->
            val freqPath = cpu / "cpufreq" / "scaling_cur_freq"
            runCatching {
                if (!fs.exists(freqPath)) 0 else fs.read(freqPath) { readUtf8() }.trim().toIntOrNull() ?: 0
            }.getOrDefault(0)
        }
    }
}
