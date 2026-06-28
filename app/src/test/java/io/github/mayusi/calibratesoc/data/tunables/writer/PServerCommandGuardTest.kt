package io.github.mayusi.calibratesoc.data.tunables.writer

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.fancurve.ConfigFileMetadata
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveScript
import org.junit.Test

/**
 * Provably-safe command guard test.
 *
 * Two tables:
 *  - ALLOW: every REAL command shape produced by an executeShell / writeSysfs /
 *    writeSettingsSystem call-site. This IS the regression suite — if any of these
 *    starts failing, the guard would break a legitimate app feature (tuning,
 *    /proc/stat reads, the fan curve, app reaping, FPS sampling).
 *  - DENY: destructive / out-of-allow-list commands that MUST be blocked, plus the
 *    proof that the Odin fan-script carve-out is path-tight (a fan-script-LOOKING
 *    command aimed at a non-Odin path is denied).
 */
class PServerCommandGuardTest {

    private fun allow(cmd: String) {
        val v = PServerCommandGuard.inspect(cmd)
        assertThat(v).isInstanceOf(PServerCommandGuard.Verdict.Allow::class.java)
    }

    private fun deny(cmd: String) {
        val v = PServerCommandGuard.inspect(cmd)
        assertThat(v).isInstanceOf(PServerCommandGuard.Verdict.Deny::class.java)
    }

    // ── The real fan-curve apply script, built from the production builder ────
    private fun realFanScript(): String =
        FanCurveScript.buildApplyScript(
            newXmlBase64 = "PD94bWwgdmVyc2lvbj0nMS4wJz8+PG1hcD48L21hcD4=",
            original = ConfigFileMetadata(
                ownerGroup = "system:system",
                mode = "660",
                seContext = "u:object_r:system_app_data_file:s0",
            ),
        )

    private fun realFanScriptUnknownMeta(): String =
        FanCurveScript.buildApplyScript(
            newXmlBase64 = "AAAA",
            original = ConfigFileMetadata.UNKNOWN,
        )

