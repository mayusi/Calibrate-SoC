package io.github.mayusi.calibratesoc.data.profiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AutoConfigUndo"

/**
 * Handles the "Undo / don't auto-tune this app" action on the auto-config
 * notification posted by [AutoConfigNotifier].
 *
 * On undo, for the given package, this:
 *  1. Removes the bundle Calibrate auto-created — but ONLY if it is still flagged
 *     [PerAppBundle.autoCreated]. If the user has since edited the bundle (so it
 *     is now user-owned) or replaced it, we leave it ALONE. This is the safety
 *     invariant: Undo can never silently delete a tune the user made theirs.
 *  2. Records the package in the auto-config OPT-OUT set so the watcher never
 *     re-creates a bundle for it on a future launch (one tap = permanent stop).
 *  3. Cancels the notification.
 *
 * Internal-only (not exported). Mirrors [io.github.mayusi.calibratesoc.data.script.BootScriptReminderReceiver]'s
 * goAsync + IO-coroutine pattern for the suspend DataStore / repository work.
 */
@AndroidEntryPoint
class AutoConfigUndoReceiver : BroadcastReceiver() {

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var userPrefs: UserPrefs
    @Inject lateinit var notifier: AutoConfigNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 1. Always opt out — this is the user's explicit "stop auto-tuning
                //    this game" signal and must hold even if the bundle is already
                //    gone or was edited.
                userPrefs.addAutoConfigOptOut(pkg)

                // 2. Remove the bundle ONLY if it is still the one we auto-created.
                val existing = profileRepository.snapshot().perAppBundles[pkg]
                when {
                    existing == null ->
                        Log.i(TAG, "undo($pkg): no bundle to remove (already cleared)")
                    existing.autoCreated -> {
                        profileRepository.clearPerAppMapping(pkg)
                        Log.i(TAG, "undo($pkg): removed auto-created bundle + opted out")
                    }
                    else ->
                        // User edited it after auto-create → it is theirs now. Opt out
                        // of future auto-config but DO NOT touch their bundle.
                        Log.i(TAG, "undo($pkg): bundle is user-owned — opted out, kept bundle")
                }

                // 3. Dismiss the notification.
                notifier.cancel(pkg)
            } catch (t: Throwable) {
                Log.w(TAG, "undo($pkg): failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "io.github.mayusi.calibratesoc.action.AUTO_CONFIG_UNDO"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
