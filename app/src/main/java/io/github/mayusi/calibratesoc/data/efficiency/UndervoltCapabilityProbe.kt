package io.github.mayusi.calibratesoc.data.efficiency

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Capability tier — drives the UI contract
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Three honest tiers of undervolt capability available on a locked Snapdragon
 * handheld. The UI reads this once and NEVER shows a control that exceeds the
 * actual capability of the device.
 *
 * [REAL_VOLTAGE_TABLE]  — A custom or unlocked kernel exposes a per-OPP
 *   voltage table as a writable sysfs node (UV_mV_table, volt_table,
 *   vdd_levels, or similar). Root privilege is almost always required.
 *   This is RARE — stock AYN Odin 3 / Retroid Pocket 6 firmware does NOT
 *   expose this. The UI may show a real per-OPP voltage slider here.
 *
 * [KNEE_EQUIVALENT]     — No voltage table is available (stock firmware,
 *   CPR/PMIC/RPMh voltages are firmware-managed and signed). The efficiency
 *   gain is achieved by frequency-capping each cluster at its measured
 *   perf-per-watt KNEE, which achieves most of the thermal and battery
 *   benefit of actual undervolting by staying on the flat part of the V/F
 *   curve rather than the exponentially expensive top OPPs.
 *   This is the default path for every stock device.
 *
 * [READ_ONLY]           — Neither voltage table nor frequency-cap writes are
 *   available (e.g. unprivileged app-UID with no Shizuku, no PServer, no
 *   root). The engine can still measure and advise but cannot apply anything.
 */
enum class UndervoltCapabilityTier {
    REAL_VOLTAGE_TABLE,
    KNEE_EQUIVALENT,
    READ_ONLY,
}

// ─────────────────────────────────────────────────────────────────────────────
//  Probe result
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Read-only snapshot from [UndervoltCapabilityProbe.probe].
 *
 * [tier]                  — The highest capability tier available on this
 *   device+kernel combination. See [UndervoltCapabilityTier] for semantics.
 *
 * [cpuVoltTablePresent]   — True if a CPU voltage table sysfs path was found
 *   and is readable. Stock firmware: always false.
 *
 * [cpuVoltTableWritable]  — True only when the path exists AND a write-probe
 *   confirmed the process can write to it. On stock firmware the file either
 *   does not exist or is read-only (SELinux / file mode). Always false when
 *   [cpuVoltTablePresent] is false.
 *
 * [gpuVoltTablePresent]   — Equivalent for GPU (Adreno vdd_levels /
 *   kgsl volt_table path). False on stock.
 *
 * [gpuVoltTableWritable]  — Equivalent for GPU. Always false when
 *   [gpuVoltTablePresent] is false.
 *
 * [freqCapWritable]       — True when scaling_max_freq for at least one CPU
 *   policy is writable. On AYN devices with PServer or an active unlock
 *   script this is true; on a plain NONE-tier device it is false.
 *
 * [probeDetails]          — Human-readable per-path probe log for diagnostics
 *   and crash reports (PII-free: only sysfs paths, not values).
 *
 * HONESTY: none of the "writable" flags are set speculatively. They require
 * both a read success AND a write-probe confirmation. The probe NEVER actually
 * changes a voltage — write probes are a no-op re-write of the already-read
 * value, or a permission-only check via access(2).
 */
data class UndervoltCapability(
    val tier: UndervoltCapabilityTier,
    val cpuVoltTablePresent: Boolean,
    val cpuVoltTableWritable: Boolean,
    val gpuVoltTablePresent: Boolean,
    val gpuVoltTableWritable: Boolean,
    val freqCapWritable: Boolean,
    val probeDetails: List<PathProbeResult>,
) {
    companion object {
        /** Builds an honest [UndervoltCapability] from a list of raw path probe results. */
        fun fromProbeResults(
            results: List<PathProbeResult>,
            freqCapWritable: Boolean,
        ): UndervoltCapability {
            val cpuVoltPaths = results.filter { it.role == PathRole.CPU_VOLT_TABLE }
            val gpuVoltPaths = results.filter { it.role == PathRole.GPU_VOLT_TABLE }

            val cpuPresent  = cpuVoltPaths.any { it.readable }
            val cpuWritable = cpuVoltPaths.any { it.writable }
            val gpuPresent  = gpuVoltPaths.any { it.readable }
            val gpuWritable = gpuVoltPaths.any { it.writable }

            val tier = when {
                cpuWritable || gpuWritable -> UndervoltCapabilityTier.REAL_VOLTAGE_TABLE
                freqCapWritable            -> UndervoltCapabilityTier.KNEE_EQUIVALENT
                else                       -> UndervoltCapabilityTier.READ_ONLY
            }

            return UndervoltCapability(
                tier = tier,
                cpuVoltTablePresent  = cpuPresent,
                cpuVoltTableWritable = cpuWritable,
                gpuVoltTablePresent  = gpuPresent,
                gpuVoltTableWritable = gpuWritable,
                freqCapWritable      = freqCapWritable,
                probeDetails         = results,
            )
        }
    }
}