    // ─────────────────────────────────────────────────────────────────────────
    //  ALLOW TABLE — every legitimate call shape MUST pass
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun allowTable_allRealShapesPass() {
        val allowed = listOf(
            // 1. cat sysfs / cat /proc/stat (HardwareScanner, CpuLoadSource, etc.)
            "cat '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'",
            "cat /sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq",
            "cat /proc/stat",
            "cat '/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq'",

            // 2. StorageSpeedTester drop_caches flush (the ONE allowed /proc/sys write)
            "sync; echo 3 > /proc/sys/vm/drop_caches",

            // 3. writeSysfs chmod-sandwich (TunableWriter / PServerWriter.writeSysfs)
            "chmod 666 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq' && " +
                "printf %s '2745600' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'; " +
                "chmod 444 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'",
            "chmod 644 '/sys/class/kgsl/kgsl-3d0/devfreq/max_freq' && " +
                "printf %s '1000000' > '/sys/class/kgsl/kgsl-3d0/devfreq/max_freq'; " +
                "chmod 444 '/sys/class/kgsl/kgsl-3d0/devfreq/max_freq'",

            // 4. settings put/get system (writeSettingsSystem, fan-curve read)
            "settings put system 'fan_mode' '4'",
            "settings put system fan_mode 4",
            "settings get system fan_mode",
            "settings put system performance_mode '2'",

            // 5. pm grant — our packages + the three known perms
            "pm grant io.github.mayusi.calibratesoc android.permission.DUMP",
            "pm grant io.github.mayusi.calibratesoc.debug android.permission.DUMP",
            "pm grant io.github.mayusi.calibratesoc android.permission.PACKAGE_USAGE_STATS",
            "pm grant io.github.mayusi.calibratesoc.debug android.permission.WRITE_SECURE_SETTINGS",

            // 6. am force-stop / set-inactive (AppReaper)
            "am force-stop com.foo.game",
            "am force-stop 'com.foo.game'; am set-inactive 'com.foo.game' true",

            // 7. SurfaceFlinger dumpsys trio + gfxinfo (GameFpsSampler)
            "dumpsys SurfaceFlinger --list",
            "dumpsys SurfaceFlinger --version",
            "dumpsys SurfaceFlinger --latency 'SurfaceView[com.foo/com.foo.MainActivity]#0'",
            // layer name containing shell metacharacters (must stay one quoted token)
            "dumpsys SurfaceFlinger --latency 'Surf;ace|View && weird #0'",
            "dumpsys gfxinfo com.foo.game",

            // 8. fan-curve apply script (the legitimate Odin path with mv/rm/chown/kill)
            realFanScript(),
            realFanScriptUnknownMeta(),

            // 9. read helpers
            "getprop ro.product.model",
            "getprop",
            "getenforce",
            "service list",
            "stat -c '%U:%G %a' '${FanCurveScript.CONFIG_XML_PATH}'",
            "stat -c '%C' '${FanCurveScript.CONFIG_XML_PATH}'",

            // 10. chaining + pipe + daemon hooks
            "stop perfd",
            "start perfd",
            "stop vendor.perf-hal-1-0; stop vendor.perf-hal-2-0",
            "start vendor.perf-hal-1-0; start vendor.perf-hal-2-0",
            // pipe form: cat | grep, service list | grep
            "cat /proc/stat | grep 'cpu'",
            "service list | grep -q 'PServerBinder'",

            // fan-curve verification reads (FanCurveScript read commands)
            FanCurveScript.readConfigCommand(),
            FanCurveScript.readFanDutyCommand(),
            FanCurveScript.readFanPeriodCommand(),
            FanCurveScript.readFanStateCommand(),
            FanCurveScript.readFanModeCommand(),
            FanCurveScript.readConfigMetadataCommand(),

            // the no-op transactability probe
            "true",

            // 11. WAVE 2: perf node families that PServer-LIVE now writes. These are
            // the chmod-sandwich shapes PServerWriter.writeSysfs emits for the nodes
            // CapabilityProbe already discovers (DDR/bus devfreq, IO scheduler,
            // input-boost) — all clean /sys nodes, so they pass the existing /sys rule.
            sandwich("/sys/class/devfreq/19091000.cpubw/max_freq", "1804000000"),
            sandwich("/sys/class/devfreq/19091000.cpubw/min_freq", "300000000"),
            sandwich("/sys/block/sda/queue/scheduler", "mq-deadline"),
            sandwich("/sys/block/sda/queue/read_ahead_kb", "128"),
            sandwich("/sys/module/cpu_boost/parameters/input_boost_freq", "0:1804800"),

            // 12. WAVE 2: cgroup-boost carve-out — uclamp (/dev/cpuctl) + schedtune
            // (/dev/stune). These are NOT /sys or /proc, so they exercise the NARROW
            // allow widening. Only the exact modeled node shapes are permitted.
            sandwich("/dev/cpuctl/top-app/cpu.uclamp.min", "20"),
            sandwich("/dev/cpuctl/foreground/cpu.uclamp.max", "80"),
            sandwich("/dev/stune/top-app/schedtune.boost", "30"),
            sandwich("/dev/stune/foreground/schedtune.prefer_idle", "1"),
            "cat '/dev/cpuctl/top-app/cpu.uclamp.min'",

            // 13. WAVE 3B: root foreground-package reads (RootForegroundReader).
            // dumpsys activity activities — narrowly gated to the `activities`
            // sub-command only. Piped through grep (grep on the allow-list already).
            "dumpsys activity activities | grep 'mResumedActivity'",
            // dumpsys window — bare form only (no args). Contains mCurrentFocus.
            "dumpsys window | grep 'mCurrentFocus'",

            // 14. FULL one-trust setup — the special-access grants. Each is OUR
            // package only, exact op/mode/sub-command only (see deny table for the
            // tightness proof). pm grant POST_NOTIFICATIONS is the SDK-33 addition.
            "pm grant io.github.mayusi.calibratesoc android.permission.POST_NOTIFICATIONS",
            "pm grant io.github.mayusi.calibratesoc.debug android.permission.POST_NOTIFICATIONS",
            // appops set — Usage Access + Overlay, allow + default (reset) modes.
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats allow",
            "appops set io.github.mayusi.calibratesoc android:system_alert_window allow",
            "appops set io.github.mayusi.calibratesoc.debug android:get_usage_stats allow",
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats default",
            "appops set io.github.mayusi.calibratesoc android:system_alert_window default",
            // appops get — read-only readback.
            "appops get io.github.mayusi.calibratesoc android:get_usage_stats",
            "appops get io.github.mayusi.calibratesoc android:system_alert_window",
            // dumpsys deviceidle whitelist — add/remove our pkg + bare read.
            "dumpsys deviceidle whitelist +io.github.mayusi.calibratesoc",
            "dumpsys deviceidle whitelist +io.github.mayusi.calibratesoc.debug",
            "dumpsys deviceidle whitelist -io.github.mayusi.calibratesoc",
            "dumpsys deviceidle whitelist",
        )
        val failures = allowed.filter {
            PServerCommandGuard.inspect(it) !is PServerCommandGuard.Verdict.Allow
        }
        assertThat(failures).isEmpty()
    }

