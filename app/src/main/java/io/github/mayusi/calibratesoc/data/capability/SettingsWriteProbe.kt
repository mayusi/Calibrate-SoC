package io.github.mayusi.calibratesoc.data.capability

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports whether we hold WRITE_SECURE_SETTINGS — the runtime-grantable
 * permission that lets us write `Settings.System` keys without root.
 *
 * Android won't let a normal app declare this in its manifest because
 * it's a signature/system permission, but a user can grant it once via:
 *
 *   adb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS
 *
 * Or via Shizuku once Shizuku is bound. After the grant, the permission
 * survives reboots until the app is uninstalled.
 *
 * This is the no-root path to AYN preset switching. The same idea
 * powers langerhans `OdinTools`.
 */
@Singleton
class SettingsWriteProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasWriteSecureSettings(): Boolean {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }
}
