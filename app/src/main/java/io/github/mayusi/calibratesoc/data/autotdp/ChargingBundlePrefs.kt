package io.github.mayusi.calibratesoc.data.autotdp

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

/**
 * DataStore-backed preferences for the charging auto-profile feature.
 *
 * Deliberately stored in its own DataStore ("charging_bundle") rather than the
 * shared "user_prefs" store, so this file requires NO changes to UserPrefs.kt
 * and cannot collide with sibling-agent edits to that file.
 *
 * ## Keys stored
 * - [chargingProfileEnabled]   — master opt-in toggle (default OFF, like all
 *                                auto-apply features). The user must explicitly
 *                                enable this — the app never auto-applies without
 *                                consent.
 * - [autoTdpGoalOrdinal]       — persisted ordinal of the chosen [GoalProfile].
 *                                Default: [GoalProfile.COOL_QUIET].
 * - [fanMode]                  — vendor fan-mode preset index, or the sentinel
 *                                [FAN_MODE_NONE] (-1) to mean "don't touch fan".
 *                                Default: [GoalProfile.FanPresets.QUIET] (0).
 * - [refreshRateHz]            — preferred display Hz * 100 (int) for round-trip
 *                                fidelity, or [REFRESH_RATE_NONE] (0) to mean
 *                                "don't touch refresh rate". Default: 60 Hz.
 */
@Singleton
class ChargingBundlePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ── Master enable toggle ───────────────────────────────────────────────────

    /**
     * Whether the charging auto-profile is enabled.
     * Default OFF — the user must opt in; the app never silently applies
     * charging-mode without consent.
     */
    val chargingProfileEnabled: Flow<Boolean> = context.chargingBundleStore.data.map { prefs ->
        prefs[CHARGING_PROFILE_ENABLED_KEY] ?: false
    }

    // ── Bundle configuration ───────────────────────────────────────────────────

    /**
     * The AutoTDP goal to run while charging. Persisted as the enum ordinal.
     * Falls back to [GoalProfile.COOL_QUIET] on any decode error (safe default).
     */
    val autoTdpGoal: Flow<GoalProfile> = context.chargingBundleStore.data.map { prefs ->
        val ordinal = prefs[AUTOTDP_GOAL_ORDINAL_KEY]
        if (ordinal != null) {
            GoalProfile.entries.getOrNull(ordinal) ?: GoalProfile.COOL_QUIET
        } else {
            GoalProfile.COOL_QUIET
        }
    }

    /**
     * The vendor fan-mode preset index while charging, or null to leave the fan
     * untouched. [FAN_MODE_NONE] sentinel (-1) is stored when user selects "off".
     */
    val fanMode: Flow<Int?> = context.chargingBundleStore.data.map { prefs ->
        val stored = prefs[FAN_MODE_KEY] ?: GoalProfile.FanPresets.QUIET
        if (stored == FAN_MODE_NONE) null else stored
    }

    /**
     * The preferred display Hz while charging, or null (= don't touch).
     * Stored as Hz * 100 (int) for round-trip fidelity; [REFRESH_RATE_NONE]
     * sentinel (0) maps to null.
     */
    val refreshRateHz: Flow<Float?> = context.chargingBundleStore.data.map { prefs ->
        val stored = prefs[REFRESH_RATE_HZ_X100_KEY] ?: DEFAULT_REFRESH_RATE_HZ_X100
        if (stored == REFRESH_RATE_NONE) null else stored / 100f
    }

    // ── Convenience: the full bundle as a single Flow ─────────────────────────

    /** Observe the current bundle configuration as a single [ChargingBundle]. */
    val bundle: Flow<ChargingBundle> =
        kotlinx.coroutines.flow.combine(autoTdpGoal, fanMode, refreshRateHz) { goal, fan, rr ->
            ChargingBundle(autoTdpGoal = goal, fanMode = fan, refreshRateHz = rr)
        }

    // ── Setters ──────────────────────────────────────────────────────────────

    suspend fun setChargingProfileEnabled(value: Boolean) {
        context.chargingBundleStore.edit { it[CHARGING_PROFILE_ENABLED_KEY] = value }
    }

    suspend fun setAutoTdpGoal(goal: GoalProfile) {
        context.chargingBundleStore.edit { it[AUTOTDP_GOAL_ORDINAL_KEY] = goal.ordinal }
    }

    /** Pass null to disable fan control (leave fan untouched). */
    suspend fun setFanMode(mode: Int?) {
        context.chargingBundleStore.edit { it[FAN_MODE_KEY] = mode ?: FAN_MODE_NONE }
    }

    /** Pass null to disable refresh-rate pinning (leave rate untouched). */
    suspend fun setRefreshRateHz(hz: Float?) {
        context.chargingBundleStore.edit {
            it[REFRESH_RATE_HZ_X100_KEY] = if (hz == null) REFRESH_RATE_NONE else (hz * 100).toInt()
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private companion object {
        val CHARGING_PROFILE_ENABLED_KEY    = booleanPreferencesKey("charging_profile_enabled")
        val AUTOTDP_GOAL_ORDINAL_KEY        = intPreferencesKey("charging_autotdp_goal_ordinal")
        val FAN_MODE_KEY                    = intPreferencesKey("charging_fan_mode")
        val REFRESH_RATE_HZ_X100_KEY        = intPreferencesKey("charging_refresh_rate_hz_x100")

        /** Sentinel stored when the user selects "don't touch fan". */
        const val FAN_MODE_NONE = -1

        /**
         * Sentinel stored when the user selects "don't touch refresh rate".
         * 0 is safe — no real panel reports 0 Hz.
         */
        const val REFRESH_RATE_NONE = 0

        /** Default: 60 Hz * 100 (lowest common refresh rate = minimum panel power). */
        const val DEFAULT_REFRESH_RATE_HZ_X100 = 6000
    }
}

// DataStore extension — isolated from "user_prefs" to avoid any merge collision.
private val Context.chargingBundleStore by preferencesDataStore(name = "charging_bundle")
