package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPU load + current frequency sampler. Vendor-divergent:
 *   - Adreno: `gpubusy_percentage` (Snapdragon QCom kernels) or
 *     `gpubusy` (older). Both are typically world-readable.
 *   - Mali devfreq: `<devfreq>/load` exists on some kernels; we
 *     fall back to the gpu_busy_percentage shim where present.
 *   - MediaTek GED: load via /proc/mtk_mali — not implemented in v1
 *     (deferred to the MediaTek device adapter slice).
 *
 * Current frequency is read via `<devfreq>/cur_freq` on both Adreno and
 * Mali. Returns null if the kernel refuses the read (SELinux denial).
 */
@Singleton
class GpuLoadSampler @Inject constructor(
    private val fs: FileSystem,
) {
    fun sample(probe: GpuProbe?): Result {
        if (probe == null) return Result(null, null)
        val root = probe.rootPath.toPath()
        val load = when (probe.family) {
            GpuFamily.ADRENO -> readAdrenoLoad(root)
            GpuFamily.MALI -> readMaliLoad(root)
            GpuFamily.POWERVR_OR_MALI_MTK,
            GpuFamily.XCLIPSE,
            GpuFamily.UNKNOWN -> null
        }
        val freq = readLong(root / "devfreq" / "cur_freq")
        return Result(load, freq)
    }

    private fun readAdrenoLoad(root: okio.Path): Int? {
        // gpubusy_percentage emits a single integer (0..100). The older
        // gpubusy file emits two numbers ("busy total") which we'd have
        // to divide; this is the modern path on every Snapdragon kernel
        // shipped post-2021.
        return readInt(root / "gpubusy_percentage")
            ?: readInt(root / "gpu_busy_percentage")
            ?: readGpuBusyPair(root / "gpubusy")
    }

    private fun readMaliLoad(maliDir: okio.Path): Int? {
        return readInt(maliDir / "load")
            ?: readInt(maliDir / "utilization")
    }

    private fun readGpuBusyPair(p: okio.Path): Int? {
        val raw = readString(p) ?: return null
        val parts = raw.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (parts.size < 2 || parts[1] == 0L) return null
        return ((parts[0].toDouble() / parts[1]) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun readString(p: okio.Path): String? = runCatching {
        if (!fs.exists(p)) null else fs.read(p) { readUtf8() }.trim().ifBlank { null }
    }.getOrNull()

    /**
     * Adreno's `gpu_busy_percentage` emits values like "1 %" — the leading
     * integer plus a literal space and percent sign. Naive toIntOrNull
     * returns null on that. We strip everything from the first
     * non-digit character so "42 %" → 42, "100\n" → 100. Pure-number
     * files ("42") still work unchanged.
     */
    private fun readInt(p: okio.Path): Int? {
        val raw = readString(p) ?: return null
        val digits = raw.trim().takeWhile { it.isDigit() || it == '-' }
        return digits.toIntOrNull()
    }

    private fun readLong(p: okio.Path) = readString(p)?.toLongOrNull()

    data class Result(val loadPct: Int?, val freqHz: Long?)
}