    /** The exact chmod-sandwich PServerWriter.writeSysfs emits for a sysfs write. */
    private fun sandwich(path: String, value: String): String =
        "chmod 666 '$path' && printf %s '$value' > '$path'; chmod 444 '$path'"

    // Individual ALLOW assertions (so a single regression names the exact shape).

    @Test fun allow_catSysfs() = allow("cat '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")
    @Test fun allow_catProcStat() = allow("cat /proc/stat")
    @Test fun allow_dropCachesFlush() = allow("sync; echo 3 > /proc/sys/vm/drop_caches")
    @Test fun allow_chmodSandwich() = allow(
        "chmod 666 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq' && " +
            "printf %s '2745600' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'; " +
            "chmod 444 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'",
    )
    @Test fun allow_settingsPut() = allow("settings put system fan_mode 4")
    @Test fun allow_settingsGet() = allow("settings get system fan_mode")
    @Test fun allow_pmGrantDebug() = allow("pm grant io.github.mayusi.calibratesoc.debug android.permission.DUMP")
    @Test fun allow_amForceStop() = allow("am force-stop com.foo")
    @Test fun allow_amReaperPair() = allow("am force-stop 'com.foo'; am set-inactive 'com.foo' true")
    @Test fun allow_sfList() = allow("dumpsys SurfaceFlinger --list")
    @Test fun allow_sfVersion() = allow("dumpsys SurfaceFlinger --version")
    @Test fun allow_sfLatency() = allow("dumpsys SurfaceFlinger --latency 'Layer#0'")
    @Test fun allow_getenforce() = allow("getenforce")
    @Test fun allow_fanScript() = allow(realFanScript())
    @Test fun allow_fanScriptUnknownMeta() = allow(realFanScriptUnknownMeta())
    @Test fun allow_stopPerfd() = allow("stop perfd")
    @Test fun allow_startPerfd() = allow("start perfd")

    // WAVE 2 — newly-routed perf nodes (chmod-sandwich) MUST pass.
    @Test fun allow_ddrDevfreqMaxSandwich() =
        allow(sandwich("/sys/class/devfreq/19091000.cpubw/max_freq", "1804000000"))
    @Test fun allow_ioSchedulerSandwich() =
        allow(sandwich("/sys/block/sda/queue/scheduler", "mq-deadline"))
    @Test fun allow_inputBoostSandwich() =
        allow(sandwich("/sys/module/cpu_boost/parameters/input_boost_freq", "0:1804800"))
    @Test fun allow_uclampMinSandwich() =
        allow(sandwich("/dev/cpuctl/top-app/cpu.uclamp.min", "20"))
    @Test fun allow_uclampMaxSandwich() =
        allow(sandwich("/dev/cpuctl/top-app/cpu.uclamp.max", "80"))
    @Test fun allow_schedtuneBoostSandwich() =
        allow(sandwich("/dev/stune/top-app/schedtune.boost", "30"))
    @Test fun allow_schedtunePreferIdleSandwich() =
        allow(sandwich("/dev/stune/foreground/schedtune.prefer_idle", "1"))

    // WAVE 3B — root foreground-package reads (RootForegroundReader).
    // The two new dumpsys subjects must pass; all other activity sub-commands
    // and dumpsys window with extra args must be denied (tightness proof below).
    @Test fun allow_dumpsysActivityActivities() =
        allow("dumpsys activity activities | grep 'mResumedActivity'")
    @Test fun allow_dumpsysActivityTopActivity() =
        allow("dumpsys activity top-activity | grep 'mResumedActivity'")
    @Test fun allow_dumpsysWindow() =
        allow("dumpsys window | grep 'mCurrentFocus'")
    // bare (no grep) forms are also valid — grep is optional in the pipe
    @Test fun allow_dumpsysActivityActivitiesBare() =
        allow("dumpsys activity activities")
    @Test fun allow_dumpsysWindowBare() =
        allow("dumpsys window")

