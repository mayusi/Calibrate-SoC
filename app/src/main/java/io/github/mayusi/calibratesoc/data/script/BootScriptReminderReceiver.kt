package io.github.mayusi.calibratesoc.data.script

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires on BOOT_COMPLETED. For each preset the user registered as
 * "persistent via reminder", posts a notification telling them to
 * open Odin Settings → Run script as Root and pick the matching .sh.
 *
 * This is the no-root persistent path. Truly automatic execution
 * needs Magisk/KernelSU (handled by the existing root-tier
 * Install at boot button).
 *
 * Separate from BootRevertReceiver because they have different
 * concerns: BootRevertReceiver reverts kernel sysfs writes that the
 * app made directly. This receiver just notifies the user to
 * trigger AYN's own script runner.
 */
@AndroidEntryPoint
class BootScriptReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var reminder: BootScriptReminder

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val names = reminder.all()
                if (names.isEmpty()) return@launch
                showReminder(context, names)
            } finally {
                pending.finish()
            }
        }
    }

    private fun showReminder(context: Context, presetNames: Set<String>) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Boot reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminds you to re-run a tune script after boot"
            },
        )
        val title = "Re-apply your tune"
        val joined = presetNames.joinToString(", ")
        val text = "Open Odin Settings → Run script as Root and pick: $joined"
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }

    private companion object {
        const val CHANNEL_ID = "calibratesoc.boot_reminder"
        const val NOTIF_ID = 7373
    }
}
