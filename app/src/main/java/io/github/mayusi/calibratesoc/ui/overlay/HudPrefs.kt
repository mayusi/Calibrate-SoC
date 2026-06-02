package io.github.mayusi.calibratesoc.ui.overlay

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 */
@Singleton
class HudPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val profile: Flow<HudProfile> = context.hudDataStore.data.map { prefs ->
        prefs[KEY_PROFILE]?.let { runCatching { HudProfile.valueOf(it) }.getOrNull() }
            ?: HudProfile.COMPACT
    }

    val xDp: Flow<Int> = context.hudDataStore.data.map { it[KEY_X_DP] ?: 16 }
    val yDp: Flow<Int> = context.hudDataStore.data.map { it[KEY_Y_DP] ?: 64 }
    val running: Flow<Boolean> = context.hudDataStore.data.map { it[KEY_RUNNING] ?: false }
    val stepMhz: Flow<Int> = context.hudDataStore.data.map { it[KEY_STEP_MHZ] ?: 200 }
    val enabledPolicies: Flow<Set<Int>> = context.hudDataStore.data.map {
        it[KEY_ENABLED_POLICIES]?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()
    }

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

    private companion object {
        val KEY_PROFILE = stringPreferencesKey("profile")
        val KEY_X_DP = intPreferencesKey("x_dp")
        val KEY_Y_DP = intPreferencesKey("y_dp")
        val KEY_RUNNING = booleanPreferencesKey("running")
        val KEY_STEP_MHZ = intPreferencesKey("step_mhz")
        val KEY_ENABLED_POLICIES = androidx.datastore.preferences.core
            .stringSetPreferencesKey("enabled_policies")
    }
}