    // FULL one-trust setup — special-access allow shapes (our pkg, exact op/mode/sub).
    @Test fun allow_pmGrantPostNotifications() =
        allow("pm grant io.github.mayusi.calibratesoc android.permission.POST_NOTIFICATIONS")
    @Test fun allow_appopsSetUsageStatsAllow() =
        allow("appops set io.github.mayusi.calibratesoc android:get_usage_stats allow")
    @Test fun allow_appopsSetOverlayAllow() =
        allow("appops set io.github.mayusi.calibratesoc android:system_alert_window allow")
    @Test fun allow_appopsSetDefaultMode() =
        allow("appops set io.github.mayusi.calibratesoc android:get_usage_stats default")
    @Test fun allow_appopsGetUsageStats() =
        allow("appops get io.github.mayusi.calibratesoc android:get_usage_stats")
    @Test fun allow_deviceidleWhitelistAddOurPkg() =
        allow("dumpsys deviceidle whitelist +io.github.mayusi.calibratesoc")
    @Test fun allow_deviceidleWhitelistRemoveOurPkg() =
        allow("dumpsys deviceidle whitelist -io.github.mayusi.calibratesoc")
    @Test fun allow_deviceidleWhitelistReadBare() =
        allow("dumpsys deviceidle whitelist")

    // ─────────────────────────────────────────────────────────────────────────
    //  DENY TABLE — every destructive / out-of-allow-list command MUST be blocked
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun denyTable_allDestructiveBlocked() {
        val denied = listOf(
            // the device-wipe class
            "rm -rf /",
            "rm -rf /sdcard",
            "rm -rf \"\$X\"/*",
            "rm -rf '/sdcard'/*",
            "dd if=/dev/zero of=/dev/block/sda",
            "dd if=/dev/zero of=/dev/block/bootdevice/by-name/userdata bs=4M",
            // arbitrary writes to non-sysfs targets
            "printf x > /data/foo",
            "printf x > /sdcard/x",
            "echo pwned > /system/build.prop",
            "echo x > /data/local/tmp/x",
            // general mv / cp / ln / chmod / chown outside sysfs + odin
            "mv /data/a /data/b",
            "cp /data/a /data/b",
            "ln -s /data/a /data/b",
            "chmod 666 /data/x",
            "chmod 777 /system/bin/sh",
            "chown root:root /data/x",
            // SELinux toggle — absolute deny
            "setenforce 0",
            "setenforce 1",
            // device-kill primitives
            "reboot",
            "reboot recovery",
            "poweroff",
            "svc power reboot",
            // package destruction
            "pm uninstall com.foo",
            "pm clear com.foo",
            "pm disable com.foo",
            // pm grant to a foreign package / unknown perm
            "pm grant com.evil.app android.permission.DUMP",
            "pm grant io.github.mayusi.calibratesoc android.permission.INSTALL_PACKAGES",
            // traversal + dangerous nodes
            "cat /sys/../etc/passwd",
            "cat '/proc/sys/kernel/../../etc/shadow'",
            "echo x > /sys/class/thermal/thermal_zone0/trip_point_0_temp",
            "printf %s 'x' > '/proc/sysrq-trigger'",
            "printf %s '4400000' > '/sys/class/power_supply/battery/constant_charge_current_max'",
            "cat /proc/kmem",
            // raw control characters / NUL / newline injection
            "cat /sys/devices/x\u0000rm -rf /",         // embedded NUL byte (escaped — no raw NUL in source)
            "cat /sys/devices/x\nrm -rf /",             // raw newline (outside fan script)
            "cat /sys/a /sys/b",                        // multi-arg cat (only one path allowed)
            "cat /etc/passwd",                          // non-sysfs read
            // unknown verbs / formats
            "format",
            "mkfs.ext4 /dev/block/sda",
            "make_ext4fs /dev/block/sda",
            "su -c 'rm -rf /'",
            "sh -c 'rm -rf /'",
            "busybox rm -rf /",
            "content insert --uri content://x",
            "am start -a android.intent.action.VIEW",
            "am broadcast -a com.foo.ACTION",
            // start/stop of a NON-perf service
            "stop netd",
            "start zygote",
            "stop perfd; rm -rf /",
            // redirect to /system / arbitrary
            "printf %s 'x' > /system/lib/libc.so",
            // WAVE 2 — the cgroup-boost carve-out is NARROW, NOT a blanket /dev/ allow.
            // Any /dev path that isn't the exact modeled uclamp/schedtune node stays denied.
            "printf %s 'x' > '/dev/block/sda'",
            "printf %s 'x' > '/dev/cpuctl/top-app/cgroup.procs'",   // wrong file in cpuctl
            "printf %s 'x' > '/dev/cpuctl/../something/cpu.uclamp.min'", // traversal
            "printf %s 'x' > '/dev/stune/top-app/tasks'",          // wrong file in stune
            "printf %s 'x' > '/dev/cpuctl/top-app/cpu.shares'",    // not a boost node
            "printf %s 'x' > '/dev/ptmx'",
            "cat '/dev/kmsg'",
            // WAVE 3B — dumpsys activity/window tightness proof.
            // Only the exact two sub-commands and bare window form are permitted;
            // anything else (services, broadcasts, arbitrary sub-commands) must deny.
            "dumpsys activity services",            // disallowed sub-command
            "dumpsys activity broadcasts",          // disallowed sub-command
            "dumpsys activity all",                 // disallowed sub-command
            "dumpsys activity",                     // missing required sub-command arg
            "dumpsys activity activities extra",    // too many args
            "dumpsys window extra-arg",             // window takes no args
            "dumpsys batterystats",                 // not on allow-list
            "dumpsys package com.foo",              // not on allow-list
            // FULL one-trust setup — the special-access widening is default-deny /
            // our-package-only / exact-op-mode-subcommand-only. Everything off-pattern denies.
            // appops on a FOREIGN package:
            "appops set com.evil.app android:get_usage_stats allow",
            "appops get com.evil.app android:get_usage_stats",
            // appops with an UNKNOWN op (even on our package):
            "appops set io.github.mayusi.calibratesoc android:write_settings allow",
            "appops set io.github.mayusi.calibratesoc android:camera allow",
            "appops get io.github.mayusi.calibratesoc android:camera",
            // appops with a mode OTHER than allow/default (deny/ignore/foreign):
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats deny",
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats ignore",
            "appops set io.github.mayusi.calibratesoc android:system_alert_window foreground",
            // appops malformed / wrong arity / wrong sub-command:
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats",  // missing mode
            "appops reset io.github.mayusi.calibratesoc",                        // reset wipes ALL ops
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats allow extra",
            // deviceidle — only `whitelist` sub-command, only our pkg target:
            "dumpsys deviceidle whitelist +com.evil.app",   // foreign pkg
            "dumpsys deviceidle whitelist -com.evil.app",   // foreign pkg
            "dumpsys deviceidle whitelist io.github.mayusi.calibratesoc", // missing +/- sign
            "dumpsys deviceidle force-idle",                // disallowed sub-command
            "dumpsys deviceidle disable",                   // disallowed sub-command
            "dumpsys deviceidle step",                      // disallowed sub-command
            "dumpsys deviceidle whitelist +io.github.mayusi.calibratesoc extra", // too many args
        )
        val leaked = denied.filter {
            PServerCommandGuard.inspect(it) !is PServerCommandGuard.Verdict.Deny
        }
        assertThat(leaked).isEmpty()
    }

