package io.github.mayusi.calibratesoc.data.script

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time "unlock the good stuff" setup script. The user runs this
 * once via Odin Settings → Run script as Root. It does what we cannot
 * do from our own UID:
 *
 *   - `pm grant DUMP`            → lets us run `dumpsys gfxinfo <pkg>`
 *                                   so the HUD can show real game FPS
 *   - `pm grant PACKAGE_USAGE_STATS` → lets us read which app is in
 *                                       the foreground without an
 *                                       AccessibilityService
 *   - `pm grant WRITE_SECURE_SETTINGS` → vendor preset key writes
 *                                         without Shizuku
 *
 * pm-granted runtime perms PERSIST across reboots — the user does
 * this once and the HUD has these powers forever. We don't keep the
 * root channel; we just need it for the grant.
 *
 * We never auto-grant; the user has to consent by running the script
 * via Odin's own runner. The script is plain text, auditable.
 */
@Singleton
class AdvancedPermissionsScript @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pServerWriter: PServerWriter,
) {

    fun deploy(): Deployed {
        val pkg = context.packageName
        // FIX 3: whitelist BOTH the current build variant AND the base package id.
        // A script generated from the DEBUG build (io.github.mayusi.calibratesoc.debug)
        // must also whitelist the RELEASE package (io.github.mayusi.calibratesoc) so
        // the user doesn't have to regenerate/re-run the script after installing the
        // release build. Similarly, a release-build script whitelists the .debug variant
        // so testers switching between builds both work.
        val basePkg = pkg.removeSuffix(".debug")
        val debugPkg = if (basePkg == pkg) "$pkg.debug" else pkg
        // Collect all variants that differ from the base (avoid duplicates if pkg == basePkg).
        val extraPkgs = listOf(basePkg, debugPkg).distinct().filter { it != pkg }

        val body = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Calibrate SoC — HUD & FPS permissions")
            appendLine("# This grants the app's HUD / FPS / vendor-key permissions. It does")
            appendLine("# NOT unlock live clock tuning — that is gated by SELinux mode + the")
            appendLine("# PServer/vendor-binder root path, which this script does not change.")
            appendLine("# Live tuning already works zero-setup on devices whose firmware lets")
            appendLine("# our app transact PServer (or has a vendor binder, or root).")
            appendLine("#")
            appendLine("# The chmod 666 loops further down are OPTIONAL. They only help the")
            appendLine("# chmod-direct write path, and only on devices that have NO other")
            appendLine("# live-tuning path (no PServer / vendor binder / root). On Enforcing")
            appendLine("# SELinux they are harmless no-ops (they just don't stick). Do NOT")
            appendLine("# enable \"Force SELinux\" / Permissive just to make them stick: it")
            appendLine("# can BREAK many emulators and weakens security. Most users should")
            appendLine("# never enable it.")
            appendLine()
            appendLine("# === 1. Permissions (persist across reboot) ===")
            appendLine("pm grant $pkg android.permission.DUMP")
            appendLine("pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
            appendLine("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            // Also grant to the sibling build variants so switching debug↔release works.
            for (extra in extraPkgs) {
                appendLine("# Grant to sibling build variant ($extra) — idempotent if not installed.")
                appendLine("pm grant $extra android.permission.DUMP 2>/dev/null || true")
                appendLine("pm grant $extra android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true")
                appendLine("pm grant $extra android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true")
            }
            appendLine()
            // NO app_whiteList step. PServer (`/system/bin/pservice`) does NOT gate on
            // Settings.System/app_whiteList — proven on-device: the app ran root via
            // PServer with our package REMOVED from the whitelist. The real gate is the
            // device's SELinux mode (Permissive / transactable firmware → PServer works
            // zero-setup; Enforcing-blocked → no app-only fix). Writing app_whiteList is
            // a confirmed no-op, so the script no longer does it — it would only mislead.
            appendLine("# === 2. Stop vendor perf daemons so they can't clobber our writes ===")
            appendLine("stop perfd 2>/dev/null")
            appendLine("stop perf-hal-1-0 2>/dev/null")
            appendLine("stop perf-hal-1-1 2>/dev/null")
            appendLine("stop perf-hal-1-2 2>/dev/null")
            appendLine("stop vendor.perfservice 2>/dev/null")
            appendLine()
            appendLine("# === 3. chmod 666 on every cpufreq policy (OPTIONAL) ===")
            appendLine("# Only useful on devices with NO other live path (no PServer / vendor")
            appendLine("# binder / root). On Enforcing SELinux this is a harmless no-op — the")
            appendLine("# chmod won't stick, and that's fine because PServer/binder tuning")
            appendLine("# already works. Where it DOES stick, the HUD's app UID can write")
            appendLine("# scaling_max_freq / _min_freq / _governor directly via FileWriter.")
            appendLine("# Resets at reboot — re-run this script after each boot. Do NOT turn")
            appendLine("# on Force SELinux just for this: it can break emulators.")
            appendLine("for p in /sys/devices/system/cpu/cpufreq/policy*; do")
            appendLine("  [ -e \"\$p/scaling_max_freq\" ] && chmod 666 \"\$p/scaling_max_freq\"")
            appendLine("  [ -e \"\$p/scaling_min_freq\" ] && chmod 666 \"\$p/scaling_min_freq\"")
            appendLine("  [ -e \"\$p/scaling_governor\" ] && chmod 666 \"\$p/scaling_governor\"")
            appendLine("done")
            appendLine()
            appendLine("# === 4. GPU sysfs ===")
            appendLine("for g in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*mali* /sys/class/kgsl/kgsl-3d0; do")
            appendLine("  [ -e \"\$g/min_freq\" ] && chmod 666 \"\$g/min_freq\"")
            appendLine("  [ -e \"\$g/max_freq\" ] && chmod 666 \"\$g/max_freq\"")
            appendLine("  [ -e \"\$g/governor\" ] && chmod 666 \"\$g/governor\"")
            appendLine("done")
            appendLine("[ -e /sys/class/kgsl/kgsl-3d0/max_gpuclk ] && chmod 666 /sys/class/kgsl/kgsl-3d0/max_gpuclk")
            appendLine("[ -e /sys/class/kgsl/kgsl-3d0/min_pwrlevel ] && chmod 666 /sys/class/kgsl/kgsl-3d0/min_pwrlevel")
            appendLine("[ -e /sys/class/kgsl/kgsl-3d0/max_pwrlevel ] && chmod 666 /sys/class/kgsl/kgsl-3d0/max_pwrlevel")
            appendLine()
            appendLine("# === 5. Adreno extras ===")
            appendLine("# throttling, force_clk_on, idle_timer, default_pwrlevel — used by Advanced Tuning.")
            appendLine("for node in throttling force_clk_on idle_timer default_pwrlevel; do")
            appendLine("  [ -e /sys/class/kgsl/kgsl-3d0/\$node ] && chmod 666 /sys/class/kgsl/kgsl-3d0/\$node 2>/dev/null")
            appendLine("done")
            appendLine()
            appendLine("# === 6. CPU governor tunables ===")
            appendLine("# schedutil/*, walt/*, interactive/* — Advanced Tuning knobs for freq governor behaviour.")
            appendLine("for p in /sys/devices/system/cpu/cpufreq/policy*; do")
            appendLine("  for gov_dir in schedutil walt interactive; do")
            appendLine("    dir=\"\$p/\$gov_dir\"")
            appendLine("    [ -d \"\$dir\" ] || continue")
            appendLine("    for f in \"\$dir\"/*; do")
            appendLine("      [ -f \"\$f\" ] && chmod 666 \"\$f\" 2>/dev/null")
            appendLine("    done")
            appendLine("  done")
            appendLine("done")
            appendLine()
            appendLine("# === 7. DDR / bus devfreq ===")
            appendLine("# Separate from GPU devfreq (kgsl-3d0). Covers qcom,cpubw / llccbw / etc.")
            appendLine("for d in /sys/class/devfreq/*; do")
            appendLine("  [ -e \"\$d/min_freq\" ] && chmod 666 \"\$d/min_freq\" 2>/dev/null")
            appendLine("  [ -e \"\$d/max_freq\" ] && chmod 666 \"\$d/max_freq\" 2>/dev/null")
            appendLine("  [ -e \"\$d/governor\" ] && chmod 666 \"\$d/governor\" 2>/dev/null")
            appendLine("done")
            appendLine()
            appendLine("# === 8. I/O block devices ===")
            appendLine("for b in /sys/block/*; do")
            appendLine("  [ -e \"\$b/queue/scheduler\" ]   && chmod 666 \"\$b/queue/scheduler\" 2>/dev/null")
            appendLine("  [ -e \"\$b/queue/read_ahead_kb\" ] && chmod 666 \"\$b/queue/read_ahead_kb\" 2>/dev/null")
            appendLine("  [ -e \"\$b/queue/nr_requests\" ]  && chmod 666 \"\$b/queue/nr_requests\" 2>/dev/null")
            appendLine("done")
            appendLine()
            appendLine("# === 9. Input boost (cpu_boost kernel module) — if present ===")
            appendLine("for f in /sys/module/cpu_boost/parameters/*; do")
            appendLine("  [ -f \"\$f\" ] && chmod 666 \"\$f\" 2>/dev/null")
            appendLine("done")
            appendLine()
            appendLine("# NOT chmod'd (keep script-only or root-only):")
            appendLine("# /proc/sys/vm/* + /proc/sys/kernel/* — procfs writes fail under SELinux from app UID")
            appendLine("# /dev/stune/* + /dev/cpuctl/*        — cgroup writes unverified from app UID")
            appendLine("# /sys/class/thermal/*                 — NEVER: hardware damage risk")
            appendLine()
            appendLine("# === 10. Report what stuck ===")
            appendLine("# A '666' result here means the optional chmod-direct path is live.")
            appendLine("# A '444' result just means this device keeps the nodes locked — that")
            appendLine("# is FINE: the permissions + PServer/binder tuning above already work")
            appendLine("# on most devices, so live tuning still functions without it.")
            appendLine("echo 'Calibrate SoC unlock complete.'")
            appendLine("for p in /sys/devices/system/cpu/cpufreq/policy*; do")
            appendLine("  m=\$(stat -c %a \"\$p/scaling_max_freq\" 2>/dev/null)")
            appendLine("  echo \"  \$(basename \$p): scaling_max_freq mode=\$m\"")
            appendLine("done")
            appendLine("echo 'A 444 result is OK if PServer/binder tuning works (it does on most devices).'")
            appendLine("echo 'Force SELinux is a LAST RESORT only — it can break emulators, so most users should leave it off.'")
        }

        val filename = "calibratesoc_unlock.sh"
        runCatching {
            @Suppress("DEPRECATION")
            val pub = Environment.getExternalStorageDirectory()
            val dir = File(pub, "CalibrateSoC").apply { mkdirs() }
            val out = File(dir, filename)
            out.writeText(body)
            return Deployed(out.absolutePath, visibleToOdinPicker = true)
        }
        val priv = File(context.getExternalFilesDir(null), filename)
        priv.writeText(body)
        return Deployed(priv.absolutePath, visibleToOdinPicker = false)
    }

    /**
     * One-trust auto-setup: grant the three advanced permissions to OUR OWN
     * package(s) directly via PServer's root shell — no script file, no manual
     * "Run script as Root" step. This is the in-app equivalent of the unlock
     * script's permission grants, run through the same guarded chokepoint.
     *
     * HOW IT WORKS
     *   1. If PServer is not transactable ([PServerWriter.isTransactable] false),
     *      we return [GrantResult.NotAvailable] honestly — NO grants are issued and
     *      we never claim a success the device can't deliver. The caller falls back
     *      to the script path.
     *   2. Otherwise we run `pm grant <pkg> <perm>` for each of the three perms,
     *      for our package AND each sibling build variant (debug↔release), via
     *      [PServerWriter.executeShell]. EVERY command passes through
     *      [PServerCommandGuard] (which already permits exactly these grants), so
     *      no guard widening is needed and nothing destructive can be sent.
     *   3. After issuing the grants we RE-READ [grantsCurrentlyHeld] and report
     *      ONLY the perms that are actually held now. A grant that didn't land
     *      (e.g. an OTA flipped SELinux mid-flight) is reported as not-held — we
     *      never fabricate success.
     *
     * Persistence: pm-granted runtime perms survive reboots, exactly like the
     * script path. The script-deploy fallback ([deploy]) stays intact for devices
     * with no live PServer.
     */
    suspend fun grantViaPServer(): GrantResult {
        // Issue grants for our package + sibling variants. Building the package
        // list mirrors deploy(): the current build's pkg PLUS the .debug/release
        // sibling so a self-grant on either build covers both.
        val pkg = context.packageName
        val basePkg = pkg.removeSuffix(".debug")
        val debugPkg = if (basePkg == pkg) "$pkg.debug" else pkg
        val pkgs = listOf(pkg, basePkg, debugPkg).distinct()

        return grantViaExecutor(
            packages = pkgs,
            isTransactable = { pServerWriter.isTransactable() },
            executeShell = { cmd -> pServerWriter.executeShell(cmd) },
            readback = { grantsCurrentlyHeld() },
        )
    }

    /**
     * FULL one-trust auto-setup: grant the app EVERYTHING it needs in a single
     * click via PServer-root — a strict SUPERSET of [grantViaPServer]. As well as
     * the three `pm grant` runtime perms (DUMP / PACKAGE_USAGE_STATS /
     * WRITE_SECURE_SETTINGS) it also flips the four "special access" toggles the
     * user otherwise grants by hand in system Settings:
     *
     *   - **Usage Access**          → `appops set <pkg> android:get_usage_stats allow`
     *   - **Display over other apps** → `appops set <pkg> android:system_alert_window allow`
     *   - **Battery unrestricted**  → `dumpsys deviceidle whitelist +<pkg>`
     *   - **Notifications** (SDK 33+) → `pm grant <pkg> android.permission.POST_NOTIFICATIONS`
     *
     * All of these were proven on-device (RP6) to land via the exact PServerBinder
     * bridge the app uses (root uid=0). Every command flows through
     * [PServerWriter.executeShell] → [io.github.mayusi.calibratesoc.data.tunables.writer.PServerCommandGuard]
     * (the one transact chokepoint) so nothing destructive can be smuggled.
     *
     * HONESTY contract — identical discipline to [grantViaPServer]:
     *   1. If PServer is NOT transactable, returns [FullSetupResult.NotAvailable]
     *      and issues ZERO commands. The caller keeps the script path as fallback;
     *      it must NOT present this as a success.
     *   2. Otherwise it issues each grant, then RE-READS every item via a real
     *      platform check ([fullSetupReadback]) and reports per-[SetupItem] whether
     *      it is ACTUALLY held now — never what was merely sent. [FullSetupResult
     *      .Completed.allGranted] is computed off the readback map only.
     *
     * Persistence: pm-granted perms + appops + the deviceidle whitelist all survive
     * reboots, so this is genuinely one-and-done. The boot-reapply path re-warms
     * tuning separately; these grants do not need re-issuing.
     */
    suspend fun grantAllViaPServer(): FullSetupResult {
        val pkg = context.packageName
        val basePkg = pkg.removeSuffix(".debug")
        val debugPkg = if (basePkg == pkg) "$pkg.debug" else pkg
        val pkgs = listOf(pkg, basePkg, debugPkg).distinct()

        return grantAllViaExecutor(
            packages = pkgs,
            attemptNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            isTransactable = { pServerWriter.isTransactable() },
            executeShell = { cmd -> pServerWriter.executeShell(cmd) },
            readback = { fullSetupReadback() },
        )
    }

    /**
     * Pure, Android-free core of [grantAllViaPServer] so it can be unit-tested with
     * a fake executor + readback. Issues every grant command via [executeShell],
     * then returns the HONEST per-[SetupItem] held map produced by [readback].
     *
     * Command shapes (each a single clean segment the guard permits — see
     * [io.github.mayusi.calibratesoc.data.tunables.writer.PServerCommandGuard]):
     *   - `pm grant <pkg> <perm>` for the 3 base perms (and POST_NOTIFICATIONS when
     *     [attemptNotifications]) — per package variant.
     *   - `appops set <pkg> android:get_usage_stats allow`   (Usage Access)
     *   - `appops set <pkg> android:system_alert_window allow` (Overlay)
     *   - `dumpsys deviceidle whitelist +<pkg>`              (Battery unrestricted)
     *
     * We never trust the command status — a sibling variant that isn't installed
     * returns non-zero, and the [readback] is the single source of truth.
     *
     * @param attemptNotifications when false (SDK < 33) the POST_NOTIFICATIONS grant
     *   is SKIPPED gracefully — on those releases notifications are on by default,
     *   so the readback still reports NOTIFICATIONS as held without us issuing it.
     * @return [FullSetupResult.NotAvailable] when [isTransactable] is false (NO
     *   commands issued); otherwise [FullSetupResult.Completed] with the re-read
     *   held map and the list of commands that were issued.
     */
    internal suspend fun grantAllViaExecutor(
        packages: List<String>,
        attemptNotifications: Boolean,
        isTransactable: suspend () -> Boolean,
        executeShell: suspend (String) -> Pair<Int, String>?,
        readback: () -> Map<SetupItem, Boolean>,
    ): FullSetupResult {
        if (!isTransactable()) {
            // Honest: PServer can't run our command on this device. No grants issued.
            return FullSetupResult.NotAvailable
        }
        val issued = ArrayList<String>()
        // Build the per-package command list. POST_NOTIFICATIONS is only appended
        // when attemptNotifications (SDK 33+) — on older releases it's a no-op perm.
        val perms = buildList {
            addAll(GRANTABLE_PERMS)
            if (attemptNotifications) add(POST_NOTIFICATIONS_PERM)
        }
        for (pkg in packages) {
            // 1. pm-grant runtime perms (DUMP / USAGE_STATS / WRITE_SECURE_SETTINGS
            //    [+ POST_NOTIFICATIONS on SDK 33+]).
            for (perm in perms) {
                issued.add("pm grant $pkg $perm")
            }
            // 2. Special-access appops — Usage Access + Overlay (allow).
            for (op in SPECIAL_ACCESS_OPS) {
                issued.add("appops set $pkg $op allow")
            }
            // 3. Battery unrestricted — add to the deviceidle whitelist.
            issued.add("dumpsys deviceidle whitelist +$pkg")
        }
        for (cmd in issued) {
            // Run through the guarded chokepoint. Status is ignored — a sibling
            // variant that isn't installed returns non-zero; the readback decides.
            runCatching { executeShell(cmd) }
        }
        // HONESTY: re-read the live state. Only items that ACTUALLY landed are
        // reported held — never the commands we merely sent.
        val held = readback()
        return FullSetupResult.Completed(held, issued)
    }

    /**
     * Honest per-[SetupItem] readback used by [grantAllViaPServer], composed ENTIRELY
     * of real platform checks against OUR package (never what we sent):
     *
     *   - [SetupItem.ROOT_PERMS]: true only when all three pm-grant perms are held
     *     (re-uses [grantsCurrentlyHeld] → PackageManager.checkPermission).
     *   - [SetupItem.USAGE_ACCESS]: AppOpsManager `get_usage_stats` == MODE_ALLOWED.
     *   - [SetupItem.OVERLAY]: [Settings.canDrawOverlays].
     *   - [SetupItem.BATTERY]: [PowerManager.isIgnoringBatteryOptimizations].
     *   - [SetupItem.NOTIFICATIONS]: [NotificationManagerCompat.areNotificationsEnabled].
     *
     * Exposed (internal) so the ViewModel can re-read after refresh, and so a test
     * can assert the map keys. No grant logic here — pure observation.
     */
    internal fun fullSetupReadback(): Map<SetupItem, Boolean> {
        val grants = grantsCurrentlyHeld()
        return linkedMapOf(
            SetupItem.ROOT_PERMS to (grants.dump && grants.usageStats && grants.writeSecureSettings),
            SetupItem.USAGE_ACCESS to isUsageAccessGranted(),
            SetupItem.OVERLAY to canDrawOverlays(),
            SetupItem.BATTERY to isIgnoringBatteryOptimizations(),
            SetupItem.NOTIFICATIONS to areNotificationsEnabled(),
        )
    }

    // ── Special-access platform readbacks (reused by fullSetupReadback) ─────────

    /** Usage Access — AppOps `get_usage_stats` resolves to MODE_ALLOWED for us. */
    private fun isUsageAccessGranted(): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Display-over-other-apps (HUD overlay). */
    private fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** Battery-unrestricted (Doze exemption) for service survival. */
    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** Notifications enabled. On SDK < 33 this is true by default (no runtime perm). */
    private fun areNotificationsEnabled(): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

    /**
     * Pure, Android-free core of [grantViaPServer] so it can be unit-tested with a
     * fake executor + readback. Issues `pm grant <pkg> <perm>` for every (pkg,
     * perm) pair via [executeShell], then returns the HONEST post-grant [Grants]
     * read back via [readback].
     *
     * Each command is a single clean `pm grant <pkg> <perm>` — exactly the shape
     * [PServerCommandGuard.allowPm] permits — so it survives the guarded transact.
     * We do NOT append `2>/dev/null || true`: the guard splits on `||`/`;` and a
     * sibling-variant grant that fails (variant not installed) simply returns a
     * non-zero status we ignore; the readback is the source of truth either way.
     *
     * @return [GrantResult.NotAvailable] when [isTransactable] is false (NO grants
     *   issued); otherwise [GrantResult.Completed] carrying the re-read [Grants] and
     *   the list of commands that were issued.
     */
    internal suspend fun grantViaExecutor(
        packages: List<String>,
        isTransactable: suspend () -> Boolean,
        executeShell: suspend (String) -> Pair<Int, String>?,
        readback: () -> Grants,
    ): GrantResult {
        if (!isTransactable()) {
            // Honest: PServer can't run our command on this device. No grants issued.
            return GrantResult.NotAvailable
        }
        val issued = ArrayList<String>(packages.size * GRANTABLE_PERMS.size)
        for (pkg in packages) {
            for (perm in GRANTABLE_PERMS) {
                val cmd = "pm grant $pkg $perm"
                issued.add(cmd)
                // Run through the guarded chokepoint. We don't trust the status —
                // a sibling variant that isn't installed returns non-zero, and the
                // readback below is what we actually report.
                runCatching { executeShell(cmd) }
            }
        }
        // HONESTY: re-read the live grant state. Only perms that ACTUALLY landed
        // are reported as held — never the commands we merely sent.
        val held = readback()
        return GrantResult.Completed(held, issued)
    }

    /** Check whether the three advanced perms are granted now. The
     *  HUD calls this on each open so the FPS line / direct-write path
     *  light up automatically the moment the user runs the script. */
    fun grantsCurrentlyHeld(): Grants {
        val pm = context.packageManager
        fun has(name: String) = pm.checkPermission(name, context.packageName) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return Grants(
            dump = has(android.Manifest.permission.DUMP),
            usageStats = has(android.Manifest.permission.PACKAGE_USAGE_STATS),
            writeSecureSettings = has(android.Manifest.permission.WRITE_SECURE_SETTINGS),
            sysfsWritable = isSysfsWritable(),
        )
    }

    /**
     * True when the one-time unlock script has been run AND the cpufreq
     * nodes are chmod 666 (app-UID-writable without root).
     *
     * This intentionally does NOT include PServer writability: PServer
     * is probed separately by [PServerWriter.isTransactable] and stored
     * in [CapabilityReport.pserverSysfsLive]. Keeping the two signals
     * separate ensures [CapabilityReport.sysfsDirectlyWritable] is true
     * ONLY for the chmod-direct path, not as a proxy for "anything can write".
     */
    private fun isSysfsWritable(): Boolean = isSysfsDirectlyWritable()

    /** Original direct-write probe; reads the value and writes it back
     *  unchanged. Reliable per-UID signal, no kernel effect. */
    private fun isSysfsDirectlyWritable(): Boolean {
        val cpufreq = java.io.File("/sys/devices/system/cpu/cpufreq")
        val policies = runCatching {
            cpufreq.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }.orEmpty()
        }.getOrElse {
            android.util.Log.e("CalibrateSoC", "isSysfsWritable: cannot list cpufreq dir: $it")
            return false
        }
        if (policies.isEmpty()) {
            android.util.Log.e("CalibrateSoC", "isSysfsWritable: no policy dirs found")
            return false
        }
        for (policyDir in policies) {
            val target = java.io.File(policyDir, "scaling_max_freq")
            if (!target.exists()) {
                android.util.Log.i("CalibrateSoC", "isSysfsWritable: ${target.path} doesn't exist")
                continue
            }
            val read = runCatching { target.readText().trim() }
            if (read.isFailure) {
                android.util.Log.e("CalibrateSoC", "isSysfsWritable: read ${target.path} failed: ${read.exceptionOrNull()}")
                continue
            }
            val current = read.getOrNull()
            if (current.isNullOrEmpty()) {
                android.util.Log.i("CalibrateSoC", "isSysfsWritable: ${target.path} empty")
                continue
            }
            val write = runCatching {
                target.bufferedWriter().use { it.write(current) }
            }
            if (write.isSuccess) {
                android.util.Log.i("CalibrateSoC", "isSysfsWritable: OK on ${target.path}")
                return true
            } else {
                android.util.Log.e("CalibrateSoC", "isSysfsWritable: write ${target.path} failed: ${write.exceptionOrNull()}")
            }
        }
        return false
    }

    /**
     * Result of [grantViaPServer].
     *
     *   - [NotAvailable]: PServer is not transactable on this device — NO grants
     *     were issued. The caller must fall back to the script path; it must NOT
     *     present this as a success.
     *   - [Completed]: the grants were issued and [held] is the HONEST re-read of
     *     which perms are actually granted now. [Completed.allGranted] is true only
     *     when all three advanced perms are held; otherwise the caller should show
     *     the honest partial state + the script fallback for what's missing.
     */
    sealed interface GrantResult {
        object NotAvailable : GrantResult
        data class Completed(
            val held: Grants,
            val issuedCommands: List<String>,
        ) : GrantResult {
            /** True only when all three advanced perms actually landed. */
            val allGranted: Boolean
                get() = held.dump && held.usageStats && held.writeSecureSettings
        }
    }

    /**
     * The complete set of things one-trust full setup grants — each a row in the
     * onboarding/settings checklist. STABLE contract the UI compiles against; do not
     * reorder or rename without updating the UI.
     *
     *   - [ROOT_PERMS]    — the three `pm grant` runtime perms as a group (true only
     *                       when ALL of DUMP + PACKAGE_USAGE_STATS + WRITE_SECURE_SETTINGS hold).
     *   - [USAGE_ACCESS]  — Usage Access (foreground detection / per-app auto-switch).
     *   - [OVERLAY]       — Display over other apps (the floating HUD).
     *   - [BATTERY]       — Battery-unrestricted (service survival through Doze).
     *   - [NOTIFICATIONS] — POST_NOTIFICATIONS (SDK 33+; auto-held on older releases).
     */
    enum class SetupItem { ROOT_PERMS, USAGE_ACCESS, OVERLAY, BATTERY, NOTIFICATIONS }

    /**
     * Result of [grantAllViaPServer] — the full one-trust setup.
     *
     *   - [NotAvailable]: PServer is not transactable on this device — NO commands
     *     were issued. The caller must fall back to the script + manual-Settings path;
     *     it must NOT present this as a success.
     *   - [Completed]: the grants were issued and [held] is the HONEST per-[SetupItem]
     *     re-read of what is actually granted now. [Completed.allGranted] is computed
     *     OFF THE READBACK MAP only (never off what was sent) and is true only when
     *     every item is held.
     */
    sealed interface FullSetupResult {
        object NotAvailable : FullSetupResult
        data class Completed(
            val held: Map<SetupItem, Boolean>,
            val issued: List<String>,
        ) : FullSetupResult {
            /** True only when EVERY setup item is held per the live readback. */
            val allGranted: Boolean
                get() = held.values.all { it }
        }
    }

    private companion object {
        /**
         * The three advanced perms we self-grant — IDENTICAL to
         * [io.github.mayusi.calibratesoc.data.tunables.writer.PServerCommandGuard]'s
         * GRANTABLE_PERMS allow-list. Kept as plain strings (matching the manifest
         * permission names) so the issued `pm grant` command is exactly the guarded
         * shape. Order: DUMP, PACKAGE_USAGE_STATS, WRITE_SECURE_SETTINGS.
         */
        val GRANTABLE_PERMS = listOf(
            "android.permission.DUMP",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.WRITE_SECURE_SETTINGS",
        )

        /**
         * The notifications runtime perm — pm-granted on SDK 33+ ONLY (added to the
         * guard's GRANTABLE_PERMS allow-list). On older releases notifications are on
         * by default, so [grantAllViaExecutor] skips issuing it.
         */
        const val POST_NOTIFICATIONS_PERM = "android.permission.POST_NOTIFICATIONS"

        /**
         * The two AppOps special-access ops we flip to `allow` via PServer-root —
         * Usage Access + Display-over-other-apps. IDENTICAL to the guard's
         * APPOPS_OPS allow-list (kept in sync; the guard only permits these two op
         * names, mode allow/default, our package). The `appops set` command shape is
         * `appops set <pkg> <op> allow`.
         */
        val SPECIAL_ACCESS_OPS = listOf(
            "android:get_usage_stats",
            "android:system_alert_window",
        )
    }

    data class Deployed(val path: String, val visibleToOdinPicker: Boolean)
    data class Grants(
        val dump: Boolean,
        val usageStats: Boolean,
        val writeSecureSettings: Boolean,
        val sysfsWritable: Boolean,
    ) {
        val anyHeld: Boolean get() = dump || usageStats || writeSecureSettings || sysfsWritable
        val allHeld: Boolean get() = dump && usageStats && writeSecureSettings && sysfsWritable
    }
}
