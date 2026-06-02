package io.github.mayusi.calibratesoc.data.capability

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether Shizuku is installed, running, and has granted us
 * permission. The "sysfs write actually works" question is answered by
 * [probeSysfsWriteAllowed] which performs a NO-OP re-write of an already
 * known CPU policy value.
 *
 * Why a no-op write: the value we write is exactly the value we just read,
 * so the kernel state is unchanged. If SELinux denies the write we learn
 * that without changing anything; if it succeeds, the device's settings
 * are still byte-identical to what they were before the probe. This is the
 * load-bearing test that decides whether the "Shizuku tier" exposes any
 * real clocking controls or degrades to vendor-key + monitoring only.
 */
@Singleton
class ShizukuProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sysfsProber: SysfsProber,
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
        // We never probe the kernel from a probe call — that would pop the
        // permission dialog at startup. The write-probe is opt-in: callers
        // invoke probeSysfsWriteAllowed() after the user grants permission.
        return ShizukuStatus(
            installed = true,
            running = true,
            permissionGranted = granted,
            sysfsWriteAllowed = null,
        )
    }

    /**
     * Performs a no-op write test against the first CPU policy's
     * scaling_min_freq — re-writes the value it just read, so the kernel
     * state is byte-identical regardless of outcome.
     *
     * Phase 1 implements only the unprivileged half (try writing directly
     * as the app UID). The Shizuku-shell write path needs a bound
     * UserService, which lands in Phase 2 alongside the rest of the
     * Shizuku writer. Until then, an app-UID success implies "writes
     * permitted" and an app-UID failure implies "we don't yet know" —
     * we report null in that case so the UI doesn't lie.
     *
     * Returns null when Shizuku is not usable or when the Phase-2 probe
     * path is not yet wired up.
     */
    fun probeSysfsWriteAllowed(): Boolean? {
        val firstPolicy = sysfsProber.probeCpuPolicies().firstOrNull() ?: return null
        val currentMin = firstPolicy.currentMinKhz.takeIf { it > 0 } ?: return null
        val targetPath = "/sys/devices/system/cpu/cpufreq/policy${firstPolicy.policyId}/scaling_min_freq"

        // Try as the app UID. On Android 10+ this is almost always blocked
        // by SELinux — that result is INDETERMINATE for the Shizuku tier
        // (the Shizuku shell may still succeed where the app UID fails).
        val appUidSuccess = runCatching {
            File(targetPath).writeText(currentMin.toString())
            true
        }.getOrDefault(false)
        if (appUidSuccess) return true

        // Shizuku-shell write probe deferred to Phase 2 (UserService path).
        return null
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