    // Individual DENY assertions (named for fast diagnosis).

    @Test fun deny_rmRfRoot() = deny("rm -rf /")
    @Test fun deny_rmRfSdcard() = deny("rm -rf /sdcard")
    @Test fun deny_rmGlobVar() = deny("rm -rf \"\$X\"/*")
    @Test fun deny_ddBlock() = deny("dd if=/dev/zero of=/dev/block/sda")
    @Test fun deny_printfToData() = deny("printf x > /data/foo")
    @Test fun deny_printfToSdcard() = deny("printf x > /sdcard/x")
    @Test fun deny_mvData() = deny("mv /data/a /data/b")
    @Test fun deny_setenforce() = deny("setenforce 0")
    @Test fun deny_reboot() = deny("reboot")
    @Test fun deny_pmUninstall() = deny("pm uninstall com.foo")
    @Test fun deny_traversal() = deny("cat /sys/../etc/passwd")
    @Test fun deny_dangerousThermalNode() =
        deny("echo x > /sys/class/thermal/thermal_zone0/trip_point_0_temp")
    @Test fun deny_dangerousThermalTripHyst() =
        deny("printf %s '95000' > /sys/class/thermal/thermal_zone3/trip_point_1_hyst")
    // Defense-in-depth: trip points are categorically dangerous at the metadata layer,
    // so even a future write path can't slip one past the guard.
    @Test fun isDangerousPath_blocksThermalTripPoint() {
        assertThat(
            io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
                .isDangerousPath("/sys/class/thermal/thermal_zone0/trip_point_0_temp")
        ).isTrue()
    }
    @Test fun isDangerousPath_allowsNonTripThermalNode() {
        // A plain temperature read node is NOT a trip point — must stay readable.
        assertThat(
            io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
                .isDangerousPath("/sys/class/thermal/thermal_zone0/temp")
        ).isFalse()
    }
    @Test fun deny_chmodData() = deny("chmod 666 /data/x")
    @Test fun deny_nulByte() = deny("cat /sys/devices/x\u0000rm -rf /")
    @Test fun deny_multiArgCat() = deny("cat /sys/a /sys/b")
    @Test fun deny_nonSysfsRead() = deny("cat /etc/passwd")
    @Test fun deny_format() = deny("format")
    @Test fun deny_redirectToSystem() = deny("printf %s 'x' > /system/lib/libc.so")
    // WAVE 2 — cgroup carve-out tightness.
    @Test fun deny_devBlockWrite() = deny("printf %s 'x' > '/dev/block/sda'")
    @Test fun deny_cpuctlWrongFile() = deny("printf %s 'x' > '/dev/cpuctl/top-app/cgroup.procs'")
    @Test fun deny_stuneWrongFile() = deny("printf %s 'x' > '/dev/stune/top-app/tasks'")
    @Test fun deny_cgroupTraversal() = deny("printf %s 'x' > '/dev/cpuctl/../x/cpu.uclamp.min'")
    @Test fun deny_devPtmx() = deny("printf %s 'x' > '/dev/ptmx'")
    // WAVE 3B — dumpsys activity/window carve-out tightness.
    // Only `activities` and `top-activity` sub-commands are allowed; anything else
    // (services, broadcasts, arbitrary sub-commands, missing sub-command) is denied.
    // dumpsys window takes NO extra args — any arg after window is denied.
    @Test fun deny_dumpsysActivityServices() = deny("dumpsys activity services")
    @Test fun deny_dumpsysActivityBroadcasts() = deny("dumpsys activity broadcasts")
    @Test fun deny_dumpsysActivityAll() = deny("dumpsys activity all")
    @Test fun deny_dumpsysActivityNoSubCmd() = deny("dumpsys activity")
    @Test fun deny_dumpsysActivityTooManyArgs() = deny("dumpsys activity activities extra")
    @Test fun deny_dumpsysWindowWithArg() = deny("dumpsys window extra-arg")
    @Test fun deny_dumpsysBatterystats() = deny("dumpsys batterystats")
    @Test fun deny_dumpsysPackage() = deny("dumpsys package com.foo")

