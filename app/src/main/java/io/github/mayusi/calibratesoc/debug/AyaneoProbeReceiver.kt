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
 * A tiny broadcast receiver so the AYANEO bind probe can be fired from adb
 * without any UI:
 *
 *   adb shell am broadcast \
 *     -a io.github.mayusi.calibratesoc.debug.PROBE_AYANEO \
 *     -n io.github.mayusi.calibratesoc.debug/io.github.mayusi.calibratesoc.debug.AyaneoProbeReceiver
 *
 * (the .debug applicationId suffix is already baked into the -n component above)
 *
 * Then watch:  adb logcat -s AYANEO_PROBE
 *
 * The receiver is declared android:enabled="false" by default in the merged
 * manifest and only flipped on for debug builds (see the tools:node="replace"
 * override in AndroidManifest.xml). As a second guard, onReceive() hard-returns
 * unless BuildConfig.DEBUG, so even if the component were somehow enabled in a
 * release build it does nothing.
 *
 * The probe runs on Dispatchers.IO via goAsync() so the bind/transact never
 * touches the main thread and the broadcast doesn't ANR while we wait on the
 * ~3 s bind. Optional override of the target via the extra:
 *
 *   --el targetHz 680000000     (to restore the stock ceiling)
 */
class AyaneoProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Hard release guard: this trigger is a development de-risk tool only.
        if (!BuildConfig.DEBUG) return
        if (intent?.action != ACTION_PROBE) return

        val targetHz = intent.getLongExtra(EXTRA_TARGET_HZ, DEFAULT_TARGET_HZ)
        Log.i(AyaneoBindProbe.TAG, "PROBE_AYANEO received — driving GPU max to ${targetHz}Hz")

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AyaneoBindProbe.tryDriveGpuMax(appContext, targetHz)
            } catch (t: Throwable) {
                // tryDriveGpuMax never throws, but never let the receiver crash.
                Log.e(AyaneoBindProbe.TAG, "probe crashed (should be impossible): ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_PROBE = "io.github.mayusi.calibratesoc.debug.PROBE_AYANEO"
        const val EXTRA_TARGET_HZ = "targetHz"

        // 585 MHz: a real OPP below the 680 MHz stock ceiling on the Pocket DS.
        // Observable (the node should change) yet harmless and fully reversible.
        const val DEFAULT_TARGET_HZ = 585_000_000L
    }
}
