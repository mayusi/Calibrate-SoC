package io.github.mayusi.calibratesoc.ui.autotdp.adaptive

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptiveIntent
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptivePreset
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuOcTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UNIT 4 (ADAPTIVE MODE) — isolated DataStore for the Adaptive-mode UI preferences.
 *
 * Stored in its own DataStore ("adaptive_prefs") — isolated from "user_prefs",
 * "goal_params", and "charging_bundle" to avoid any merge collision with sibling
 * agents. No other prefs file needs to change.
 *
 * ## Keys persisted
 *  - [selectedPreset]          — which of the five [AdaptivePreset]s the user picked.
 *  - [customWeights]           — the four raw slider weights when in Custom mode
 *                                (null = user has not diverged from a preset, i.e. is
 *                                using the preset's weights verbatim). The VM always
 *                                delivers the normalized intent; only raw weights are
 *                                persisted so round-trips are lossless.
 *  - [gpuOcTier]               — the chosen [GpuOcTier]; BEYOND_STOCK only valid after
 *                                consent is granted.
 *  - [beyondStockConsent]      — true if the user has explicitly read + confirmed the
 *                                beyond-stock risk dialog. Gate for BEYOND_STOCK.
 *  - [beyondStockProbeVerdict] — cached string encoding the Unit 3 [GpuOcVerdict] plus
 *                                the device fingerprint so a kernel/build change
 *                                invalidates it. Unit 3 writes; this file reads only.
 *  - [adaptiveModeActive]      — whether Adaptive mode is the active mode.
 */
@Singleton
class AdaptivePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ── Selected preset ───────────────────────────────────────────────────────

    /**
     * The user's currently selected preset. Defaults to [AdaptivePreset.BALANCED].
     * Changing this does NOT automatically clear [customWeights]; the VM decides
     * whether to seed custom weights from the new preset.
     */
    val selectedPreset: Flow<AdaptivePreset> = context.adaptivePrefsStore.data.map { prefs ->
        val ordinal = prefs[SELECTED_PRESET_ORDINAL_KEY]
        if (ordinal != null) {
            AdaptivePreset.entries.getOrNull(ordinal) ?: AdaptivePreset.DEFAULT
        } else {
            AdaptivePreset.DEFAULT
        }
    }

    suspend fun setSelectedPreset(preset: AdaptivePreset) {
        context.adaptivePrefsStore.edit { it[SELECTED_PRESET_ORDINAL_KEY] = preset.ordinal }
    }

    // ── Custom intent weights (null = using preset verbatim) ──────────────────

    /**
     * The four raw slider weights for Custom mode. Null when the user has not
     * diverged from the preset (the VM then uses the preset's intent directly).
     *
     * Stored as individual float keys. All four must be present to reconstruct an
     * [AdaptiveIntent]; if any are absent the store treats them as null (no custom).
     */
    val customIntent: Flow<AdaptiveIntent?> = context.adaptivePrefsStore.data.map { prefs ->
        val p = prefs[CUSTOM_W_PERF_KEY]
        val b = prefs[CUSTOM_W_BATT_KEY]
        val s = prefs[CUSTOM_W_STAB_KEY]
        val t = prefs[CUSTOM_W_THERMAL_KEY]
        if (p != null && b != null && s != null && t != null) {
            AdaptiveIntent(
                wPerformance = p,
                wBattery = b,
                wStability = s,
                wThermalHeadroom = t,
            )
        } else {
            null
        }
    }

    /** Persist raw custom weights. Null to clear (revert to using the preset). */
    suspend fun setCustomIntent(intent: AdaptiveIntent?) {
        context.adaptivePrefsStore.edit { prefs ->
            if (intent == null) {
                prefs.remove(CUSTOM_W_PERF_KEY)
                prefs.remove(CUSTOM_W_BATT_KEY)
                prefs.remove(CUSTOM_W_STAB_KEY)
                prefs.remove(CUSTOM_W_THERMAL_KEY)
            } else {
                prefs[CUSTOM_W_PERF_KEY]    = intent.wPerformance
                prefs[CUSTOM_W_BATT_KEY]    = intent.wBattery
                prefs[CUSTOM_W_STAB_KEY]    = intent.wStability
                prefs[CUSTOM_W_THERMAL_KEY] = intent.wThermalHeadroom
            }
        }
    }

    // ── GPU OC tier ───────────────────────────────────────────────────────────

    /**
     * The chosen [GpuOcTier]. BEYOND_STOCK is only honoured when
     * [beyondStockConsent] is also true; the VM enforces this.
     *
     * Default: [GpuOcTier.OFF] — safe option, user must explicitly opt in.
     */
    val gpuOcTier: Flow<GpuOcTier> = context.adaptivePrefsStore.data.map { prefs ->
        val ordinal = prefs[GPU_OC_TIER_ORDINAL_KEY]
        if (ordinal != null) {
            GpuOcTier.entries.getOrNull(ordinal) ?: GpuOcTier.OFF
        } else {
            GpuOcTier.OFF
        }
    }

    suspend fun setGpuOcTier(tier: GpuOcTier) {
        context.adaptivePrefsStore.edit { it[GPU_OC_TIER_ORDINAL_KEY] = tier.ordinal }
    }

    // ── Beyond-stock consent ──────────────────────────────────────────────────

    /**
     * Whether the user has explicitly confirmed the beyond-stock risk dialog.
     * False by default — BEYOND_STOCK is never applied without this.
     */
    val beyondStockConsent: Flow<Boolean> = context.adaptivePrefsStore.data.map { prefs ->
        prefs[BEYOND_STOCK_CONSENT_KEY] ?: false
    }

    suspend fun setBeyondStockConsent(granted: Boolean) {
        context.adaptivePrefsStore.edit { it[BEYOND_STOCK_CONSENT_KEY] = granted }
    }

    // ── Beyond-stock probe verdict (cached, device-fingerprinted) ────────────

    /**
     * Cached result of Unit 3's [GpuOcVerdict] probe, stored as a serialized string
     * together with the device fingerprint (Build.FINGERPRINT + kernel release).
     *
     * Format: `"<fingerprint>|<verdict>"` where verdict is one of:
     *   - `"Unsupported"`
     *   - `"Rejected:<clampedHz>"`
     *   - `"Ineffective"`
     *   - `"Accepted:<reachedHz>"`
     *
     * Null = not yet probed, or the record was invalidated (fingerprint changed).
     * Unit 3 writes this; Unit 4 reads + parses it for the honest UI state.
     */
    val beyondStockProbeVerdict: Flow<String?> = context.adaptivePrefsStore.data.map { prefs ->
        prefs[BEYOND_STOCK_PROBE_VERDICT_KEY]
    }

    /** Called by Unit 3 to cache the probe verdict. */
    suspend fun setBeyondStockProbeVerdict(verdictRecord: String?) {
        context.adaptivePrefsStore.edit { prefs ->
            if (verdictRecord == null) {
                prefs.remove(BEYOND_STOCK_PROBE_VERDICT_KEY)
            } else {
                prefs[BEYOND_STOCK_PROBE_VERDICT_KEY] = verdictRecord
            }
        }
    }

    // ── Adaptive mode active flag ─────────────────────────────────────────────

    /**
     * Whether Adaptive mode is the active top-level mode on the AutoTDP screen.
     * Default OFF — the existing Goal Modes / Manual panels are not disturbed until
     * the user explicitly selects Adaptive.
     */
    val adaptiveModeActive: Flow<Boolean> = context.adaptivePrefsStore.data.map { prefs ->
        prefs[ADAPTIVE_MODE_ACTIVE_KEY] ?: false
    }

    suspend fun setAdaptiveModeActive(active: Boolean) {
        context.adaptivePrefsStore.edit { it[ADAPTIVE_MODE_ACTIVE_KEY] = active }
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    private companion object {
        val SELECTED_PRESET_ORDINAL_KEY      = intPreferencesKey("adaptive_selected_preset_ordinal")
        val CUSTOM_W_PERF_KEY                = floatPreferencesKey("adaptive_custom_w_perf")
        val CUSTOM_W_BATT_KEY                = floatPreferencesKey("adaptive_custom_w_batt")
        val CUSTOM_W_STAB_KEY                = floatPreferencesKey("adaptive_custom_w_stab")
        val CUSTOM_W_THERMAL_KEY             = floatPreferencesKey("adaptive_custom_w_thermal")
        val GPU_OC_TIER_ORDINAL_KEY          = intPreferencesKey("adaptive_gpu_oc_tier_ordinal")
        val BEYOND_STOCK_CONSENT_KEY         = booleanPreferencesKey("adaptive_beyond_stock_consent")
        val BEYOND_STOCK_PROBE_VERDICT_KEY   = stringPreferencesKey("adaptive_beyond_stock_probe_verdict")
        val ADAPTIVE_MODE_ACTIVE_KEY         = booleanPreferencesKey("adaptive_mode_active")
    }
}

// ── DataStore extension — isolated from every other store in the app ──────────
private val Context.adaptivePrefsStore by preferencesDataStore(name = "adaptive_prefs")
