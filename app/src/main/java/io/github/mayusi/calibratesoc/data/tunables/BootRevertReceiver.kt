package io.github.mayusi.calibratesoc.data.tunables

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.MainActivity
import io.github.mayusi.calibratesoc.R
import io.github.mayusi.calibratesoc.data.boot.BootApplyMode
import io.github.mayusi.calibratesoc.data.boot.resolveBootApplyMode
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BootRevertReceiver"

/**
 * On BOOT_COMPLETED we:
 *   1. Replay the snapshot journal in reverse to undo everything the app
 *      wrote during the last session (the safety default — nothing we changed
 *      persists across reboot unless the user opts into "apply at boot"
 *      per-profile).
 *   2. For each profile with [UserProfile.applyOnBoot]=true, decide what
 *      the strongest *honest* behaviour is via [resolveBootApplyMode]:
 *
 *      AUTO      → re-apply the tune immediately in the background through
 *                  [ProfileApplier] + [PresetSafetyGate].  Wrong-device
 *                  profiles are still blocked.  Posts a quiet success notification.
 *
 *      REMINDER  → post a "Tap to re-apply <name>" notification that deep-
 *                  links into MainActivity.  Used when only the AYN_SETTINGS
 *                  tier is available (Settings.System keys that need a live
 *                  app context).  Never silently fails.
 *
 *      UNSUPPORTED → no write path available at boot (monitoring-only device).
 *                  Does nothing — posting a useless reminder would be dishonest.
 *
 * Separate from [BootScriptReminderReceiver] which handles the AYN "run
 * script as Root" reminder flow for the no-root persistent path.
 *
 * The receiver is registered in AndroidManifest.xml with
 * RECEIVE_BOOT_COMPLETED. It uses goAsync() because the revert + re-apply
 * can take several seconds — long enough to exceed the synchronous broadcast
 * budget.
 *
 * Hilt-injected via @AndroidEntryPoint, but the heavy lifting runs on a
 * detached scope because the BroadcastReceiver lifecycle ends as soon
 * as we return from finish().
 */
@AndroidEntryPoint
class BootRevertReceiver : BroadcastReceiver() {

    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var tunableWriter: TunableWriter
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var profileApplier: ProfileApplier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val report = capabilityProbe.refresh()

                // Step 1: Revert first.  Even when a profile is marked
                // applyOnBoot, the revert step is harmless — most sysfs
                // values don't survive reboot anyway (the journal entry
                // matches the current sysfs value, so revert either writes
                // the same value back or is a trivial no-op).
                val revertSummary = tunableWriter.revertAll(report)
                if (revertSummary.failed == 0) {
                    Log.i(TAG, "Boot revert complete: ${revertSummary.ok}/${revertSummary.totalEntries} entries reverted successfully.")
                } else {
                    Log.w(
                        TAG,
                        "Boot revert partial: ${revertSummary.ok} succeeded, " +
                            "${revertSummary.failed} FAILED of ${revertSummary.totalEntries} total entries. " +
                            "Journal retained for next boot retry.",
                    )
                }

                // Step 2: Determine the strongest honest write tier.
                val mode = resolveBootApplyMode(report)
                Log.i(TAG, "Boot-apply mode: $mode (privilege=${report.privilege}, pserverSysfsLive=${report.pserverSysfsLive}, sysfsDirectlyWritable=${report.sysfsDirectlyWritable})")

                val applyAtBoot = profileRepository.snapshot().profiles.filter { it.applyOnBoot }
                if (applyAtBoot.isEmpty()) return@launch

                when (mode) {
                    BootApplyMode.AUTO -> {
                        // Re-apply each profile in the background.  ProfileApplier +
                        // PresetSafetyGate ensure wrong-device profiles are still blocked.
                        val applied = mutableListOf<String>()
                        val failed = mutableListOf<String>()
                        for (profile in applyAtBoot) {
                            val results = profileApplier.apply(
                                profile.toPreset(),
                                report,
                                reason = "boot re-apply: ${profile.name}",
                            )
                            val allOk = results.isNotEmpty() && results.none { it is WriteResult.Rejected }
                            if (allOk) {
                                applied.add(profile.name)
                                Log.i(TAG, "Boot re-applied profile '${profile.name}' (${results.size} tunables)")
                            } else {
                                failed.add(profile.name)
                                val rejections = results.filterIsInstance<WriteResult.Rejected>()
                                Log.w(TAG, "Boot re-apply failed for '${profile.name}': ${rejections.map { it.message }}")
                            }
                        }
                        if (applied.isNotEmpty()) {
                            postAppliedNotification(context, applied)
                        }
                    }

                    BootApplyMode.REMINDER -> {
                        // Cannot write autonomously — the write tier requires the
                        // app to be open.  Post one notification per profile with
                        // a deep-link into MainActivity so the user can re-apply
                        // with a single tap.
                        for ((index, profile) in applyAtBoot.withIndex()) {
                            postReminderNotification(context, profile.name, NOTIF_ID_REMINDER_BASE + index)
                            Log.i(TAG, "Boot-apply reminder posted for '${profile.name}'")
                        }
                    }

                    BootApplyMode.UNSUPPORTED -> {
                        // No write path available.  Silently skip — posting a
                        // notification here would be misleading (the user still
                        // can't persist the tune without root/Shizuku/unlock).
                        Log.d(TAG, "Boot-apply: UNSUPPORTED tier — skipping ${applyAtBoot.size} profile(s)")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun ensureChannel(context: Context): NotificationManager? {
        val nm = context.getSystemService<NotificationManager>() ?: return null
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Boot tune",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notifies when a tune is auto-applied or needs re-applying after boot"
            },
        )
        return nm
    }

    /**
     * Quiet "Applied <name> on boot" notification — LOW importance so it
     * doesn't make a sound.  Intended as a confirmation, not an alert.
     */
    private fun postAppliedNotification(context: Context, names: List<String>) {
        val nm = ensureChannel(context) ?: return
        val joined = names.joinToString(", ")
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Tune applied on boot")
            .setContentText("Applied: $joined")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Applied on boot: $joined"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { nm.notify(NOTIF_ID_APPLIED, notif) }
    }

    /**
     * "Tap to re-apply <name>" reminder notification — DEFAULT importance so
     * it gets the user's attention.  Tapping opens MainActivity so the apply
     * flow can run.
     */
    private fun postReminderNotification(context: Context, profileName: String, notifId: Int) {
        val nm = ensureChannel(context) ?: return
        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context,
            notifId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Tap to re-apply your tune")
            .setContentText("\"$profileName\" was not applied automatically — tap to open the app.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "\"$profileName\" requires the app to be open to re-apply. " +
                        "Tap to launch Calibrate SoC and re-apply.",
                ),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { nm.notify(notifId, notif) }
    }

    private companion object {
        const val CHANNEL_ID = "calibratesoc.boot_tune"
        const val NOTIF_ID_APPLIED = 7374
        const val NOTIF_ID_REMINDER_BASE = 7380 // 7380, 7381, 7382 … one per profile
    }
}
