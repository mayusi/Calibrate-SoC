package io.github.mayusi.calibratesoc.data.profiles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the quiet, HONEST notification that discloses Calibrate auto-configured a
 * recognised game — and offers a one-tap Undo that also opts the game out of any
 * future auto-config.
 *
 * ## Honesty contract (this notification is the user's only signal that the app
 *    acted on its own — it MUST be truthful and actionable)
 *  - The title says the app did this AUTOMATICALLY ("Calibrate set up automatic
 *    tuning for <App>").
 *  - The body states it is a sensible STARTING default from the game's type — it
 *    does NOT claim the tune is optimal — and tells the user exactly how to stop
 *    it (the Undo action / the Settings toggle).
 *  - The Undo action both removes the auto-created bundle AND remembers the
 *    opt-out, so one tap fully reverses the action and prevents re-creation.
 *
 * ## Channel
 * Its own LOW-importance channel so it is quiet (no sound/peek) — this is an FYI,
 * not an alert. Distinct from the temperature-alert and boot-reminder channels.
 *
 * Pure-ish: the only side effects are creating the channel and posting/cancelling
 * a notification. The Undo work happens in [AutoConfigUndoReceiver].
 */
@Singleton
class AutoConfigNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager: NotificationManager? =
        context.getSystemService()

    /**
     * Post the auto-config notification for [packageName] with the human-readable
     * [appLabel]. The notification id is derived from the package so each game's
     * notification is independent (a second game's notification does not replace
     * the first's), and so [cancel] can target exactly this one.
     */
    fun notifyAutoConfigured(packageName: String, appLabel: String) {
        val nm = notificationManager ?: return
        ensureChannel(nm)

        val undoIntent = Intent(context, AutoConfigUndoReceiver::class.java).apply {
            action = AutoConfigUndoReceiver.ACTION_UNDO
            // The exact package the user is undoing — the receiver removes its
            // auto-created bundle and records the opt-out.
            putExtra(AutoConfigUndoReceiver.EXTRA_PACKAGE, packageName)
            putExtra(AutoConfigUndoReceiver.EXTRA_NOTIF_ID, notificationIdFor(packageName))
        }
        val undoPending = PendingIntent.getBroadcast(
            context,
            // Per-package request code so each game's Undo PendingIntent is distinct
            // and FLAG_UPDATE_CURRENT refreshes the right extras.
            packageName.hashCode(),
            undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )

        val title = "Calibrate set up automatic tuning for $appLabel"
        val body =
            "Calibrate recognised $appLabel and applied a sensible starting tune " +
                "for it automatically — you didn't have to do anything. This is a " +
                "starting point based on the game's type, not a guaranteed-best " +
                "setup. Tap Undo to remove it and stop auto-tuning this game, or " +
                "turn off \"Auto-configure known games\" in Settings."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            // Dismissible (swipe) AND explicitly undoable via the action.
            .addAction(0, "Undo / don't auto-tune this", undoPending)
            .build()

        runCatching { nm.notify(notificationIdFor(packageName), notification) }
    }

    /** Cancel this package's auto-config notification (called from the Undo path). */
    fun cancel(packageName: String) {
        notificationManager?.cancel(notificationIdFor(packageName))
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Automatic game tuning",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description =
                        "Tells you when Calibrate automatically sets up tuning for a " +
                            "recognised game, and lets you undo it."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        /** Distinct LOW-importance channel for the auto-config FYI. */
        const val CHANNEL_ID = "calibratesoc.auto_config"

        /** Base id; the per-package id is this + a stable package hash. */
        private const val NOTIF_ID_BASE = 8100

        /** Stable, per-package notification id so games don't clobber each other. */
        fun notificationIdFor(packageName: String): Int =
            NOTIF_ID_BASE + (packageName.hashCode() and 0x0000FFFF)

        /**
         * FLAG_IMMUTABLE is required on API 31+ for PendingIntents we don't mutate.
         * Returns the flag on S+ and 0 below it (the flag did not exist pre-23 and
         * is not required pre-31).
         */
        private fun pendingIntentImmutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