    // FULL one-trust setup — widening tightness proof (default-deny / our-pkg-only).
    @Test fun deny_appopsForeignPackage() =
        deny("appops set com.evil.app android:get_usage_stats allow")
    @Test fun deny_appopsGetForeignPackage() =
        deny("appops get com.evil.app android:get_usage_stats")
    @Test fun deny_appopsUnknownOp() =
        deny("appops set io.github.mayusi.calibratesoc android:write_settings allow")
    @Test fun deny_appopsCameraOp() =
        deny("appops set io.github.mayusi.calibratesoc android:camera allow")
    @Test fun deny_appopsModeDeny() =
        deny("appops set io.github.mayusi.calibratesoc android:get_usage_stats deny")
    @Test fun deny_appopsModeIgnore() =
        deny("appops set io.github.mayusi.calibratesoc android:get_usage_stats ignore")
    @Test fun deny_appopsMissingMode() =
        deny("appops set io.github.mayusi.calibratesoc android:get_usage_stats")
    @Test fun deny_appopsResetSubcommand() =
        deny("appops reset io.github.mayusi.calibratesoc")
    @Test fun deny_deviceidleWhitelistForeignPkg() =
        deny("dumpsys deviceidle whitelist +com.evil.app")
    @Test fun deny_deviceidleWhitelistNoSign() =
        deny("dumpsys deviceidle whitelist io.github.mayusi.calibratesoc")
    @Test fun deny_deviceidleForceIdleSubcommand() =
        deny("dumpsys deviceidle force-idle")
    @Test fun deny_deviceidleDisableSubcommand() =
        deny("dumpsys deviceidle disable")
    @Test fun deny_pmGrantPostNotificationsForeignPkg() =
        deny("pm grant com.evil.app android.permission.POST_NOTIFICATIONS")

    // ── The fan-carve-out tightness proof ─────────────────────────────────────
    // A command that LOOKS like the fan script but aims at a non-Odin path must
    // be denied. This proves rm-f/mv/chown are permitted ONLY for the Odin config.

