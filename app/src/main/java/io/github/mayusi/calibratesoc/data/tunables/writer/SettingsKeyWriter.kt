package io.github.mayusi.calibratesoc.data.tunables.writer

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vendor preset flipper. AYN's perf / fan modes are implemented as
 * Settings.System keys that the OEM system app subscribes to; flipping
 * the key triggers the real kernel change inside their service. This is
 * the same pathway langerhans `OdinTools` uses and it works on stock
 * firmware without root.
 *
 * Requires WRITE_SECURE_SETTINGS, which the user grants via Shizuku
 * (`adb shell pm grant ${appId} android.permission.WRITE_SECURE_SETTINGS`)
 * or root. We don't ask Android for it because it's a signature/system
 * permission and would otherwise be denied silently.
 *
 * VENDOR_INTENT support (AYANEO AYASpace) is stubbed — those vendors
 * dispatch via private binder. We surface them as CapabilityDenied with
 * a clear "unsupported on this device" message rather than silently
 * doing nothing.
 */
@Singleton
class SettingsKeyWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SysfsWriter {

    override suspend fun read(id: TunableId): String? = withContext(Dispatchers.IO) {
        when (id.kind) {
            TunableKind.SETTINGS_SYSTEM -> runCatching {
                Settings.System.getString(context.contentResolver, id.target)
            }.getOrNull()
            TunableKind.VENDOR_INTENT, TunableKind.SYSFS -> null
        }
    }

    override suspend fun canWrite(id: TunableId): Boolean = id.kind == TunableKind.SETTINGS_SYSTEM

    override suspend fun write(id: TunableId, value: String): WriteResult =
        withContext(Dispatchers.IO) {
            when (id.kind) {
                TunableKind.SETTINGS_SYSTEM -> {
                    val previous = runCatching {
                        Settings.System.getString(context.contentResolver, id.target)
                    }.getOrNull()
                    val ok = runCatching {
                        Settings.System.putString(context.contentResolver, id.target, value)
                    }.getOrDefault(false)
                    if (ok) WriteResult.Success(id, previous, value)
                    else WriteResult.Rejected(
                        id = id,
                        errno = null,
                        message = "Settings.System.putString returned false — likely missing " +
                            "WRITE_SECURE_SETTINGS (grant via adb or root).",
                    )
                }
                TunableKind.VENDOR_INTENT -> WriteResult.CapabilityDenied(
                    id = id,
                    reason = "VENDOR_INTENT writer not implemented (AYANEO AYASpace path pending).",
                )
                TunableKind.SYSFS -> WriteResult.CapabilityDenied(
                    id = id,
                    reason = "SettingsKeyWriter does not handle SYSFS.",
                )
            }
        }
}
