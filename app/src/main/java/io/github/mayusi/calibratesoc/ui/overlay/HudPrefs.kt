package io.github.mayusi.calibratesoc.ui.overlay

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hudDataStore by preferencesDataStore(name = "hud_overlay")

/**
 * Persisted overlay state: profile choice + last drag position so the
 * window comes back where the user left it after a reboot or QS tile
 * toggle. Position survives process death — the HUD is supposed to feel
 * like a permanent sticky note, not something that resets every time.
 *
 * Added in Direction-C rework:
 *  - [hudSizeIndex]  — 0=small, 1=medium, 2=large width preset
 *  - [hudOpacity]    — 0.0..1.0 float, default 0.94
 */
@Singleton
class HudPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val profile: Flow<HudProfile> = context.hudDataStore.data.map { prefs ->
        prefs[KEY_PROFILE]?.let { runCatching { HudProfile.valueOf(it) }.getOrNull() }
            ?: HudProfile.COMPACT
    }

    // First-run placement is INSET from the screen corner (not flush) so the
    // premium boxed bar reads as an intentional, floating HUD rather than jammed
    // into the top-left edge. clampToScreen() still keeps it on-screen if the bar
    // is wider than expected. Mirror this default in [OverlayService] so the very
    // first frame (before this async read lands) also spawns inset.
    val xDp: Flow<Int> = context.hudDataStore.data.map { it[KEY_X_DP] ?: DEFAULT_X_DP }
    val yDp: Flow<Int> = context.hudDataStore.data.map { it[KEY_Y_DP] ?: DEFAULT_Y_DP }
    val running: Flow<Boolean> = context.hudDataStore.data.map { it[KEY_RUNNING] ?: false }
    val stepMhz: Flow<Int> = context.hudDataStore.data.map { it[KEY_STEP_MHZ] ?: 200 }
    val enabledPolicies: Flow<Set<Int>> = context.hudDataStore.data.map {
        it[KEY_ENABLED_POLICIES]?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()
    }

    /** 0 = small (300dp), 1 = medium (330dp), 2 = large (372dp). Default 1. See HudDisplayUtils.hudWidthDp(). */
    val hudSizeIndex: Flow<Int> = context.hudDataStore.data.map { (it[KEY_HUD_SIZE_INDEX] ?: 1).coerceIn(0, 2) }

    /** Overlay alpha, 0.0..1.0. Default 0.94. */
    val hudOpacity: Flow<Float> = context.hudDataStore.data.map { (it[KEY_HUD_OPACITY] ?: 94).coerceIn(10, 100) / 100f }

    suspend fun setStepMhz(step: Int) {
        context.hudDataStore.edit { it[KEY_STEP_MHZ] = step }
    }

    suspend fun setEnabledPolicies(set: Set<Int>) {
        context.hudDataStore.edit {
            it[KEY_ENABLED_POLICIES] = set.map { p -> p.toString() }.toSet()
        }
    }

    suspend fun setProfile(p: HudProfile) {
        context.hudDataStore.edit { it[KEY_PROFILE] = p.name }
    }

    suspend fun setPosition(xDp: Int, yDp: Int) {
        context.hudDataStore.edit {
            it[KEY_X_DP] = xDp
            it[KEY_Y_DP] = yDp
        }
    }

    suspend fun setRunning(r: Boolean) {
        context.hudDataStore.edit { it[KEY_RUNNING] = r }
    }

    /** [index] must be 0, 1, or 2. */
    suspend fun setHudSizeIndex(index: Int) {
        context.hudDataStore.edit { it[KEY_HUD_SIZE_INDEX] = index.coerceIn(0, 2) }
    }

    /** [opacity] in 0.0..1.0; clamped and stored as 0..100 int to avoid float drift. */
    suspend fun setHudOpacity(opacity: Float) {
        context.hudDataStore.edit { it[KEY_HUD_OPACITY] = (opacity * 100).toInt().coerceIn(10, 100) }
    }

    companion object {
        /** First-run HUD inset from the top-left corner (dp). Shared with
         *  [OverlayService] so the first frame spawns at the same inset position. */
        const val DEFAULT_X_DP = 28
        const val DEFAULT_Y_DP = 80

        private val KEY_PROFILE = stringPreferencesKey("profile")
        private val KEY_X_DP = intPreferencesKey("x_dp")
        private val KEY_Y_DP = intPreferencesKey("y_dp")
        private val KEY_RUNNING = booleanPreferencesKey("running")
        private val KEY_STEP_MHZ = intPreferencesKey("step_mhz")
        private val KEY_ENABLED_POLICIES = androidx.datastore.preferences.core
            .stringSetPreferencesKey("enabled_policies")
        private val KEY_HUD_SIZE_INDEX = intPreferencesKey("hud_size_index")
        private val KEY_HUD_OPACITY = intPreferencesKey("hud_opacity_pct")
    }
}
