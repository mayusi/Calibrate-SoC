package io.github.mayusi.calibratesoc.data.vendor

import android.content.Context
import android.content.Intent

/**
 * Deep-link actions exposed by the Odin Settings system app
 * (com.odin.settings). Discovered via `dumpsys package` on Odin 3
 * firmware. All actions are listed in com.odin.settings's manifest
 * with intent-filter entries — they are public deep links by design.
 *
 * resolveActivity returns null on Android 11+ unless we declare each
 * action under <queries><intent> in our manifest. That's already done
 * for ACTION_FAN_TEMP_CONTROL_CURVE_CONFIG.
 */
object OdinIntents {
    const val ACTION_FAN_TEMP_CONTROL_CURVE_CONFIG = "action_fan_temp_control_curve_config"
    const val ACTION_EQUALIZER_CONFIG = "action_equalizer_config"

    /** Returns true when the device firmware exposes the fan-curve
     *  editor we can deep-link to. False on non-AYN handhelds. */
    fun supportsFanCurveEditor(context: Context): Boolean {
        val intent = Intent(ACTION_FAN_TEMP_CONTROL_CURVE_CONFIG)
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun openFanCurveEditor(context: Context) {
        val intent = Intent(ACTION_FAN_TEMP_CONTROL_CURVE_CONFIG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Best-effort "open Odin Settings on the Run-as-root screen" path.
     * Odin's runner is a private Activity (varies by firmware), but the
     * com.odin.settings launcher entry brings the user one tap away.
     * We try, in order:
     *   1. A known internal activity name if present on this firmware
     *   2. The package's default launcher activity
     *   3. The SHOW_APP_INFO settings page (always succeeds, so the user
     *      can tap "Open" from there)
     */
    fun openOdinSettings(context: Context): Boolean = openVendorSettings(context)

    /**
     * Human-readable name of the device's vendor settings app, for use
     * in UI copy ("run it via <X> → Run script as Root"). Falls back to
     * a generic phrase on unknown devices so the instructions still make
     * sense. Checks installed packages so the label matches the actual
     * device, not a guess from Build.MODEL.
     */
    fun vendorSettingsName(context: Context): String {
        fun installed(pkg: String) = runCatching {
            context.packageManager.getPackageInfo(pkg, 0); true
        }.getOrDefault(false)
        return when {
            installed("com.odin.settings") -> "Odin Settings"
            installed("com.rp.settings") -> "Retroid Settings"
            installed("com.ayaneo.settings") -> "AYANEO Settings"
            else -> "your device's settings"
        }
    }

    /**
     * Open the device's vendor "handheld settings" app on its main page,
     * where the "Force SELinux" toggle and the "Run script as Root"
     * runner live. The supported handhelds expose this same mechanism —
     * verified live:
     *   - AYN Odin 3 / Thor → com.odin.settings (RO firmware base)
     *   - Retroid Pocket 6  → com.rp.settings   (SAME com.ro.* classes)
     *
     * The runner is a menu item INSIDE the main settings activity (not a
     * separate exported Activity), so we can only land the user one tap
     * away. We try each known ComponentName in turn; the first that
     * launches wins. Falls back to the app-details page of whichever
     * vendor settings package is installed.
     */
    fun openVendorSettings(context: Context): Boolean {
        // Class names confirmed via `dumpsys package` on each device.
        val candidates = listOf(
            // AYN / Odin (RO firmware)
            android.content.ComponentName("com.odin.settings", "com.ro.settings.activity.MainSettingsActivity"),
            android.content.ComponentName("com.odin.settings", "com.odin.settings.activity.LightDialogMainSettingsActivity"),
            // Retroid Pocket 6 — same RO classes under the rp package
            android.content.ComponentName("com.rp.settings", "com.ro.settings.activity.MainSettingsActivity"),
            // Legacy / alternate AYN package
            android.content.ComponentName("com.ayn.gameassistant", "com.ayn.gameassistant.MainActivity"),
        )
        for (cn in candidates) {
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = runCatching { context.startActivity(intent); true }.getOrElse { false }
            if (ok) return true
        }
        // Final fallback: app-details page of whichever vendor settings
        // package is actually present.
        val vendorPkgs = listOf("com.odin.settings", "com.rp.settings")
        for (pkg in vendorPkgs) {
            val installed = runCatching {
                context.packageManager.getPackageInfo(pkg, 0); true
            }.getOrDefault(false)
            if (!installed) continue
            val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(details); true }.getOrElse { false }
            if (ok) return true
        }
        return false
    }

    /**
     * Open a file-manager-style chooser pointed at the directory where
     * we drop generated scripts (/sdcard/CalibrateSoC). Useful when the
     * Odin runner's own picker is hard to navigate to — the user can
     * see the script first, then send-to / open-with it.
     */
    fun openScriptDirectory(context: Context, absolutePath: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                androidx.core.content.FileProvider
                    .getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        java.io.File(absolutePath),
                    ),
                "text/x-shellscript",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent); true }.getOrElse { false }
    }
}