    @Test
    fun deny_fanScriptLookalike_nonOdinTmp() {
        // Same `if printf … | base64 -d` opening, but the tmp/redirect target is /sdcard.
        val evil = "if printf %s 'AAAA' | base64 -d > '/sdcard/evil.tmp' && " +
            "mv -f '/sdcard/evil.tmp' '/sdcard/evil' && chmod '660' '/sdcard/evil'" +
            "; then rm -f '/sdcard/evil'; else rm -f '/sdcard/evil'; exit 1; fi"
        deny(evil)
    }

    @Test
    fun deny_fanScriptLookalike_rmToSdcard() {
        // The tight-carve-out proof from the spec: a fan-script-LOOKING rm aimed
        // at /sdcard must DENY.
        deny("rm -f /sdcard/x")
    }

    @Test
    fun deny_fanScriptLookalike_killForeignProcess() {
        val evil = "if printf %s 'AAAA' | base64 -d > '${FanCurveScript.CONFIG_XML_PATH}.calibrate.tmp' && " +
            "mv -f '${FanCurveScript.CONFIG_XML_PATH}.calibrate.tmp' '${FanCurveScript.CONFIG_XML_PATH}'" +
            "; then kill -9 \$(pidof com.android.systemui); else exit 1; fi"
        deny(evil)
    }

    @Test
    fun deny_fanScriptLookalike_extraDestructiveTail() {
        // Genuine fan-script prefix but a smuggled `rm -rf /` in the success tail.
        val base = realFanScript()
        val tampered = base.replace("; fi", "; rm -rf /; fi")
        deny(tampered)
    }

    @Test
    fun deny_bareRmFofOdin_outsideFanScript() {
        // A bare `rm -f <odin-tmp>` NOT inside the fan-script structure is still
        // denied — rm is hard-denied in the generic path; the carve-out only
        // applies to the whole validated if/fi blob.
        deny("rm -f '${FanCurveScript.CONFIG_XML_PATH}.calibrate.tmp'")
    }

    // ── Pipe / chaining segment isolation ─────────────────────────────────────

    @Test
    fun deny_pipeWithDestructiveRightSide() {
        // Every segment must independently pass; a benign left side cannot smuggle
        // a destructive right side.
        deny("cat /proc/stat | rm -rf /")
    }

    @Test
    fun deny_chainWithOneBadSegment() {
        deny("settings put system fan_mode 4 && rm -rf /sdcard")
    }

