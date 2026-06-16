package io.github.mayusi.calibratesoc.data.fancurve

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.fanCurveStore by preferencesDataStore(name = "fan_curve")

/**
 * Persists the user's fan-curve choice across app restarts.
 *
 * Stores:
 *  - [KEY_CURVE_JSON]    the active curve, in the stock `[{"a","b"}…]` JSON
 *    shape (so we never invent a second serialization format),
 *  - [KEY_PRESET_ID]     the selected built-in preset id, or absent when the
 *    user has a hand-edited custom curve,
 *  - [KEY_APPLY_ON_OPEN] whether to re-apply the saved curve when the app opens
 *    and the feature is available (lightweight boot-apply without wiring a new
 *    BootReceiver — see [FanCurveController.maybeReapplyOnOpen]).
 *  - [KEY_ALLOW_SUB_FLOOR] whether the user opted into sub-20%/0% duty points.
 *
 * Uses [FanCurveJson] for the curve value so the persisted form is exactly the
 * device wire format.
 */
@Singleton
class FanCurveStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    /** The persisted curve, or null when nothing has been saved yet. */
    val savedCurve: Flow<FanCurve?> = context.fanCurveStore.data.map { prefs ->
        prefs[KEY_CURVE_JSON]?.let { raw ->
            (FanCurveJson.parse(raw) as? FanCurveParse.Ok)?.curve
        }
    }

    /** The selected preset, or null when the saved curve is hand-edited. */
    val savedPresetId: Flow<String?> = context.fanCurveStore.data.map { it[KEY_PRESET_ID] }

    /** Whether to re-apply on app open. Defaults to false (opt-in). */
    val applyOnOpen: Flow<Boolean> = context.fanCurveStore.data.map { it[KEY_APPLY_ON_OPEN] ?: false }

    /** Whether the user opted into sub-20%/0% duty points. Defaults to false. */
    val allowSubFloor: Flow<Boolean> = context.fanCurveStore.data.map { it[KEY_ALLOW_SUB_FLOOR] ?: false }

    /** One-shot read of the saved curve (for boot/open re-apply). */
    suspend fun savedCurveNow(): FanCurve? = savedCurve.first()

    suspend fun applyOnOpenNow(): Boolean = applyOnOpen.first()

    /** Persist a built-in preset selection (stores both id and its curve JSON). */
    suspend fun savePreset(preset: FanCurvePreset) {
        context.fanCurveStore.edit { prefs ->
            prefs[KEY_PRESET_ID] = preset.id
            prefs[KEY_CURVE_JSON] = FanCurveJson.serialize(preset.curve)
        }
    }

    /** Persist a hand-edited custom curve (clears the preset id). */
    suspend fun saveCustomCurve(curve: FanCurve) {
        context.fanCurveStore.edit { prefs ->
            prefs.remove(KEY_PRESET_ID)
            prefs[KEY_CURVE_JSON] = FanCurveJson.serialize(curve)
        }
    }

    suspend fun setApplyOnOpen(enabled: Boolean) {
        context.fanCurveStore.edit { it[KEY_APPLY_ON_OPEN] = enabled }
    }

    suspend fun setAllowSubFloor(enabled: Boolean) {
        context.fanCurveStore.edit { it[KEY_ALLOW_SUB_FLOOR] = enabled }
    }

    private companion object {
        val KEY_CURVE_JSON = stringPreferencesKey("curve_json")
        val KEY_PRESET_ID = stringPreferencesKey("preset_id")
        val KEY_APPLY_ON_OPEN = booleanPreferencesKey("apply_on_open")
        val KEY_ALLOW_SUB_FLOOR = booleanPreferencesKey("allow_sub_floor")
    }
}
