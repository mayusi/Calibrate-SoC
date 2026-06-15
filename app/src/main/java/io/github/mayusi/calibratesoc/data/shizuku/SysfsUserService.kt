package io.github.mayusi.calibratesoc.data.shizuku

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Shizuku UserService — runs inside the Shizuku-spawned process at shell UID
 * (u:r:shell:s0). This means it has ADB-shell privileges: more than the app
 * UID (u:r:untrusted_app:s0) but still subject to the device's vendor SELinux
 * policy.
 *
 * IMPORTANT: This class intentionally uses NO Android framework services that
 * depend on a bound Context (e.g. ContentResolver, SharedPreferences) because
 * the shell process does not have a full ActivityThread. Plain Java file I/O
 * and the binder interface are the only safe operations here.
 *
 * The [probeWritable] method is the honesty core: it reads the current value
 * and writes it back unchanged to prove shell-level access without mutating
 * any kernel state.
 */
class SysfsUserService : ISysfsUserService.Stub() {

    // ── ISysfsUserService implementation ─────────────────────────────────────

    override fun readSysfsNode(path: String): String {
        requireSafeShellPath(path)
        return try {
            File(path).readText().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    override fun writeSysfsNode(path: String, value: String): Int {
        requireSafeShellPath(path)
        return try {
            // Use FileOutputStream to avoid any Kotlin stdlib newline injection.
            // Some kernel parsers reject trailing newlines with EINVAL.
            FileOutputStream(path, false).use { out ->
                out.write(value.toByteArray(Charsets.UTF_8))
            }
            0
        } catch (e: SecurityException) {
            EACCES
        } catch (e: java.io.FileNotFoundException) {
            ENOENT
        } catch (e: java.io.IOException) {
            // On SELinux denials the kernel surfaces EACCES through Java as an
            // IOException with "Permission denied" text. Match on message to
            // pick the closest errno bucket.
            val msg = e.message?.lowercase() ?: ""
            when {
                "permission denied" in msg -> EACCES
                "not permitted" in msg     -> EPERM
                else                        -> EIO
            }
        }
    }

    override fun probeWritable(path: String): Int {
        requireSafeShellPath(path)
        return try {
            // Read the current value.
            val current = File(path).readText()
            // Write it back byte-identical — no semantic kernel state change.
            FileOutputStream(path, false).use { out ->
                out.write(current.toByteArray(Charsets.UTF_8))
            }
            0 // success — shell CAN write this node on this device
        } catch (e: SecurityException) {
            EACCES
        } catch (e: java.io.FileNotFoundException) {
            ENOENT
        } catch (e: java.io.IOException) {
            val msg = e.message?.lowercase() ?: ""
            when {
                "permission denied" in msg -> EACCES
                "not permitted" in msg     -> EPERM
                else                        -> EIO
            }
        }
    }

    override fun destroy() {
        // No-op: the Shizuku framework tears down the process when all clients
        // unbind. This entry point exists so callers can signal an orderly
        // shutdown if needed.
    }

    // ── Path safety ───────────────────────────────────────────────────────────

    /**
     * Reject any path that does not start with a known safe kernel prefix.
     * This prevents callers (even compromised ones on the local binder) from
     * using the shell UID to write arbitrary files.
     *
     * The allowlist matches the same families as [Tunables.isUnlockCoveredNode]
     * and the AutoTDP node families the daemon actually needs.
     */
    private fun requireSafeShellPath(path: String) {
        val safe = SAFE_PATH_PREFIXES.any { path.startsWith(it) }
        require(safe) { "ShizukuUserService: path outside allowlist: $path" }
    }

    private companion object {
        // errno constants (POSIX subset used in sysfs I/O)
        const val EACCES = 13
        const val EPERM  = 1
        const val EIO    = 5
        const val ENOENT = 2

        /**
         * Kernel surfaces the app is ever allowed to touch via Shizuku.
         * Deliberately no /proc/sys, /dev/stune, /data, or /sdcard.
         */
        val SAFE_PATH_PREFIXES = listOf(
            "/sys/devices/system/cpu/",
            "/sys/class/kgsl/",
            "/sys/class/devfreq/",
            "/sys/block/",
            "/sys/module/cpu_boost/",
            "/sys/kernel/",
        )
    }
}
