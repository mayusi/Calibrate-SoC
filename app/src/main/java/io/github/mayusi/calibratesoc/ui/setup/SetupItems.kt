package io.github.mayusi.calibratesoc.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Shared logic for both the first-launch wizard and the Settings
 * checklist. Each [SetupItem] knows how to check its own status and
 * how to launch the system flow that grants/enables it.
 *
 * Probes are intentionally cheap so the wizard / checklist can poll
 * them every second without burning battery.
 */
sealed interface SetupItem {
    val id: String
    val title: String
    val rationale: String
    fun isDone(context: Context): Boolean
    fun launch(context: Context)

    /** Whether this setup item is achievable on THIS device at all. Items
     *  that require hardware/firmware the device lacks return false so the UI
     *  can show "Not available on this device" instead of an un-grantable button. */
    fun isApplicable(context: android.content.Context): Boolean = true
}

object OverlaySetupItem : SetupItem {
    override val id = "overlay"
    override val title = "Draw over other apps"
    override val rationale =
        "The floating HUD needs this to draw on top of games. " +
            "Tap Grant → toggle Calibrate SoC ON in the system list."
    override fun isDone(context: Context): Boolean = Settings.canDrawOverlays(context)
    override fun launch(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        // No FLAG_ACTIVITY_NEW_TASK when launched from an Activity:
        // letting it inherit OUR task means the system back button
        // returns the user to this wizard automatically. We only fall
        // back to NEW_TASK for non-Activity contexts (e.g., Settings
        // checklist composables that happened to wrap context).
        startFromActivityOrTask(context, intent)
    }
}

/**
 * Usage Stats — needed to know which app is in the foreground so the
 * HUD's per-app profile auto-switcher works and the FPS counter can
 * label which game's stats it's showing.
 */
object UsageStatsSetupItem : SetupItem {
    override val id = "usage_stats"
    override val title = "Usage access"
    override val rationale =
        "Lets the HUD know which game is currently in front so it can " +
            "show the right FPS / auto-apply the right tune. Tap Grant → " +
            "find Calibrate SoC → toggle ON."
    override fun isDone(context: Context): Boolean {
        val pm = context.packageManager
        return pm.checkPermission(
            android.Manifest.permission.PACKAGE_USAGE_STATS,
            context.packageName,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    override fun launch(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startFromActivityOrTask(context, intent)
    }
}

/**
 * Battery optimization exemption — keeps the HUD foreground service
 * and the MonitorService alive in the background. Without this,
 * Doze will kill them mid-session.
 */
object BatteryOptSetupItem : SetupItem {
    override val id = "battery_opt"
    override val title = "Ignore battery optimization"
    override val rationale =
        "Keeps the HUD and monitor running during long sessions instead of " +
            "being frozen by Android. Tap below, then allow it — if you land " +
            "on a list, find Calibrate SoC and switch it on."
    override fun isDone(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    @android.annotation.SuppressLint("BatteryLife")
    override fun launch(context: Context) {
        val activity = activityOf(context)
        val starter: (Intent) -> Unit = { intent ->
            if (activity != null) activity.startActivity(intent)
            else { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) }
        }
        // 1) Direct system prompt ("Allow?" dialog) — fastest, but some OEM
        //    firmwares (e.g. Retroid kalama) reject the package-targeted variant.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        // 2) The global "battery optimization" list — always present in AOSP Settings.
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        // 3) This app's details page — last resort so the user is never stranded.
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
        for ((label, intent) in listOf("direct-prompt" to direct, "opt-list" to list, "app-details" to details)) {
            try { starter(intent); return }
            catch (t: Throwable) {
                android.util.Log.w("CalibrateSetup", "battery-opt intent '$label' failed: ${t.message}")
            }
        }
    }
}

/**
 * Unlock script — the one-time grant flow we built earlier. Done
 * state is "DUMP permission held" since DUMP is the most expensive
 * thing the unlock grants and the most reliable signal it ran.
 *
 * Unlike the other items, `launch()` here actually GENERATES the
 * script first (drops `calibratesoc_unlock.sh` into the user's
 * chosen folder) and THEN opens Odin Settings. The wizard supplies
 * the deployer via the lastDeployedPath field so it can show the
 * file path to the user. Previous behavior just opened Odin Settings
 * and the user had nothing to pick from the file list.
 */
object UnlockScriptSetupItem : SetupItem {
    override val id = "unlock_script"
    override val title = "HUD & FPS permissions (optional)"
    override val rationale =
        "Optional extra for the in-game FPS counter, per-app auto-profiles, and " +
            "vendor-key writes. On PServer devices (Odin, Retroid) core tuning is " +
            "already live without this. Tap Generate — we write calibratesoc_unlock.sh " +
            "into your CalibrateSoC folder, then open Odin Settings → " +
            "Run script as Root. Pick that file from the list."

    /** Set by the wizard after we successfully write the script.
     *  Surfaced in the UI so the user knows the exact filename + path
     *  to look for in Odin's picker. */
    @Volatile var lastDeployedPath: String? = null

    /**
     * The unlock script step is only forceable on devices that have a vendor
     * runner to execute it — AYN (com.odin.settings), Retroid (com.rp.settings),
     * AYANEO (com.ayaneo.settings), or any device whose PServer already transacts
     * (the script whitelists us there).
     *
     * On a generic Android phone with none of these, the user has no "Run
     * script as Root" menu, so we must NOT force them through this step.
     */
    override fun isApplicable(context: Context): Boolean {
        val pm = context.packageManager
        val vendorSettingsPkgs = listOf(
            "com.odin.settings",   // AYN Odin / Thor
            "com.rp.settings",     // Retroid Pocket 6 (and family)
            "com.ayaneo.settings", // AYANEO
        )
        val hasVendorRunner = vendorSettingsPkgs.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        if (hasVendorRunner) return true
        // Also applicable if PServer already transacts — the script whitelists us.
        return runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                UnlockEntryPoint::class.java,
            ).unlockScript().let {
                // Re-use the memoised transactableNow check via PServerWriter.
                dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    PServerCheckEntryPoint::class.java,
                ).pServerWriter().transactableNow()
            }
        }.getOrDefault(false)
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PServerCheckEntryPoint {
        fun pServerWriter(): io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
    }

