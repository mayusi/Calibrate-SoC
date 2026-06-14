package io.github.mayusi.calibratesoc.data.tunables.writer

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
 * Shizuku-tier writer. The Shizuku 13.x public API does NOT expose
 * `newProcess` directly — the supported pathway is a UserService that
 * we bind into the Shizuku-spawned process and call across binder.
 *
 * That UserService implementation is intentionally deferred (it requires
 * a stable AIDL surface + lifecycle plumbing that's its own design beat).
 * For now this writer:
 *   * READS by falling back to the app-UID fs path. Sysfs is world-
 *     readable on most kernels, so the dashboard / monitor can still
 *     pull live data on the Shizuku tier.
 *   * WRITES by reporting CapabilityDenied with a clear message so the
 *     UI can prompt "Switch to root tier OR wait for the Shizuku
 *     UserService update" rather than silently failing.
 *
 * The TODO below is the contract the UserService work will fulfill.
 */
@Singleton
class ShizukuWriter @Inject constructor(
    private val fs: FileSystem,
) : SysfsWriter {

    override suspend fun read(id: TunableId): String? {
        if (id.kind != TunableKind.SYSFS) return null
        return withContext(Dispatchers.IO) {
            fs.readSysfsString(id.target.toPath())
        }
    }

    override suspend fun canWrite(id: TunableId): Boolean = false

    override suspend fun write(id: TunableId, value: String): WriteResult =
        WriteResult.CapabilityDenied(
            id = id,
            // TODO(phase-2-followup): replace with the UserService write path
            //  once the AIDL/binder plumbing lands. The probe in
            //  ShizukuProbe.probeSysfsWriteAllowed will move into the
            //  UserService at the same time.
            reason = "Shizuku sysfs write deferred — bind UserService or use root tier.",
        )
}
