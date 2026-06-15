package io.github.mayusi.calibratesoc.data.capability

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuServiceConnection
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether Shizuku is installed, running, and has granted us
 * permission. The "sysfs write actually works" question is answered by
 * [probeSysfsWriteAllowed] which performs a NO-OP re-write of an already
 * known CPU policy value via the Shizuku UserService binder.
 *
 * Why a no-op write: the value we write is exactly the value we just read,
 * so the kernel state is unchanged. If SELinux denies the write we learn
 * that without changing anything; if it succeeds, the device's settings
 * are still byte-identical to what they were before the probe. This is the
 * load-bearing test that decides whether the "Shizuku tier" exposes any
 * real clocking controls or degrades to vendor-key + monitoring only.
 *
 * Phase 2 change: [probeSysfsWriteAllowed] now uses the [ShizukuServiceConnection]
 * UserService binder to perform the write as shell UID. The app-UID fallback
 * is retained as a quick-check (if app UID can write, shell definitely can too),
 * but the definitive answer comes from the shell process via the binder.
 */
@Singleton
class ShizukuProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sysfsProber: SysfsProber,
    private val connection: ShizukuServiceConnection,
    private val nodeCache: ShizukuNodeCache,
) {

    fun probe(): ShizukuStatus {
        val installed = isShizukuInstalled()
        if (!installed) {
            return ShizukuStatus(
                installed = false,
                running = false,
                permissionGranted = false,
                sysfsWriteAllowed = null,
            )
        }

        val running = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!running) {
            // Connection dropped — clear the node cache so stale probe results
            // don't carry over to the next session or kernel update.
            nodeCache.clearCache()
            connection.disconnect()
            return ShizukuStatus(
                installed = true,
                running = false,
                permissionGranted = false,
                sysfsWriteAllowed = null,
            )
        }

        val granted = try {
            !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        if (granted) {
            // Ensure the UserService is connected so probes can proceed.
            connection.ensureConnected()
        }

        // We never probe the kernel from this synchronous probe call — that
        // would pop the permission dialog at startup. The write-probe is opt-in:
        // callers invoke probeSysfsWriteAllowed() after the user grants permission.
        return ShizukuStatus(
            installed = true,
            running = true,
            permissionGranted = granted,
            sysfsWriteAllowed = null, // set later by probeSysfsWriteAllowed()
        )
    }

    /**
     * Performs a no-op write test against the first CPU policy's
     * scaling_min_freq — re-writes the value it just read, so the kernel
     * state is byte-identical regardless of outcome.
     *
     * Phase 2: uses the Shizuku UserService binder (shell UID) to perform
     * the probe, so the result reflects the shell-level SELinux decision
     * rather than the app-UID decision. The app-UID quick-check is kept as
     * an early-return optimisation (if even the app UID can write, shell
     * definitely can too — no need to spin up the binder).
     *
     * Returns:
     *   true  — shell CAN write this node on this device
     *   false — shell CANNOT write (vendor SELinux denial confirmed)
     *   null  — Shizuku not usable or UserService not yet connected
     */
    fun probeSysfsWriteAllowed(): Boolean? {
        val firstPolicy = sysfsProber.probeCpuPolicies().firstOrNull() ?: return null
        val currentMin = firstPolicy.currentMinKhz.takeIf { it > 0 } ?: return null
        val targetPath = "/sys/devices/system/cpu/cpufreq/policy${firstPolicy.policyId}/scaling_min_freq"

        // Quick-check: if the app UID can write (unlocked node), shell can too.
        val appUidSuccess = runCatching {
            File(targetPath).writeText(currentMin.toString())
            true
        }.getOrDefault(false)
        if (appUidSuccess) return true

        // Shell-UID probe via UserService binder. If the service isn't connected
        // yet (e.g. Shizuku just granted), attempt to connect first.
        connection.ensureConnected()
        val svc = connection.service ?: run {
            Log.w(TAG, "probeSysfsWriteAllowed: UserService not connected yet")
            return null
        }

        val errno = try {
            runBlocking { nodeCache.probeAndCache(targetPath) }
        } catch (e: Throwable) {
            Log.e(TAG, "probeSysfsWriteAllowed exception: ${e.message}")
            return null
        }

        // probeAndCache returns: true=writable, false=denied, null=service unavailable
        return errno
    }

    /**
     * Run the full per-node probe for all AutoTDP-critical paths.
     * Returns the set of paths that the shell can actually write on this device.
     * Populates [ShizukuNodeCache] so [WriterRegistry] can route correctly.
     *
     * Called after Shizuku permission is granted and the UserService is connected.
     * The result is per-device and per-path — we make no assumptions.
     */
    suspend fun probeAutotdpNodes(report: io.github.mayusi.calibratesoc.data.capability.CapabilityReport): Set<String> {
        if (!report.shizuku.permissionGranted) return emptySet()
        connection.ensureConnected()

        // Build the candidate list: all nodes AutoTDP might write.
        val candidates = buildList {
            report.cpuPolicies.forEach { policy ->
                add(Tunables.cpuMinFreq(policy.policyId).target)
                add(Tunables.cpuMaxFreq(policy.policyId).target)
                add(Tunables.cpuGovernor(policy.policyId).target)
                policy.onlineCores.forEach { core ->
                    if (core != 0) add(Tunables.cpuOnline(core).target) // never probe cpu0 online
                }
            }
            report.gpu?.let { gpu ->
                add(Tunables.gpuMinFreq(gpu.rootPath).target)
                add(Tunables.gpuMaxFreq(gpu.rootPath).target)
                add(Tunables.gpuGovernor(gpu.rootPath).target)
                if (gpu.powerLevelRange != null) {
                    add(Tunables.adrenoMaxPowerLevel(gpu.rootPath).target)
                }
            }
        }

        // Probe each candidate — the cache stores the results.
        val writable = mutableSetOf<String>()
        for (path in candidates) {
            val result = nodeCache.probeAndCache(path)
            if (result == true) writable.add(path)
        }

        Log.i(TAG, "Shizuku probeAutotdpNodes: ${writable.size}/${candidates.size} writable")
        if (writable.isEmpty()) {
            Log.w(TAG, "Shizuku granted but NO AutoTDP nodes are shell-writable on this device. " +
                "Vendor SELinux policy blocks all cpufreq/GPU writes. " +
                "The app will surface monitoring + vendor settings only.")
        }
        return writable
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val TAG = "ShizukuProbe"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
