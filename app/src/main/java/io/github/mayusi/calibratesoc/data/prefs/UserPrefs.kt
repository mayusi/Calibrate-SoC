package io.github.mayusi.calibratesoc.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.ui.theme.AccentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

/**
 * Small DataStore-backed preference bag. Currently:
 *   - oc_acknowledged:       first-write risk modal has been dismissed
 *   - root_mode_enabled:     user opted IN to Magisk/KernelSU privilege tier.
 *     OFF by default so casual users never see a root prompt; the AYN
 *     handheld can do everything its stock UI does via the
 *     AYN_SETTINGS tier without root.
 *   - accent_color:          user-chosen AccentColor enum name (default BLUE)
 *   - clock_unit:            MHz vs GHz display preference (default MHZ)
 *   - temp_unit:             °C vs °F display preference (default CELSIUS)
 *   - last_seen_version:     last versionCode shown in "What's New" banner
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

    // ── Accent colour ────────────────────────────────────────────────────────

    /** User-selected accent colour. Defaults to [AccentColor.BLUE] which
     *  is visually identical to the original hand-tuned palette. */
    val accentColor: Flow<AccentColor> = context.dataStore.data.map { prefs ->
        val stored = prefs[ACCENT_COLOR_KEY]
        if (stored != null) runCatching { AccentColor.valueOf(stored) }.getOrDefault(AccentColor.BLUE)
        else AccentColor.BLUE
    }

    // ── Units ────────────────────────────────────────────────────────────────

    /** Clock display preference: MHz or GHz. Default MHz. */
    val clockUnit: Flow<ClockUnit> = context.dataStore.data.map { prefs ->
        val stored = prefs[CLOCK_UNIT_KEY]
        if (stored != null) runCatching { ClockUnit.valueOf(stored) }.getOrDefault(ClockUnit.MHZ)
        else ClockUnit.MHZ
    }

    /** Temperature display preference: Celsius or Fahrenheit. Default Celsius. */
    val tempUnit: Flow<TempUnit> = context.dataStore.data.map { prefs ->
        val stored = prefs[TEMP_UNIT_KEY]
        if (stored != null) runCatching { TempUnit.valueOf(stored) }.getOrDefault(TempUnit.CELSIUS)
        else TempUnit.CELSIUS
    }

    // ── What's New / update tracking ─────────────────────────────────────────

    /** The last versionCode the user has seen the "What's New" banner for.
     *  0 means never shown (fresh install). When BuildConfig.VERSION_CODE
     *  is greater than this, the banner is shown once and this value is
     *  updated on dismiss. */
    val lastSeenVersion: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LAST_SEEN_VERSION_KEY] ?: 0
    }

    // ── Setters ──────────────────────────────────────────────────────────────

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

    suspend fun setAccentColor(accent: AccentColor) {
        context.dataStore.edit { it[ACCENT_COLOR_KEY] = accent.name }
    }

    suspend fun setClockUnit(unit: ClockUnit) {
        context.dataStore.edit { it[CLOCK_UNIT_KEY] = unit.name }
    }

    suspend fun setTempUnit(unit: TempUnit) {
        context.dataStore.edit { it[TEMP_UNIT_KEY] = unit.name }
    }

    suspend fun setLastSeenVersion(versionCode: Int) {
        context.dataStore.edit { it[LAST_SEEN_VERSION_KEY] = versionCode }
    }

    /** Sync read for the capability-probe path which already runs on
     *  Dispatchers.IO. runBlocking is acceptable there because nothing
     *  upstream is on the main thread. Returns false on any error. */
    fun rootModeEnabledBlocking(): Boolean = runCatching {
        runBlocking { context.dataStore.data.first()[ROOT_MODE_KEY] ?: false }
    }.getOrDefault(false)

    private companion object {
        val OC_ACK_KEY            = booleanPreferencesKey("oc_acknowledged")
        val ROOT_MODE_KEY         = booleanPreferencesKey("root_mode_enabled")
        val ONBOARDING_KEY        = booleanPreferencesKey("onboarding_complete")
        val EXPERIMENTAL_KEY      = booleanPreferencesKey("experimental_enabled")
        val ACCENT_COLOR_KEY      = stringPreferencesKey("accent_color")
        val CLOCK_UNIT_KEY        = stringPreferencesKey("clock_unit")
        val TEMP_UNIT_KEY         = stringPreferencesKey("temp_unit")
        val LAST_SEEN_VERSION_KEY = intPreferencesKey("last_seen_version")
    }
}
