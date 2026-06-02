package io.github.mayusi.calibratesoc.data.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.refreshRateStore by preferencesDataStore(name = "refresh_rate")

/**
 * Screen refresh-rate controller. Enumerates the modes the panel
 * exposes (typically 60 / 90 / 120 / sometimes 144 Hz on handhelds)
 * and lets us pin a chosen one via the Activity's window
 * `preferredDisplayModeId`.
 *
 * Why this works without root: `preferredDisplayModeId` is a public
 * window-attribute the system honors as long as the mode is in the
 * Display's supported set. Some OEMs override based on power-save
 * settings; the user can also unblock those via the system settings
 * "Smooth Display" toggle.
 *
 * Storage: pinned mode ID survives reboots. On boot the saved id may
 * not match the post-boot enumeration (modes can be reshuffled). In
 * that case we fall back to matching by refresh-rate Hz.
 */
@Singleton
class RefreshRateController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Enumerate every mode the panel reports right now. Activity
     *  context preferred — the Application context's DisplayManager
     *  sometimes reports an empty list on Android 14+ when called
     *  before any Activity has attached a window. */
    fun supportedModes(activityContext: Context? = null): List<Mode> {
        val ctx = activityContext ?: context
        // Activity preferred: windowManager.defaultDisplay returns the
        // display the window is bound to, with the panel's true mode
        // list. Falls back to DISPLAY_SERVICE for non-activity callers.
        val display: Display? = when (ctx) {
            is android.app.Activity -> ctx.windowManager.defaultDisplay
            else -> {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                dm?.getDisplay(Display.DEFAULT_DISPLAY)
            }
        }
        if (display == null) return emptyList()
        return display.supportedModes
            .map { Mode(id = it.modeId, hz = it.refreshRate, w = it.physicalWidth, h = it.physicalHeight) }
            .distinctBy { it.hz to it.w to it.h }
            .sortedBy { it.hz }
    }

    /** Highest-Hz mode the panel reports. Useful for "max it out" calls. */
    fun highestHzMode(): Mode? = supportedModes().maxByOrNull { it.hz }

    val preferredHz: Flow<Float?> = context.refreshRateStore.data.map { prefs ->
        val saved = prefs[KEY_HZ_X100] ?: return@map null
        saved / 100f
    }

    suspend fun setPreferredHz(hz: Float?) {
        context.refreshRateStore.edit { prefs ->
            if (hz == null) prefs.remove(KEY_HZ_X100)
            else prefs[KEY_HZ_X100] = (hz * 100).toInt()
        }
    }

    /** Resolve the saved preference into a concrete mode id for this
     *  display's current supported set, or null if none matches. */
    fun resolveModeIdForHz(hz: Float?): Int? {
        if (hz == null) return null
        val modes = supportedModes()
        if (modes.isEmpty()) return null
        // Find the mode whose Hz is closest to the saved value AND whose
        // resolution matches the current native (don't downscale).
        val native = modes.maxByOrNull { it.w * it.h }
        val matchRes = modes.filter { it.w == native?.w && it.h == native.h }
        return (if (matchRes.isNotEmpty()) matchRes else modes)
            .minByOrNull { kotlin.math.abs(it.hz - hz) }?.id
    }

    data class Mode(val id: Int, val hz: Float, val w: Int, val h: Int) {
        val displayLabel: String = "${hz.toInt()} Hz · ${w}×${h}"
    }

    private companion object {
        // Stored as Hz*100 (int) so 90.005 Hz survives round-tripping.
        val KEY_HZ_X100 = intPreferencesKey("hz_x100")
    }
}
