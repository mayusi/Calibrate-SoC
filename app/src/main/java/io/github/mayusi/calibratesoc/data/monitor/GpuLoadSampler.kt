package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegedSysfsReader
import io.github.mayusi.calibratesoc.data.util.readSysfsString
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.concurrent.ConcurrentHashMap
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
 * Mali.
 *
 * ## Cross-device fix: privileged-read fallback (RP6 + Odin 3)
 *
 * On the Retroid Pocket 6 (and the same class of Snapdragon device) the
 * Adreno telemetry nodes — `/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq`,
 * `gpu_busy_percentage`, `gpubusy` — are **SELinux-denied to the app UID**
 * (even `adb shell cat` is denied in the app's `untrusted_app` domain). The
 * app's own `open()` (what Okio does under the hood) gets `EACCES`, so the
 * direct read returns null and the HUD/Dashboard previously showed "--" for
 * GPU clock + load.
 *
 * This is the SAME root cause the capability/AutoTDP layer already solves for
 * the GPU devfreq min/max bounds via [PrivilegedSysfsReader.catOrNull] (a
 * PServer-root `cat`). We REUSE that exact reader here: when the direct
 * app-UID read returns null we fall back to the privileged `cat`. If even the
 * privileged read fails (no PServer / genuinely unreadable), the value stays
 * null → "--" (honest absence, never a fake 0).
 *
 * To keep this cheap on the 1 Hz hot path we **probe each node's direct
 * readability ONCE** (see [directReadable]): the very first time a node reads
 * fine directly we mark it direct-only and never touch the privileged path
 * for it again; the first time it's app-UID-denied we mark it privileged and
 * go straight to the (memoised) [PrivilegedSysfsReader] thereafter. So the
 * common world-readable case (most devices) pays zero IPC, and the denied
 * case never re-probes the failed direct `open()` every tick.
 */
@Singleton
class GpuLoadSampler @Inject constructor(
    private val fs: FileSystem,
    /**
     * Privileged-read fallback for GPU nodes the app UID cannot read directly
     * (RP6/Odin kgsl SELinux denial). Null in unit tests that construct
     * `GpuLoadSampler(fs)` directly — when null we behave exactly as before
     * (Okio direct read only, null on denial). Hilt always injects the real
     * [PrivilegedSysfsReader] singleton in production.
     */
    private val privilegedReader: PrivilegedSysfsReader? = null,
) {
    /**
     * Per-node read-source capability cache, probed once per node then reused on
     * the hot path. Concurrent because [sample] runs on the shared
     * Dispatchers.IO pool. See [ReadSource] for the three resolved states.
     */
    private val readSource = ConcurrentHashMap<String, ReadSource>()

    /** How a given GPU node is read once its readability has been probed. */
    private enum class ReadSource {
        /** App UID can read it directly via Okio — never escalate to privileged. */
        DIRECT,

        /** App UID is denied but the privileged PServer `cat` works — use that. */
        PRIVILEGED,

        /**
         * Neither the app UID nor the privileged path can read it (genuinely
         * absent, or unreadable even by PServer — e.g. RP6 with no PServer).
         * Stop probing entirely: every tick returns null → "--" (honest).
         */
        UNAVAILABLE,
    }

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
        //
        // RP6 honesty fix: the kgsl gpu_busy_percentage node is SELinux-
        // permissive-logged here and can return an out-of-range or NEGATIVE
        // value (e.g. a signed -EINVAL-style sentinel) when the GPU is in a
        // transient state. A busy% is physically 0..100, so we clamp the
        // single-integer reads through [readPct]: anything outside 0..100
        // (negative or garbage) is rejected to null so the HUD shows "--"
        // rather than an impossible "-53%". The two-number gpubusy ratio is
        // already clamped in [readGpuBusyPair].
        return readPct(root / "gpubusy_percentage")
            ?: readPct(root / "gpu_busy_percentage")
            ?: readGpuBusyPair(root / "gpubusy")
    }

    private fun readMaliLoad(maliDir: okio.Path): Int? {
        return readPct(maliDir / "load")
            ?: readPct(maliDir / "utilization")
    }

    private fun readGpuBusyPair(p: okio.Path): Int? {
        val raw = readString(p) ?: return null
        val parts = raw.split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        if (parts.size < 2 || parts[1] == 0L) return null
        return ((parts[0].toDouble() / parts[1]) * 100.0).toInt().coerceIn(0, 100)
    }

    /**
     * Read a GPU busy-percentage node HONESTLY, returning null (→ HUD "--") for
     * anything that isn't a real 0..100 utilisation. Handles the two formats the
     * kgsl/devfreq nodes emit across devices:
     *
     *  - single value:  "42", "42 %", "42\n"  → 42
     *  - two-number ratio (busy total / busy_max): "53 100"  → 53  (= 53/100*100)
     *
     * RP6 honesty: the node is SELinux-permissive-logged and can yield a NEGATIVE
     * sentinel or an out-of-range number. A busy% is physically 0..100, so any
     * value outside that range is rejected to null instead of being rendered as an
     * impossible "-53%". A genuine 0 IS a valid reading (idle GPU) and is kept.
     */
    private fun readPct(p: okio.Path): Int? {
        val raw = readString(p)?.trim() ?: return null
        val nums = raw.split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        val pct: Long = when {
            // Two-number "busy total" ratio (e.g. "53 100") → busy/total * 100.
            nums.size >= 2 && nums[1] > 0L ->
                ((nums[0].toDouble() / nums[1]) * 100.0).toLong()
            // Single leading integer (may carry a trailing " %" or newline).
            nums.isNotEmpty() -> nums[0]
            else -> return null
        }
        // Honest range gate: reject negatives / impossible values → null ("--").
        return if (pct in 0..100) pct.toInt() else null
    }

    /**
     * Read a GPU sysfs node, preferring the cheap direct Okio read and falling
     * back to a privileged PServer-root `cat` ONLY when the app UID is denied.
     * Per-node read source is probed once and cached (see [readSource]) so the
     * hot 1 Hz path never re-probes a resolved node:
     *
     *  - [ReadSource.DIRECT]      → direct Okio read only (no IPC).
     *  - [ReadSource.PRIVILEGED]  → privileged `cat` only (skip the doomed open()).
     *  - [ReadSource.UNAVAILABLE] → return null with no work (genuinely unreadable).
     *  - Unprobed                 → try direct; if it works cache DIRECT; else try
     *    the privileged fallback — cache PRIVILEGED if it yields a value, else
     *    UNAVAILABLE so we stop hitting the failed paths every tick.
     *
     * Honesty: when neither path yields a value the result is null and the
     * caller renders "--" — never a fabricated 0.
     */
    private fun readString(p: okio.Path): String? {
        val key = p.toString()
        return when (readSource[key]) {
            ReadSource.DIRECT -> fs.readSysfsString(p)
            ReadSource.PRIVILEGED -> privilegedReader?.catOrNull(key)
            ReadSource.UNAVAILABLE -> null
            null -> {
                val direct = fs.readSysfsString(p)
                if (direct != null) {
                    readSource[key] = ReadSource.DIRECT
                    return direct
                }
                // Direct read failed (missing node or SELinux EACCES). Try the
                // privileged fallback once. catOrNull() is itself cheap — it
                // returns null with zero IPC when PServer is unavailable.
                val privileged = privilegedReader?.catOrNull(key)
                readSource[key] =
                    if (privileged != null) ReadSource.PRIVILEGED else ReadSource.UNAVAILABLE
                privileged
            }
        }
    }

    private fun readLong(p: okio.Path) = readString(p)?.toLongOrNull()

    data class Result(val loadPct: Int?, val freqHz: Long?)

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
