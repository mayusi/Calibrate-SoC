package io.github.mayusi.calibratesoc.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.mayusi.calibratesoc.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DE-RISK PROBE TRIGGER — debug-only, isolated, removable.
 *
 * A broadcast receiver that fires [RetroidFanProbe.tryBind] from adb without
 * any UI, to answer: can a normal app bind SettingsController → FanProvider
 * on the Retroid Pocket 6 and set the fan speed?
 *
 * ## Fire the probe (set a custom fan speed)
 * ```
 * adb shell am broadcast \
 *   -a io.github.mayusi.calibratesoc.debug.PROBE_RETROID_FAN \
 *   -n io.github.mayusi.calibratesoc.debug/io.github.mayusi.calibratesoc.debug.RetroidFanProbeReceiver \
 *   --ei speed 128
 * ```
 *
 * ## Restore auto fan control
 * ```
 * adb shell am broadcast \
 *   -a io.github.mayusi.calibratesoc.debug.PROBE_RETROID_FAN \
 *   -n io.github.mayusi.calibratesoc.debug/io.github.mayusi.calibratesoc.debug.RetroidFanProbeReceiver \
 *   --ez restore true \
 *   --ei restoreMode 2
 * ```
 *
 * ## Watch the log
 * ```
 * adb logcat -s RP_FAN_PROBE
 * ```
 *
 * ## Physical cross-check (before AND after firing)
 * ```
 * adb shell cat /sys/class/hwmon/hwmon0/pwm1
 * ```
 *
 * ## Intent extras
 * | Extra         | Type    | Default | Meaning |
 * |---------------|---------|---------|---------|
 * | `speed`       | int     | 128     | Value passed to FanProvider txn 7 (r(int)). Unit is unknown — start conservative. |
 * | `restore`     | boolean | false   | When true, calls txn 5 (c(int)) with `restoreMode` instead of txn 7. |
 * | `restoreMode` | int     | 2       | Mode for txn 5. 2 = Smart/auto per RP6 gameassistant prefs. Try 1 or 4 if 2 does not restore. |
 *
 * ## Security model
 * The receiver is declared [android:enabled="false" android:exported="false"] in
 * the main manifest so it is completely inert in release builds. The
 * [app/src/debug/AndroidManifest.xml] overlay flips it to [enabled=true
 * exported=true] for debug builds only (via `tools:replace`). As a second
 * hard guard, [onReceive] returns immediately unless [BuildConfig.DEBUG].
 *
 * Delete this file, [RetroidFanProbe], the main-manifest receiver block, and
 * [app/src/debug/AndroidManifest.xml] once the probe question is answered.
 */
class RetroidFanProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Hard release guard — belt-and-suspenders behind the manifest disabled flag.
        if (!BuildConfig.DEBUG) return
        if (intent?.action != ACTION_PROBE) return

        val speed = intent.getIntExtra(EXTRA_SPEED, DEFAULT_SPEED)
        val restore = intent.getBooleanExtra(EXTRA_RESTORE, false)
        val restoreMode = intent.getIntExtra(EXTRA_RESTORE_MODE, DEFAULT_RESTORE_MODE)

        Log.i(RetroidFanProbe.TAG, "Broadcast received — speed=$speed  restore=$restore  restoreMode=$restoreMode")
        Log.i(RetroidFanProbe.TAG, "Cross-check BEFORE: adb shell \"cat /sys/class/hwmon/hwmon*/pwm1\"")

        val appContext = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = RetroidFanProbe.tryBind(
                    speed = speed,
                    restore = restore,
                    restoreMode = restoreMode,
                )
                Log.i(RetroidFanProbe.TAG, "Probe complete: $result")
                Log.i(RetroidFanProbe.TAG, "Cross-check AFTER:  adb shell \"cat /sys/class/hwmon/hwmon*/pwm1\"")
            } catch (t: Throwable) {
                // RetroidFanProbe.tryBind() never throws, but a receiver must
                // never bring the app down under any circumstances.
                Log.e(RetroidFanProbe.TAG, "Probe crashed (should be impossible): ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_PROBE = "io.github.mayusi.calibratesoc.debug.PROBE_RETROID_FAN"

        /** Fan speed value for txn 7. Unit unknown — 128 is a safe starting guess. */
        const val EXTRA_SPEED = "speed"
        const val DEFAULT_SPEED = 128

        /** When true: call txn 5 (set mode) instead of txn 7 (set speed). */
        const val EXTRA_RESTORE = "restore"

        /** Mode value for txn 5 when restoring. 2 = Smart/auto. */
        const val EXTRA_RESTORE_MODE = "restoreMode"
        const val DEFAULT_RESTORE_MODE = 2
    }
}
