package io.github.mayusi.calibratesoc.data.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-node write-probe cache for the Shizuku shell tier.
 *
 * HONESTY CORE: A node appears in the writable set ONLY if the no-op probe
 * succeeded on THIS device via the shell-UID binder. We never assume
 * "shell writes everything" — that is false on stock Snapdragon kernels
 * where vendor SELinux policy denies shell access to many cpufreq/GPU nodes.
 *
 * Probe technique (no-op write):
 *   1. Call [ISysfsUserService.probeWritable] → the service reads the node's
 *      current value and writes it back byte-identical.
 *   2. If the return code is 0 → the shell UID CAN write this node on this
 *      device. Cache as writable = true.
 *   3. If the return code is non-zero (EACCES/EPERM/etc.) → cache as
 *      writable = false. The node will be routed to the script path instead.
 *   4. If the service is not connected → treat as UNKNOWN (not writable).
 *      Probes are retried on next connection.
 *
 * Cache lifetime: in-memory, per-app-process. The probe is re-run after
 * a Shizuku reconnect (service = null flushes the cache for new paths).
 * Previously probed paths are retained so the UI doesn't flicker.
 */
@Singleton
class ShizukuNodeCache @Inject constructor(
    private val connection: ShizukuServiceConnection,
) {
    private val cacheMutex = Mutex()

    /**
     * Map of sysfs path → probe result.
     *   true  = shell CAN write this node on this device (probe succeeded)
     *   false = shell CANNOT write this node (probe denied)
     */
    private val cache: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * Returns true only if [path] has a CACHED probe result of writable = true.
     * Does NOT trigger a new probe — call [probeAndCache] explicitly when the
     * connection first becomes available.
     */
    fun isCachedWritable(path: String): Boolean {
        return cache[path] == true
    }

    /**
     * Returns the cached result, or null if this path has never been probed.
     */
    fun getCached(path: String): Boolean? = cache[path]

    /**
     * Run the no-op probe for [path] via the UserService, cache and return result.
     *
     * Safe to call concurrently — mutex serialises per-path probes.
     * Must be called from a coroutine (suspends on I/O and binder call).
     *
     * Returns null when the UserService is not connected (probe not possible).
     */
    suspend fun probeAndCache(path: String): Boolean? = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            // Return cached result if already known.
            cache[path]?.let { return@withContext it }

            val svc = connection.service
            if (svc == null) {
                Log.w(TAG, "probe($path): UserService not connected — skipping")
                return@withContext null
            }

            val errno = try {
                svc.probeWritable(path)
            } catch (e: Throwable) {
                Log.e(TAG, "probe($path): binder exception ${e.message}")
                return@withContext null
            }

            val writable = (errno == 0)
            cache[path] = writable
            Log.i(TAG, "probe($path): ${if (writable) "WRITABLE" else "DENIED (errno=$errno)"}")
            writable
        }
    }

    /**
     * Probe all paths in [paths] concurrently (still serialised per-path by mutex).
     * Existing cached entries are skipped.
     */
    suspend fun probeAll(paths: List<String>) {
        paths.forEach { probeAndCache(it) }
    }

    /**
     * Evict all cached results. Call this when the Shizuku connection drops,
     * because a reconnect may land on a different kernel/SELinux context
     * (e.g. after a reboot with a new kernel).
     */
    fun clearCache() {
        cache.clear()
        Log.i(TAG, "Node probe cache cleared")
    }

    /** Return a snapshot of the current cache for diagnostics/debugging. */
    fun snapshot(): Map<String, Boolean> = cache.toMap()

    private companion object {
        const val TAG = "ShizukuNodeCache"
    }
}
