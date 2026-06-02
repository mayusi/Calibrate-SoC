package io.github.mayusi.calibratesoc.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

/**
 * Small DataStore-backed preference bag. Currently:
 *   - oc_acknowledged: first-write risk modal has been dismissed
 *   - root_mode_enabled: user opted IN to Magisk/KernelSU privilege tier.
 *     OFF by default so casual users never see a root prompt; the AYN
 *     handheld can do everything its stock UI does via the
 *     AYN_SETTINGS tier without root.
 */
@Singleton
class UserPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val ocAcknowledged: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[OC_ACK_KEY] ?: false
    }

    val rootModeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ROOT_MODE_KEY] ?: false
    }

    /** True once the user has finished (or explicitly skipped) the
     *  first-launch onboarding wizard. Drives whether MainActivity
     *  routes to the wizard or the main app. */
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_KEY] ?: false
    }

    /** Master switch for experimental features. Default OFF. When ON,
     *  unlocks the HUD ± steppers and other features that can leave
     *  the device in a bad state if the user doesn't understand what
     *  they're doing. Reaching the ON state requires a typed-confirm
     *  in Settings. */
    val experimentalEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[EXPERIMENTAL_KEY] ?: false
    }

    suspend fun setOcAcknowledged(value: Boolean) {
        context.dataStore.edit { it[OC_ACK_KEY] = value }
    }

    suspend fun setRootModeEnabled(value: Boolean) {
        context.dataStore.edit { it[ROOT_MODE_KEY] = value }
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[ONBOARDING_KEY] = value }
    }

    suspend fun setExperimentalEnabled(value: Boolean) {
        context.dataStore.edit { it[EXPERIMENTAL_KEY] = value }
    }

    /** Sync read for the capability-probe path which already runs on
     *  Dispatchers.IO. runBlocking is acceptable there because nothing
     *  upstream is on the main thread. Returns false on any error. */
    fun rootModeEnabledBlocking(): Boolean = runCatching {
        runBlocking { context.dataStore.data.first()[ROOT_MODE_KEY] ?: false }
    }.getOrDefault(false)

    private companion object {
        val OC_ACK_KEY = booleanPreferencesKey("oc_acknowledged")
        val ROOT_MODE_KEY = booleanPreferencesKey("root_mode_enabled")
        val ONBOARDING_KEY = booleanPreferencesKey("onboarding_complete")
        val EXPERIMENTAL_KEY = booleanPreferencesKey("experimental_enabled")
    }
}
