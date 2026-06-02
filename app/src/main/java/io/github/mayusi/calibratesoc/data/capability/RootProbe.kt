package io.github.mayusi.calibratesoc.data.capability

import com.topjohnwu.superuser.Shell
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether the device has a working `su` binary AND classifies
 * which root provider it most likely is (Magisk / KernelSU / other).
 *
 * Note on libsu: a call to [Shell.isAppGrantedRoot] triggers a real `su`
 * acquisition request the first time it runs. We avoid that side-effect at
 * capability-detection time by using the lightweight static probe via
 * [Shell.getCachedShell]/[Shell.isAppGrantedRoot] only after the user has
 * opted in. For the capability report we just check whether the binary
 * exists; the "really granted" check happens lazily when the user actually
 * tries to apply a root-only tunable.
 */
@Singleton
class RootProbe @Inject constructor() {

    fun probe(): Pair<Boolean, RootKind> {
        val hasSu = SU_BINARY_PATHS.any { java.io.File(it).exists() }
        if (!hasSu) return false to RootKind.NONE

        val kind = when {
            java.io.File("/sbin/magisk").exists() ||
                java.io.File("/system/bin/magisk").exists() ||
                java.io.File("/data/adb/magisk").isDirectory -> RootKind.MAGISK
            java.io.File("/data/adb/ksud").exists() ||
                java.io.File("/data/adb/ksu").isDirectory -> RootKind.KERNELSU
            else -> RootKind.OTHER
        }
        return true to kind
    }

    private companion object {
        /** Canonical su locations across Magisk, KernelSU, and legacy ROMs. */
        val SU_BINARY_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksud",
        )
    }
}
