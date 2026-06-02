package io.github.mayusi.calibratesoc.ui.overlay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.MainActivity
import javax.inject.Inject

/**
 * Quick Settings tile to toggle the floating HUD without opening
 * the app. The user has to add this to their tile tray once via
 * Edit tiles; Android won't auto-add system tiles to the active
 * set.
 *
 * Tap behaviour:
 *  - HUD off + overlay perm granted → start the service
 *  - HUD on                          → stop the service
 *  - Overlay perm missing            → open Settings → Display
 *                                       over other apps, surfaced
 *                                       via [startActivityAndCollapse]
 *                                       so the QS pane closes
 */
@AndroidEntryPoint
class HudQuickTileService : TileService() {

    @Inject lateinit var hudPrefs: HudPrefs

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            // No overlay permission yet. Bounce the user to grant it.
            // Use the app's own MainActivity rather than the system
            // Settings page so the user has somewhere to come back to.
            val launch = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Android 14 (UPSIDE_DOWN_CAKE) replaced the Intent overload
            // of startActivityAndCollapse with a PendingIntent variant.
            // Older form still works on <34 — branch.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch)
            }
            return
        }

        val running = qsTile?.state == Tile.STATE_ACTIVE
        if (running) {
            OverlayService.stop(this)
        } else {
            OverlayService.start(this)
        }
        // Don't optimistically flip the tile — wait for the prefs flow
        // to write back. onStartListening will re-run when the panel
        // is next opened and pick up the persisted state.
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val running = Settings.canDrawOverlays(this) && isServiceRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) "HUD on" else "HUD"
        tile.contentDescription = if (running) {
            "Floating performance HUD is on. Tap to hide."
        } else {
            "Floating performance HUD is off. Tap to show."
        }
        tile.updateTile()
    }

    /**
     * Best-effort check: ask the activity manager for matching services.
     * Deprecated for cross-app use but reliable for in-app introspection.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val targetName = OverlayService::class.java.name
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == targetName }
    }
}
