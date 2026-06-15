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
 * Force SELinux (Odin firmware-specific) — without this our app's
 * UID can't write CPU sysfs, vendor key Binder calls hit denials,
 * and PServer / kernel-read fallbacks all fail. We can't toggle it
 * for the user (it's in Odin Settings, not our app's perms), but we
 * can DETECT whether it's on by running `getenforce` (always
 * executable to any UID) and checking the result.
 */
object ForceSelinuxSetupItem : SetupItem {
    override val id = "force_selinux"
    override val title = "Permissive SELinux (advanced)"
    override val rationale =
        "Optional. Needed only for live in-app tuning (HUD ± buttons + " +
            "direct sysfs writes). Open your device's settings app and turn " +
            "\"Force SELinux\" ON — it's available on AYN (Odin/Thor) and " +
            "Retroid (Pocket 6) handhelds. Some devices ship permissive " +
            "already. We can't flip it for you — the firmware owns the " +
            "toggle. Skip it if you only want monitoring + benchmarks."
    override fun isDone(context: Context): Boolean {
        // We CANNOT reliably read SELinux mode from our app's UID:
        //   - getenforce → "Permission denied" (Odin 3 + Thor)
        //   - /sys/fs/selinux/enforce → "Permission denied"
        //   - scaling_max_freq is 664/660 owned system:system, so the
        //     write-probe fails even when permissive (we're not root
        //     and not the owner — permissive removes the SELinux denial
        //     but the POSIX mode bits still block us).
        // The ONLY thing that flips when Force SELinux turns ON and
        // actually MATTERS for us is whether PServer starts executing
        // our commands. So that's the real signal. If it transacts,
        // permissive is effectively on for our purposes.
        val viaPServer = isPServerTransactable(context)
        if (viaPServer) return true

        // Best-effort sysfs write probe (works only if a prior unlock
        // script chmod 666'd the files AND permissive is on).
        val viaWriteProbe = runCatching {
            val probe = java.io.File("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")
            if (!probe.exists()) return@runCatching false
            val original = probe.readText().trim()
            if (original.isEmpty()) return@runCatching false
            probe.bufferedWriter().use { it.write(original) }
            true
        }.getOrDefault(false)
        if (viaWriteProbe) return true

        // Last resort: the user manually told us they enabled it. We
        // can't verify, but we trust their word so the wizard isn't a
        // dead end on devices where every automated probe is blocked.
        return manuallyConfirmed(context)
    }

    /**
     * Delegates to [PServerWriter.transactableNow] — the ONLY correct
     * probe for whether PServer actually executes our commands.
     *
     * [PServerWriter.transactableNow] reads the memoised result of the
     * real [PServerWriter.isTransactable] probe that [CapabilityProbe.refresh]
     * already ran. This avoids:
     *   (a) duplicating the wire format (the old local copy used the
     *       WRONG format: writeInterfaceToken + writeString + transact(1),
     *       which always returns UNKNOWN_TRANSACTION on AYN firmware — a
     *       permanent false negative); and
     *   (b) issuing a second live transact, which would re-arm the
     *       circuit breaker on slow devices.
     *
     * If the cache has not been warmed yet (cold path before first
     * CapabilityProbe.refresh) this returns false — conservative, never
     * a false positive.
     */
    private fun isPServerTransactable(context: Context): Boolean = runCatching {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            PServerEntryPoint::class.java,
        ).pServerWriter().transactableNow()
    }.getOrDefault(false)

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PServerEntryPoint {
        fun pServerWriter(): io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
    }

    /** Read the user's manual "I enabled Force SELinux" confirmation
     *  from SharedPreferences. Synchronous + cheap so isDone() stays
     *  pollable. */
    fun manuallyConfirmed(context: Context): Boolean =
        context.getSharedPreferences("setup_overrides", Context.MODE_PRIVATE)
            .getBoolean("force_selinux_confirmed", false)

    /** Persist the user's manual confirmation. Called from the wizard
     *  when they tap "I've enabled it". */
    fun setManuallyConfirmed(context: Context, value: Boolean) {
        context.getSharedPreferences("setup_overrides", Context.MODE_PRIVATE)
            .edit().putBoolean("force_selinux_confirmed", value).apply()
    }

    override fun isApplicable(context: Context): Boolean {
        // Force-SELinux is provided by the vendor settings app on AYN/Odin
        // AND Retroid AND AYANEO handhelds — they share the com.ro.* firmware
        // base, so the toggle is present under com.odin.settings,
        // com.rp.settings, or com.ayaneo.settings. (Earlier this only checked
        // com.odin.settings, which wrongly marked the Retroid Pocket 6 as
        // "not available" — it IS available there.) Also applicable if PServer
        // already transacts (root path present) or if the device is already
        // permissive (some ship that way).
        val pm = context.packageManager
        val vendorSettingsPkgs = listOf(
            "com.odin.settings",   // AYN Odin / Thor
            "com.rp.settings",     // Retroid Pocket 6 (and family)
            "com.ayaneo.settings", // AYANEO
        )
        val hasVendorToggle = vendorSettingsPkgs.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        return hasVendorToggle || isPServerTransactable(context)
    }

    override fun launch(context: Context) {
        // Open Odin Settings so user can find the toggle themselves —
        // there's no deep link to the specific switch.
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openOdinSettings(context)
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
    override val title = "Run the unlock script"
    override val rationale =
        "One-time setup. Tap Generate — we write calibratesoc_unlock.sh " +
            "into your CalibrateSoC folder, then open Odin Settings → " +
            "Run script as Root. Pick that file from the list."

    /** Set by the wizard after we successfully write the script.
     *  Surfaced in the UI so the user knows the exact filename + path
     *  to look for in Odin's picker. */
    @Volatile var lastDeployedPath: String? = null

    /**
     * Mirrors [ForceSelinuxSetupItem.isApplicable]: the unlock script step
     * is only forceable on devices that have a vendor runner to execute it —
     * AYN (com.odin.settings), Retroid (com.rp.settings), AYANEO
     * (com.ayaneo.settings), or any device whose PServer already transacts
     * (the script whitelists us there).
     *
     * On a generic Android phone with none of these, the user has no "Run
     * script as Root" menu, so we must NOT force them through this step.
     */
    override fun isApplicable(context: Context): Boolean =
        ForceSelinuxSetupItem.isApplicable(context)

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
 * ForceSelinuxSetupItem and UnlockScriptSetupItem are still defined
 * above and used by the Settings checklist + TuneScreen's
 * AdvancedUnlockCard — they are intentionally NOT included here so
 * the wizard stays fast and device-agnostic.
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
    ForceSelinuxSetupItem,
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