    @Test
    fun allow_pipeCatGrep() {
        allow("cat /proc/stat | grep cpu")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SECURITY HOLE CLOSURES — each test proves one audit-confirmed hole is shut
    //  at the GUARD (the unbypassable chokepoint), not merely at the door.
    // ═════════════════════════════════════════════════════════════════════════

    // ── CRITICAL-1: SELinux node + subtree (sysfs route around setenforce deny) ──

    @Test fun deny_selinuxEnforceWrite() =
        deny("chmod 666 '/sys/fs/selinux/enforce' && printf %s '0' > '/sys/fs/selinux/enforce'; chmod 444 '/sys/fs/selinux/enforce'")

    @Test fun deny_selinuxEnforcePrintf() =
        deny("printf %s '0' > '/sys/fs/selinux/enforce'")

    @Test fun deny_selinuxEnforceRead() =
        deny("cat '/sys/fs/selinux/enforce'")

    @Test fun deny_selinuxSubtreePolicy() =
        deny("printf %s 'x' > '/sys/fs/selinux/load'")

    @Test fun deny_selinuxSubtreeBoolean() =
        deny("printf %s '1' > '/sys/fs/selinux/booleans/some_bool'")

    // ── CRITICAL-2: shell-substitution metachars rejected AT THE GUARD ──────────
    // The door rejects these too, but these assertions prove the guard is
    // self-sufficient — a metachar path denies here even though no caller quoted it.

    @Test fun deny_commandSubstitutionInSysfsPath() =
        deny("cat /sys/devices/x\$(reboot)/cur_freq")

    @Test fun deny_backtickInSysfsPath() =
        deny("cat /sys/devices/x`reboot`/cur_freq")

    @Test fun deny_commandSubstitutionInWriteTarget() =
        deny("printf %s '1' > /sys/devices/x\$(rm -rf /)/y")

    @Test fun deny_semicolonMetacharInPath() =
        deny("cat /sys/foo;reboot")

    @Test fun deny_pipeMetacharInChmodPath() =
        deny("chmod 666 /sys/foo|reboot")

    // Echo arg-level command substitution (no redirect needed — shell expands first).
    @Test fun deny_echoCommandSubstitution() =
        deny("echo \$(reboot)")

    @Test fun deny_echoBacktick() =
        deny("echo `reboot`")

    @Test fun deny_settingsValueCommandSubstitution() =
        deny("settings put system fan_mode \$(reboot)")

    @Test fun deny_settingsValueBacktick() =
        deny("settings put system fan_mode `reboot`")

    // ── HIGH-3: suspend / driver-detach / sysrq / panic-on-* crash primitives ───

    @Test fun deny_powerStateSuspend() =
        deny("printf %s 'mem' > /sys/power/state")

    @Test fun deny_powerDiskHibernate() =
        deny("printf %s 'disk' > /sys/power/disk")

    @Test fun deny_powerWakeupCount() =
        deny("printf %s '0' > /sys/power/wakeup_count")

    @Test fun deny_driverUnbind() =
        deny("printf %s '1-1' > '/sys/bus/usb/drivers/usb/unbind'")

    @Test fun deny_driverBind() =
        deny("printf %s '1-1' > '/sys/bus/platform/drivers/foo/bind'")

    @Test fun deny_sysrqEnable() =
        deny("printf %s '1' > /proc/sys/kernel/sysrq")

    @Test fun deny_panicOnOops() =
        deny("printf %s '1' > /proc/sys/kernel/panic_on_oops")

    @Test fun deny_panicOnWarn() =
        deny("printf %s '1' > /proc/sys/kernel/panic_on_warn")

    // ── MEDIUM: chmod mode allow-list (000 / setuid / setgid blocked) ───────────

    @Test fun deny_chmod000() =
        deny("chmod 000 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")

    @Test fun deny_chmodSetuid4755() =
        deny("chmod 4755 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")

    @Test fun deny_chmodSetgid2755() =
        deny("chmod 2755 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")

    @Test fun deny_chmod777() =
        deny("chmod 777 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")

    // The legit sandwich modes (666 / 644 / 444) must still pass.
    @Test fun allow_chmod666_644_444_modes() {
        allow("chmod 666 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")
        allow("chmod 644 '/sys/class/kgsl/kgsl-3d0/devfreq/max_freq'")
        allow("chmod 444 '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")
    }

    // ── MEDIUM: am force-stop of a critical system package refused at the guard ──

    @Test fun deny_forceStopSystemui() =
        deny("am force-stop com.android.systemui")

    @Test fun deny_forceStopSystemServer() =
        deny("am force-stop system_server")

    @Test fun deny_forceStopAndroid() =
        deny("am force-stop android")

    @Test fun deny_forceStopLauncher() =
        deny("am force-stop com.android.launcher3")

    @Test fun deny_forceStopLauncherVariant() =
        deny("am force-stop com.android.launcher3.uioverrides")

    @Test fun deny_forceStopNexusLauncher() =
        deny("am force-stop com.google.android.apps.nexuslauncher")

    // A normal game package is still reapable.
    @Test fun allow_forceStopNormalGameStillWorks() =
        allow("am force-stop com.foo.game")

    // ── Regression guard: the full set of REAL legit writes still ALLOW ─────────
    // A focused re-assertion that the new denials did NOT break any sanctioned shape.
    @Test fun allow_legitWritesStillPassAfterHardening() {
        val legit = listOf(
            // cpufreq scaling_max_freq sandwich
            sandwich("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", "2745600"),
            // GPU pwrlevel / devfreq
            sandwich("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "1000000"),
            sandwich("/sys/class/kgsl/kgsl-3d0/min_pwrlevel", "0"),
            // WALT governor tunable (scheduler walt path)
            sandwich("/sys/devices/system/cpu/cpufreq/policy0/walt/up_rate_limit_us", "1000"),
            // governor write
            sandwich("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor", "performance"),
            // the Odin fan-script carve-out
            realFanScript(),
            // settings put (numeric value)
            "settings put system fan_mode 4",
            "settings put system performance_mode '2'",
            // pm grant our package
            "pm grant io.github.mayusi.calibratesoc android.permission.DUMP",
            // appops set our package
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats allow",
            // deviceidle whitelist our package
            "dumpsys deviceidle whitelist +io.github.mayusi.calibratesoc",
            // drop_caches flush
            "sync; echo 3 > /proc/sys/vm/drop_caches",
        )
        val failures = legit.filter {
            PServerCommandGuard.inspect(it) !is PServerCommandGuard.Verdict.Allow
        }
        assertThat(failures).isEmpty()
    }
}
