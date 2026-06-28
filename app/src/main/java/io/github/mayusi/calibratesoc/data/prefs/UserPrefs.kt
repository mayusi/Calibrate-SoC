package io.github.mayusi.calibratesoc.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 *     OFF by default so casual users never see a root prompt; a vendor
 *     handheld can do everything its stock UI does via the
 *     VENDOR_SETTINGS tier without root.
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

    // ── Auto-update check ─────────────────────────────────────────────────────

    /** Master switch for the automatic daily update check. Default TRUE —
     *  just a network check, never an auto-download or auto-install. */
    val autoUpdateCheckEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_UPDATE_CHECK_ENABLED_KEY] ?: true
    }

    /** Epoch-ms of the last successful auto-check (used to throttle to once/day).
     *  0 means never checked. */
    val lastUpdateCheckMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[LAST_UPDATE_CHECK_MS_KEY] ?: 0L
    }

    /** Epoch-ms before which the "update available" banner should be suppressed
     *  (i.e. user tapped "Later" — snooze for 7 days). Default 0 (no snooze). */
    val updateRemindAfterMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[UPDATE_REMIND_AFTER_MS_KEY] ?: 0L
    }

    /**
     * True when the user confirmed the "scary skip" dialog on an applicable
     * (AYN/vendor-runner) device during the advanced-unlock wizard. When true
     * and the user tries to use a gated feature (AutoTDP, HUD ± buttons, live
     * tuning), the main app can re-surface the advanced setup prompt rather
     * than silently failing.
     *
     * Default false (not skipped). Reset to false when the user completes the
     * advanced setup successfully.
     */
    val advancedSetupSkipped: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ADVANCED_SETUP_SKIPPED_KEY] ?: false
    }

    // ── AutoTDP: idle/charge trigger ──────────────────────────────────────────

    /**
     * Master opt-in for the idle/charge auto-downclock trigger (Component 7).
     * Default OFF so the user never gets an uninvited downclock.
     *
     * When ON, [IdleChargeTrigger] emits an EFFICIENCY profile request
     * whenever the screen turns off or the device is plugged in to charge,
     * and emits null (restore) on screen-on + unplug.
     */
    val idleChargeTriggerEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[IDLE_CHARGE_TRIGGER_ENABLED_KEY] ?: false
    }

    /** The release tag the user last dismissed ("don't nag me about this version").
     *  Null means never dismissed. Banner re-appears when a newer tag arrives. */
    val dismissedUpdateTag: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[DISMISSED_UPDATE_TAG_KEY]
    }

    // ── Temperature alerts ────────────────────────────────────────────────────

    /** Master switch for temperature alerts. Default OFF.
     *  When ON, the app notifies the user when any CPU/GPU/battery temp
     *  crosses [tempAlertThresholdC] and optionally auto-switches to a
     *  cooler profile. Stored in °C regardless of [tempUnit]. */
    val tempAlertsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TEMP_ALERTS_ENABLED_KEY] ?: false
    }

    /** Alert fires when the hottest relevant sensor (max of cpu/gpu zone
     *  temps and battery temp) crosses this value in °C. Default 80°C.
     *  Always stored as integer °C — convert to °F for display only. */
    val tempAlertThresholdC: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TEMP_ALERT_THRESHOLD_C_KEY] ?: DEFAULT_ALERT_THRESHOLD_C
    }

    /** Profile ID to auto-apply when the threshold is crossed. Null means
     *  notify only (no profile switch). The profile must exist in
     *  [ProfileRepository] — callers should validate before displaying. */
    val tempAlertAutoProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[TEMP_ALERT_AUTO_PROFILE_ID_KEY]
    }

    // ── Auto-configure known games (the "app just handles everything" headline) ─

    /**
     * Master switch for auto-configuring KNOWN games on first launch. Default
     * **TRUE** — this is the headline zero-tap feature — but power users can turn
     * it off so the app never auto-creates a per-app tune for them.
     *
     * When ON and a recognised game ([io.github.mayusi.calibratesoc.data.gameaware.KnownGames])
     * comes to the foreground with NO existing bundle and NOT in
     * [autoConfigOptOut], [ForegroundAppWatcher] auto-creates a conservative
     * starting bundle and posts a dismissible, undoable notification.
     *
     * When OFF, the auto-create path is skipped entirely — existing user bundles
     * and previously auto-created bundles still apply as normal; only NEW
     * auto-creation stops.
     */
    val autoConfigKnownGamesEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_CONFIG_ENABLED_KEY] ?: true
    }

    /**
     * The set of package names the user has explicitly opted OUT of auto-config
     * (via the notification's "Don't auto-tune this app" action). The
     * auto-create path never re-creates a bundle for a package in this set, so a
     * single Undo permanently stops the nag for that game.
     *
     * Empty by default. Persisted as a string-set; survives app restarts and
     * reboots. Independent of the global [autoConfigKnownGamesEnabled] toggle —
     * a package can be opted out while the feature stays on for everything else.
     */
    val autoConfigOptOut: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[AUTO_CONFIG_OPT_OUT_KEY] ?: emptySet()
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

    suspend fun setAutoUpdateCheckEnabled(value: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_CHECK_ENABLED_KEY] = value }
    }

    suspend fun setLastUpdateCheckMs(value: Long) {
        context.dataStore.edit { it[LAST_UPDATE_CHECK_MS_KEY] = value }
    }

    suspend fun setUpdateRemindAfterMs(value: Long) {
        context.dataStore.edit { it[UPDATE_REMIND_AFTER_MS_KEY] = value }
    }

    /** Pass null to clear the dismissed tag (e.g. after a fresh install or
     *  when a newer release supersedes the dismissed one). */
    suspend fun setDismissedUpdateTag(tag: String?) {
        context.dataStore.edit { prefs ->
            if (tag == null) prefs.remove(DISMISSED_UPDATE_TAG_KEY)
            else prefs[DISMISSED_UPDATE_TAG_KEY] = tag
        }
    }

    suspend fun setTempAlertsEnabled(value: Boolean) {
        context.dataStore.edit { it[TEMP_ALERTS_ENABLED_KEY] = value }
    }

    suspend fun setTempAlertThresholdC(value: Int) {
        context.dataStore.edit { it[TEMP_ALERT_THRESHOLD_C_KEY] = value }
    }

    suspend fun setIdleChargeTriggerEnabled(value: Boolean) {
        context.dataStore.edit { it[IDLE_CHARGE_TRIGGER_ENABLED_KEY] = value }
    }

    /** Pass null to clear the auto-profile (notify-only mode). */
    suspend fun setTempAlertAutoProfileId(profileId: String?) {
        context.dataStore.edit { prefs ->
            if (profileId == null) prefs.remove(TEMP_ALERT_AUTO_PROFILE_ID_KEY)
            else prefs[TEMP_ALERT_AUTO_PROFILE_ID_KEY] = profileId
        }
    }

    // ── Auto-configure known games ─────────────────────────────────────────────

    suspend fun setAutoConfigKnownGamesEnabled(value: Boolean) {
        context.dataStore.edit { it[AUTO_CONFIG_ENABLED_KEY] = value }
    }

    /**
     * Add [packageName] to the auto-config opt-out set (idempotent). Called when
     * the user taps "Don't auto-tune this app" on the auto-config notification.
     * Reads the current set and writes back the union so a concurrent edit is not
     * clobbered (DataStore's [edit] runs the transform under its own lock).
     */
    suspend fun addAutoConfigOptOut(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[AUTO_CONFIG_OPT_OUT_KEY] ?: emptySet()
            prefs[AUTO_CONFIG_OPT_OUT_KEY] = current + packageName
        }
    }

    /**
     * Remove [packageName] from the opt-out set — used if the UI ever offers a
     * "re-enable auto-config for this app" affordance. Safe no-op when absent.
     */
    suspend fun removeAutoConfigOptOut(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[AUTO_CONFIG_OPT_OUT_KEY] ?: return@edit
            prefs[AUTO_CONFIG_OPT_OUT_KEY] = current - packageName
        }
    }

    /**
     * Persist whether the user confirmed the scary "Skip — read-only" path on
     * an applicable (AYN/vendor-runner) device. Pass true when the user
     * confirms the full-screen ScarySkipDialog. Pass false when they complete
     * the advanced setup (so re-surface logic knows they did it properly).
     */
    suspend fun setAdvancedSetupSkipped(value: Boolean) {
        context.dataStore.edit { it[ADVANCED_SETUP_SKIPPED_KEY] = value }
    }

    /** Sync read for the capability-probe path which already runs on
     *  Dispatchers.IO. runBlocking is acceptable there because nothing
     *  upstream is on the main thread. Returns false on any error. */
    fun rootModeEnabledBlocking(): Boolean = runCatching {
        runBlocking { context.dataStore.data.first()[ROOT_MODE_KEY] ?: false }
    }.getOrDefault(false)

    private companion object {
        val OC_ACK_KEY                   = booleanPreferencesKey("oc_acknowledged")
        val ROOT_MODE_KEY                = booleanPreferencesKey("root_mode_enabled")
        val ONBOARDING_KEY               = booleanPreferencesKey("onboarding_complete")
        val EXPERIMENTAL_KEY             = booleanPreferencesKey("experimental_enabled")
        val ACCENT_COLOR_KEY             = stringPreferencesKey("accent_color")
        val CLOCK_UNIT_KEY               = stringPreferencesKey("clock_unit")
        val TEMP_UNIT_KEY                = stringPreferencesKey("temp_unit")
        val LAST_SEEN_VERSION_KEY        = intPreferencesKey("last_seen_version")
        val TEMP_ALERTS_ENABLED_KEY      = booleanPreferencesKey("temp_alerts_enabled")
        val TEMP_ALERT_THRESHOLD_C_KEY   = intPreferencesKey("temp_alert_threshold_c")
        val TEMP_ALERT_AUTO_PROFILE_ID_KEY = stringPreferencesKey("temp_alert_auto_profile_id")
        val AUTO_UPDATE_CHECK_ENABLED_KEY      = booleanPreferencesKey("auto_update_check_enabled")
        val LAST_UPDATE_CHECK_MS_KEY           = longPreferencesKey("last_update_check_ms")
        val UPDATE_REMIND_AFTER_MS_KEY         = longPreferencesKey("update_remind_after_ms")
        val DISMISSED_UPDATE_TAG_KEY           = stringPreferencesKey("dismissed_update_tag")
        // ── AutoTDP keys ──────────────────────────────────────────────────────
        val IDLE_CHARGE_TRIGGER_ENABLED_KEY    = booleanPreferencesKey("autotdp_idle_charge_trigger_enabled")
        // ── Advanced setup gate ────────────────────────────────────────────────
        val ADVANCED_SETUP_SKIPPED_KEY         = booleanPreferencesKey("advanced_setup_skipped")
        // ── Auto-configure known games ─────────────────────────────────────────
        val AUTO_CONFIG_ENABLED_KEY            = booleanPreferencesKey("auto_config_known_games_enabled")
        val AUTO_CONFIG_OPT_OUT_KEY            = stringSetPreferencesKey("auto_config_opt_out_packages")

        const val DEFAULT_ALERT_THRESHOLD_C = 80
    }
}