    override fun isDone(context: Context): Boolean {
        return context.packageManager.checkPermission(
            android.Manifest.permission.DUMP,
            context.packageName,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    override fun launch(context: Context) {
        // Deploy the script via the same singleton the Settings/Tune
        // screens use — pulled out of Hilt's app-context graph by hand
        // because Compose doesn't give us scoped injection inside a
        // plain composable click handler.
        val deployer = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                UnlockEntryPoint::class.java,
            ).unlockScript()
        }.getOrNull()
        if (deployer != null) {
            val deployed = runCatching { deployer.deploy() }.getOrNull()
            lastDeployedPath = deployed?.path
        }
        // Now bounce into Odin Settings so the user can run it. Force
        // NEW_TASK here — Odin Settings is a separate UID app and the
        // intent must launch a new task by design.
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openOdinSettings(context)
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface UnlockEntryPoint {
        fun unlockScript(): io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
    }
}

/**
 * Universal setup items for the first-launch wizard.
 * Only the three permissions every device needs: overlay (HUD),
 * usage access (per-app auto-switch), battery optimization exemption
 * (keeps HUD + monitor alive mid-game).
 *
 * UnlockScriptSetupItem is still defined above and used by the Settings
 * checklist + TuneScreen's AdvancedUnlockCard — it is intentionally NOT
 * included here so the wizard stays fast and device-agnostic.
 */
val AllSetupItems: List<SetupItem> = listOf(
    OverlaySetupItem,
    UsageStatsSetupItem,
    BatteryOptSetupItem,
)

/**
 * Full checklist for the Settings screen — includes the advanced items
 * so power users can check/re-grant them without navigating back to
 * Tune → Advanced unlock.
 */
val AllSetupItemsWithAdvanced: List<SetupItem> = listOf(
    OverlaySetupItem,
    UsageStatsSetupItem,
    BatteryOptSetupItem,
    UnlockScriptSetupItem,
)

/**
 * Launch [intent] from [context], preferring activity-stack semantics
 * (no FLAG_ACTIVITY_NEW_TASK) so the system back button returns the
 * user to us instead of dropping them on the home screen. Falls back
 * to NEW_TASK when [context] isn't an Activity (only happens from the
 * Settings checklist's composable when its host doesn't expose an
 * Activity — rare). Safe to call from any thread.
 */
/**
 * Walk [context]'s ContextWrapper.baseContext chain to find the hosting
 * Activity, or null if none (e.g. a service/application context).
 */
private fun activityOf(context: Context): android.app.Activity? =
    generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .firstOrNull { it is android.app.Activity } as? android.app.Activity

private fun startFromActivityOrTask(context: Context, intent: Intent) {
    val activity = activityOf(context)
    if (activity != null) {
        runCatching { activity.startActivity(intent) }
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

