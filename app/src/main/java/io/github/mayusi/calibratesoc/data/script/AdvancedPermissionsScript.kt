package io.github.mayusi.calibratesoc.data.script

import android.content.Context
import android.os.Build
import android.os.Environment
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
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val isAynDevice = "ayn" in manufacturer || "odin" in model
        val body = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Calibrate SoC — unlock script")
            appendLine("# REQUIRES \"Force SELinux\" ON in your device's settings (if it")
            appendLine("# has the toggle) — otherwise the chmod 666 calls below silently")
            appendLine("# fail and the HUD +/- buttons won't write sysfs directly.")
            appendLine()
            appendLine("# === 1. Permissions (persist across reboot) ===")
            appendLine("pm grant $pkg android.permission.DUMP")
            appendLine("pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
            appendLine("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            appendLine()
            // AYN-specific PServer whitelist step.
            // Only emitted when we detect an AYN/Odin device at script-deploy time;
            // also includes a runtime shell guard (service list check) as defence-in-depth.
            //
            // MECHANISM: AYN's PServerBinder gates transact() on the caller UID matching
            // an entry in Settings.System/app_whiteList. langerhans' OdinTools is in the
            // list by default; we are not. Adding our package name here makes PServer
            // accept our transacts, enabling live sysfs writes without per-boot chmod.
            //
            // HONESTY: This is the best-known mechanism based on:
            //   (a) the error message in PServerWriter.write() which names app_whiteList
            //       as the gate (and gives the exact add-command),
            //   (b) on-device behaviour: transact returns -1/UNKNOWN_TRANSACTION before
            //       this step, 0/success after.
            // MUST BE VERIFIED on Odin 3 after running the script. If PServer gates on
            // something other than this key (e.g. a file under /data/vendor/ or a SELinux
            // label), this step will be a harmless no-op and transactableNow() will remain
            // false — the app will then honestly fall back to UnlockedFileWriter/NoopWriter.
            if (isAynDevice) {
                appendLine("# === 1a. AYN/Odin PServer whitelist (PSERVER-LIVE tier) ===")
                appendLine("# This adds our package to PServer's app_whiteList so the binder")
                appendLine("# accepts our transact() calls. After this, Calibrate SoC can write")
                appendLine("# sysfs nodes (cpu freq, GPU pwrlevel) via PServer's root shell —")
                appendLine("# no per-boot chmod needed.")
                appendLine("# Self-guards: only runs if PServerBinder is present on this device.")
                appendLine("if service list 2>/dev/null | grep -q 'PServerBinder'; then")
                appendLine("  current_list=\$(settings get system app_whiteList 2>/dev/null)")
                appendLine("  if echo \"\$current_list\" | grep -qF '$pkg'; then")
                appendLine("    echo 'PServer whitelist: $pkg already present, skipping.'")
                appendLine("  else")
                appendLine("    if [ -z \"\$current_list\" ] || [ \"\$current_list\" = 'null' ]; then")
                appendLine("      settings put system app_whiteList '$pkg'")
                appendLine("    else")
                appendLine("      settings put system app_whiteList \"\$current_list,$pkg\"")
                appendLine("    fi")
                appendLine("    echo 'PServer whitelist: added $pkg'")
                appendLine("    echo 'Verify: settings get system app_whiteList'")
                appendLine("  fi")
                appendLine("else")
                appendLine("  echo 'PServer whitelist: PServerBinder not present on this device, skipping.'")
                appendLine("fi")
            }
            appendLine()
            appendLine("# === 2. Stop vendor perf daemons so they can't clobber our writes ===")
            appendLine("stop perfd 2>/dev/null")
            appendLine("stop perf-hal-1-0 2>/dev/null")
            appendLine("stop perf-hal-1-1 2>/dev/null")
            appendLine("stop perf-hal-1-2 2>/dev/null")
            appendLine("stop vendor.perfservice 2>/dev/null")
            appendLine()
            appendLine("# === 3. chmod 666 on every cpufreq policy ===")
            appendLine("# With SELinux permissive, this STICKS. The HUD's app UID can")
            appendLine("# then write scaling_max_freq / _min_freq / _governor directly")
            appendLine("# via FileWriter. Zero Odin Settings round-trips per ± tap.")
            appendLine("# Resets at reboot — re-run this script after each boot, or")
            appendLine("# leave Force SELinux ON and re-run only the chmod loop.")
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
            appendLine("# A '666' result here means the HUD can now write directly.")
            appendLine("# Anything still '444' means SELinux blocked the chmod (Force")
            appendLine("# SELinux probably wasn't ON when this script ran).")
            appendLine("echo 'Calibrate SoC unlock complete.'")
            appendLine("for p in /sys/devices/system/cpu/cpufreq/policy*; do")
            appendLine("  m=\$(stat -c %a \"\$p/scaling_max_freq\" 2>/dev/null)")
            appendLine("  echo \"  \$(basename \$p): scaling_max_freq mode=\$m\"")
            appendLine("done")
            appendLine("echo 'If any mode is 444 instead of 666 — enable Force SELinux in Odin Settings and re-run.'")
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
