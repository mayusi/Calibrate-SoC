package io.github.mayusi.calibratesoc.data.capability

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight check for the presence of the three vendor companion apps
 * we know how to interoperate with. Their package ids are declared in the
 * <queries> block in AndroidManifest.xml — without that, PackageManager
 * lookups would return NameNotFoundException on Android 11+ even when the
 * app is installed.
 */
@Singleton
class VendorAppDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun detect(): VendorAppPresence = VendorAppPresence(
        // Odin 3 firmware uses com.odin.gameassistant (com.ro.gameassistant
        // internal class names). Some older docs reference com.ayn.* —
        // unify into a single boolean by checking BOTH so the rest of
        // the app sees "is there an AYN/Odin game-assistant app".
        aynGameAssistant = installed("com.odin.gameassistant") ||
            installed("com.ayn.gameassistant"),
        langerhansOdinTools = installed("de.langerhans.odintools"),
        ayaSpace = installed("com.ayaneo.ayaspace"),
        // Retroid Pocket 6: com.rp.gameassistant is the perf/fan app;
        // com.retroidpocket.gamelauncher is the launcher. Verified live
        // on RP6 firmware.
        retroidGameAssistant = installed("com.rp.gameassistant") ||
            installed("com.retroidpocket.gameassistant"),
    )

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