/** Classification of what a probed sysfs path represents. */
enum class PathRole {
    CPU_VOLT_TABLE,
    GPU_VOLT_TABLE,
}

/**
 * Result of probing one candidate voltage-table path.
 *
 * [path]     — The sysfs path that was probed.
 * [role]     — Whether it is a CPU or GPU voltage table.
 * [exists]   — True when the path was found in the filesystem.
 * [readable] — True when the path exists AND content could be read.
 * [writable] — True when [readable] is true AND a write-permission check
 *              (access(2) / file mode inspection) confirmed writability.
 *              NOT a destructive write — the probe never modifies the value.
 * [note]     — Optional diagnostic note (e.g. "SELinux denied", "not found").
 */
data class PathProbeResult(
    val path: String,
    val role: PathRole,
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val note: String = "",
)

// ─────────────────────────────────────────────────────────────────────────────
//  Probe implementation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Detects whether this device/kernel exposes a real per-OPP voltage table
 * that the app could (in principle) write.
 *
 * ARCHITECTURE: Pure sysfs read (via [FileSystem]) + permission check.
 * Does NOT write any voltage. Does NOT depend on Android context. Fully
 * unit-testable with [okio.fakefilesystem.FakeFileSystem].
 *
 * HONESTY: On stock AYN Odin 3 / Retroid Pocket 6 / most Snapdragon handhelds,
 * CPR (Core Power Reduction), PMIC, and RPMh manage per-OPP voltages in signed
 * firmware. The kernel does not expose a writable voltage table to userspace.
 * [probe] will return [UndervoltCapabilityTier.KNEE_EQUIVALENT] on these devices
 * as long as frequency-cap writes are available, or [UndervoltCapabilityTier.READ_ONLY]
 * when no write privilege is present.
 *
 * Custom kernels (e.g. some Kali NetHunter / Sultan / Sultan-inspired builds) do
 * expose UV_mV_table or equivalent, primarily on older Snapdragon 845/855/865
 * devices. This probe will detect those.
 *
 * @param fs Okio [FileSystem] — inject [FakeFileSystem] for unit tests.
 * @param freqCapWritable Whether the caller has confirmed scaling_max_freq is
 *        writable on this device (from [CapabilityReport.sysfsDirectlyWritable]
 *        or pserverSysfsLive). Passed in to keep this class Android-free.
 */
