package io.github.mayusi.calibratesoc.data.tunables.writer

import android.util.Log
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuServiceConnection
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.util.readSysfsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku-tier sysfs writer. Executes writes via the [SysfsUserService] binder
 * which runs at shell UID (u:r:shell:s0) — more privileged than the app UID
 * but still subject to the device's vendor SELinux policy.
 *
 * HONESTY CONTRACT (non-negotiable):
 *   This writer only accepts a write for [path] if that path previously
 *   passed [ShizukuNodeCache.isCachedWritable] — meaning the no-op probe
 *   (read current value, write it back unchanged) succeeded via the shell
 *   binder. If the cache says the node is NOT writable (or the path was
 *   never probed), [write] returns [WriteResult.CapabilityDenied] with an
 *   honest message rather than silently failing or lying to the UI.
 *
 * Why the probe is essential:
 *   On stock Snapdragon devices Shizuku grants ADB-shell UID, but vendor
 *   SELinux policy still DENIES shell writes to cpufreq and Adreno nodes in
 *   many configurations. We CANNOT assume "shell can write everything" — that
 *   is provably false on common devices. The probe converts "shell might work"
 *   into "shell provably works on THESE exact nodes on THIS device".
 *
 * Read path: falls back to the app-UID fs path. Sysfs is world-readable on
 * most kernels, so the dashboard/monitor still sees live data at this tier
 * regardless of whether writes are permitted.
 */
@Singleton
class ShizukuWriter @Inject constructor(
    private val fs: FileSystem,
    private val connection: ShizukuServiceConnection,
    private val nodeCache: ShizukuNodeCache,
) : SysfsWriter {

    // ── Read (app UID — sysfs is world-readable) ──────────────────────────────

    override suspend fun read(id: TunableId): String? {
        if (id.kind != TunableKind.SYSFS) return null
        return withContext(Dispatchers.IO) {
            fs.readSysfsString(id.target.toPath())
        }
    }

    // ── canWrite — true only for probe-confirmed writable nodes ───────────────

    override suspend fun canWrite(id: TunableId): Boolean {
        if (id.kind != TunableKind.SYSFS) return false
        return nodeCache.isCachedWritable(id.target)
    }

    // ── write — routes through the UserService binder ─────────────────────────

    override suspend fun write(id: TunableId, value: String): WriteResult {
        if (id.kind != TunableKind.SYSFS) {
            return WriteResult.CapabilityDenied(
                id = id,
                reason = "ShizukuWriter handles SYSFS tunables only.",
            )
        }

        // Gate 1: has this node passed the per-node write probe?
        if (!nodeCache.isCachedWritable(id.target)) {
            return WriteResult.CapabilityDenied(
                id = id,
                reason = buildProbeFailureReason(id.target),
            )
        }

        // Gate 2: is the UserService actually connected?
        val svc = connection.service
            ?: return WriteResult.CapabilityDenied(
                id = id,
                reason = "Shizuku UserService not connected — Shizuku may have stopped.",
            )

        return withContext(Dispatchers.IO) {
            // Snapshot the previous value for the revert journal.
            val previous = runCatching {
                fs.readSysfsString(id.target.toPath())
            }.getOrNull()

            val errno = try {
                svc.writeSysfsNode(id.target, value)
            } catch (e: Throwable) {
                Log.e(TAG, "binder exception writing ${id.target}: ${e.message}")
                return@withContext WriteResult.Failed(id = id, error = e)
            }

            if (errno == 0) {
                Log.d(TAG, "wrote ${id.target} = $value (shell UID)")
                WriteResult.Success(id = id, previousValue = previous, newValue = value)
            } else {
                val msg = errnoMessage(errno, id.target)
                Log.w(TAG, "write rejected: ${id.target} errno=$errno: $msg")
                WriteResult.Rejected(id = id, errno = errno, message = msg)
            }
        }
    }

    // ── Probe integration (called by CapabilityProbe / ShizukuProbe) ──────────

    /**
     * Run the no-op write probe for [path] via the UserService and cache the
     * result. Returns true when the shell CAN write this node.
     *
     * This is the load-bearing honesty test. The no-op technique: we read the
     * current value and write it back unchanged, so kernel state is always
     * byte-identical after the probe — success or failure.
     */
    suspend fun probeNode(path: String): Boolean {
        return nodeCache.probeAndCache(path) ?: false
    }

    /**
     * Probe all of [paths] and return the subset that is shell-writable on
     * this device. The return value is intentionally a Set<String> (not all-
     * or-nothing) because a device may allow shell writes to GPU devfreq but
     * deny cpufreq — we surface the union of what actually works.
     */
    suspend fun probeAll(paths: List<String>): Set<String> {
        nodeCache.probeAll(paths)
        return paths.filter { nodeCache.isCachedWritable(it) }.toSet()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildProbeFailureReason(path: String): String {
        val cached = nodeCache.getCached(path)
        return when {
            cached == false ->
                "Shizuku is connected but this device's kernel denies shell writes to $path " +
                    "(vendor SELinux policy). Monitoring + vendor settings only for this node."
            else ->
                "This node has not been probed yet. Run the Shizuku capability check first."
        }
    }

    private fun errnoMessage(errno: Int, path: String): String = when (errno) {
        13   -> "EACCES: SELinux or DAC denied shell write to $path"
        1    -> "EPERM: Operation not permitted for $path"
        5    -> "EIO: I/O error writing $path"
        2    -> "ENOENT: $path does not exist on this device"
        else -> "Errno $errno writing $path"
    }

    private companion object {
        const val TAG = "ShizukuWriter"
    }
}