@Singleton
class UndervoltCapabilityProbe @Inject constructor(
    private val fs: FileSystem,
) {

    /**
     * Probe all known voltage-table candidate paths and return the combined
     * [UndervoltCapability]. Pass [freqCapWritable] = true when the
     * [CapabilityReport] confirms at least one CPU policy's scaling_max_freq
     * is writable (pserverSysfsLive || sysfsDirectlyWritable || Shizuku write
     * allowed).
     *
     * Safe to call on any thread; no coroutine required.
     */
    fun probe(freqCapWritable: Boolean = false): UndervoltCapability {
        val results = buildList {
            // ── CPU voltage table candidates ───────────────────────────────────
            // (1) UV_mV_table — Sultan kernel, some custom builds for SD845/855/865
            addAll(CPU_VOLT_TABLE_PATHS.map { path ->
                probePath(path, PathRole.CPU_VOLT_TABLE)
            })
            // (2) Per-CPU debugfs voltage table — some CAF kernels
            for (cpuIndex in 0..7) {
                val path = "/sys/kernel/debug/msm_core/cpu$cpuIndex/volt_table"
                add(probePath(path, PathRole.CPU_VOLT_TABLE))
            }

            // ── GPU voltage table candidates ───────────────────────────────────
            addAll(GPU_VOLT_TABLE_PATHS.map { path ->
                probePath(path, PathRole.GPU_VOLT_TABLE)
            })
        }

        return UndervoltCapability.fromProbeResults(
            results = results,
            freqCapWritable = freqCapWritable,
        )
    }

    // ─── Internal probe ───────────────────────────────────────────────────────

    /**
     * Probes a single path: existence → readability → write-permission.
     * Never writes any value. The write-permission check inspects the file's
     * Unix permission bits (owner/group/other) rather than attempting a write.
     */
    internal fun probePath(path: String, role: PathRole): PathProbeResult {
        val p = path.toPath()

        val exists = try { fs.exists(p) } catch (_: IOException) { false }
        if (!exists) {
            return PathProbeResult(path = path, role = role, exists = false,
                readable = false, writable = false, note = "not found")
        }

        // Try to read — a read-only root-owned path will fail here under SELinux.
        val content = try {
            fs.read(p) { readUtf8() }.trim()
        } catch (_: IOException) {
            return PathProbeResult(path = path, role = role, exists = true,
                readable = false, writable = false, note = "read denied (SELinux or permissions)")
        }

        if (content.isBlank()) {
            return PathProbeResult(path = path, role = role, exists = true,
                readable = false, writable = false, note = "empty — kernel module not loaded")
        }

        // Write-permission check: inspect file metadata via okio.
        // On a real device under app UID, voltage table files are always
        // root:root 0440 or 0444 → writable = false.
        // On a rooted device with a custom kernel that chmod'd the file to
        // 0644, writable = true.
        //
        // FakeFileSystem does not support metadata queries, so we fall back to
        // a best-effort java.io.File check when the okio metadata API is not
        // available (i.e. in unit tests the caller must set up the fake FS
        // appropriately — see [probePath] contract in tests).
        val writable = isWritable(path)

        return PathProbeResult(
            path = path,
            role = role,
            exists = true,
            readable = true,
            writable = writable,
            note = if (writable) "readable + writable" else "read-only (stock firmware)",
        )
    }

    /**
     * Checks whether the current process can write to [path].
     * Uses [java.io.File.canWrite] which reflects real POSIX permissions +
     * SELinux policy without performing an actual write. This is the same
     * approach used by the existing [ShizukuProbe]'s "no-op write" philosophy
     * applied to a permission check rather than an I/O round-trip.
     */
    private fun isWritable(path: String): Boolean =
        runCatching { java.io.File(path).canWrite() }.getOrDefault(false)

    // ─── Candidate sysfs paths ────────────────────────────────────────────────

    companion object {
        /**
         * Known CPU per-OPP voltage table paths from custom Snapdragon kernels.
         *
         * Checked in order; first readable+writable wins the tier.
         * All of these return read-only (or don't exist) on stock Qualcomm/OEM
         * firmware — the CPR subsystem owns the voltage and the driver does not
         * expose a userspace table.
         */
        val CPU_VOLT_TABLE_PATHS: List<String> = listOf(
            // Sultan kernel (SD845/855/865/888 custom builds)
            "/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table",
            "/sys/devices/system/cpu/cpufreq/policy4/UV_mV_table",
            "/sys/devices/system/cpu/cpufreq/policy7/UV_mV_table",
            // Some older CAF / LA kernels expose a direct cpuN path
            "/sys/devices/system/cpu/cpu0/cpufreq/UV_mV_table",
            // Generic debugfs voltage table (requires debugfs mounted)
            "/sys/kernel/debug/cpr3-regulator/vdd-cx/corner-0/ceiling_volt",
            // Some Kali/NetHunter builds
            "/sys/kernel/debug/regulator/vdd_cx/voltage",
        )

        /**
         * Known GPU per-OPP voltage / power-level voltage paths.
         *
         * Adreno voltage tables are behind KGSL. Stock firmware never exposes
         * them as writable; some rooted custom builds do.
         */
        val GPU_VOLT_TABLE_PATHS: List<String> = listOf(
            // Adreno KGSL: vdd_levels (Hz → uV table) — seen on Sultan and similar
            "/sys/class/kgsl/kgsl-3d0/vdd_levels",
            // Older Adreno / debugfs path
            "/sys/kernel/debug/kgsl/kgsl-3d0/volt_table",
            // Some devfreq-based Adreno builds
            "/sys/class/kgsl/kgsl-3d0/devfreq/vdd_levels",
        )
    }
}
