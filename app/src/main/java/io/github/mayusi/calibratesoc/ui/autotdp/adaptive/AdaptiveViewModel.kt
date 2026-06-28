package io.github.mayusi.calibratesoc.ui.autotdp.adaptive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.autotdp.AdaptiveRunConfig
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.TdpState
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptiveIntent
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptivePreset
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuOcTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UNIT 4 (ADAPTIVE MODE) — ViewModel for the Adaptive panel + prefs.
 *
 * ## Responsibility boundaries
 *  - Owns all Adaptive-mode UI state: preset, custom intent, GPU OC tier, consent.
 *  - Persists state via [AdaptivePrefs] (own store, no collision risk).
 *  - Reads the live run-state from [AutoTdpController] for the live readout card.
 *  - DOES NOT start/stop the daemon — that is Unit 5's job. [setAdaptiveActive]
 *    persists the active flag and exposes [adaptiveConfig] for Unit 5 to consume;
 *    see the [TODO] stub at the bottom of this file.
 *
 * ## Custom-weights renormalization contract
 *  - Weights are stored as RAW slider values (0..100 int mapped to 0f..1f float).
 *  - [updateCustomWeight] adjusts one axis and renormalizes the other three live
 *    so they always sum to 1.0 as an [AdaptiveIntent].
 *  - [effectiveIntent] is always the normalized vector the policy consumes.
 *
 * ## Beyond-stock consent gate
 *  - [setGpuOcTier] with [GpuOcTier.BEYOND_STOCK] is a no-op unless
 *    [grantBeyondStockConsent] has been called first. The UI triggers a dialog
 *    and calls [grantBeyondStockConsent] only after the user confirms.
 */
@HiltViewModel
class AdaptiveViewModel @Inject constructor(
    private val prefs: AdaptivePrefs,
    private val controller: AutoTdpController,
) : ViewModel() {

    // ── Preset ────────────────────────────────────────────────────────────────

    val selectedPreset: StateFlow<AdaptivePreset> = prefs.selectedPreset
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdaptivePreset.DEFAULT)

    /** Select a named preset. Clears any custom weights (exits Custom mode). */
    fun selectPreset(preset: AdaptivePreset) {
        viewModelScope.launch {
            prefs.setSelectedPreset(preset)
            prefs.setCustomIntent(null)          // exit Custom mode
        }
    }

    // ── Custom weights (4 raw floats, lazily seeded from the preset) ──────────

    /**
     * Raw (un-normalized) custom weights as a [AdaptiveIntent]. Null when the user
     * has not entered Custom mode (i.e. is using the selected preset verbatim).
     */
    val customIntent: StateFlow<AdaptiveIntent?> = prefs.customIntent
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The normalized intent the policy actually consumes:
     *  - If custom weights exist → [AdaptiveIntent.normalized] of those weights.
     *  - Otherwise → the selected preset's intent (already normalized by design).
     */
    val effectiveIntent: StateFlow<AdaptiveIntent> =
        combine(selectedPreset, customIntent) { preset, custom ->
            custom?.normalized() ?: preset.intent
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AdaptivePreset.DEFAULT.intent)

    /**
     * Update one axis of the custom weight and renormalize the other three.
     *
     * The axis is identified by index (0=Performance, 1=Battery, 2=Stability,
     * 3=Cool & Quiet / ThermalHeadroom). The new value is in [0f, 1f].
     *
     * If not yet in Custom mode, seeds from the current preset first.
     */
    fun updateCustomWeight(axisIndex: Int, newValue: Float) {
        require(axisIndex in 0..3) { "axisIndex must be 0..3, got $axisIndex" }
        viewModelScope.launch {
            val base = customIntent.value ?: selectedPreset.value.intent
            val clamped = newValue.coerceIn(0f, 1f)

            // Build raw vector with the new axis pinned
            val raw = when (axisIndex) {
                0 -> AdaptiveIntent(clamped, base.wBattery, base.wStability, base.wThermalHeadroom)
                1 -> AdaptiveIntent(base.wPerformance, clamped, base.wStability, base.wThermalHeadroom)
                2 -> AdaptiveIntent(base.wPerformance, base.wBattery, clamped, base.wThermalHeadroom)
                else -> AdaptiveIntent(base.wPerformance, base.wBattery, base.wStability, clamped)
            }
            prefs.setCustomIntent(raw)
        }
    }

    /** Enter Custom mode seeded from the current preset (if not already in Custom). */
    fun enterCustom() {
        viewModelScope.launch {
            if (customIntent.value == null) {
                prefs.setCustomIntent(selectedPreset.value.intent)
            }
        }
    }

    /** Exit Custom mode — reverts to using the selected preset's intent. */
    fun exitToPreset() {
        viewModelScope.launch {
            prefs.setCustomIntent(null)
        }
    }

    /**
     * Find the preset whose intent is nearest (Euclidean distance in 4D weight space)
     * to the current effective intent. Used by the "Reset to {nearest preset}" chip.
     */
    val nearestPreset: StateFlow<AdaptivePreset> = effectiveIntent
        .map { intent ->
            AdaptivePreset.entries.minByOrNull { preset ->
                val dp = intent.wPerformance    - preset.intent.wPerformance
                val db = intent.wBattery        - preset.intent.wBattery
                val ds = intent.wStability      - preset.intent.wStability
                val dt = intent.wThermalHeadroom - preset.intent.wThermalHeadroom
                dp * dp + db * db + ds * ds + dt * dt
            } ?: AdaptivePreset.DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdaptivePreset.DEFAULT)

    // ── GPU OC tier + consent ─────────────────────────────────────────────────

    val gpuOcTier: StateFlow<GpuOcTier> = prefs.gpuOcTier
        .stateIn(viewModelScope, SharingStarted.Eagerly, GpuOcTier.OFF)

    val beyondStockConsent: StateFlow<Boolean> = prefs.beyondStockConsent
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Cached probe verdict string (fingerprint|verdict) from Unit 3.
     * Null = not yet probed. The UI parses this to show the honest yellow state.
     */
    val beyondStockProbeVerdict: StateFlow<String?> = prefs.beyondStockProbeVerdict
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Select a GPU OC tier.
     *
     * [GpuOcTier.BEYOND_STOCK] requires prior consent — if [beyondStockConsent] is
     * false this call is silently ignored. The UI must show the consent dialog and
     * call [grantBeyondStockConsent] before calling [setGpuOcTier] with BEYOND_STOCK.
     */
    fun setGpuOcTier(tier: GpuOcTier) {
        viewModelScope.launch {
            if (tier == GpuOcTier.BEYOND_STOCK && !beyondStockConsent.value) {
                // Consent gate — UI must call grantBeyondStockConsent first
                return@launch
            }
            prefs.setGpuOcTier(tier)
        }
    }

    /**
     * Called by the UI after the user confirms the beyond-stock risk dialog.
     * Grants consent AND applies the BEYOND_STOCK tier in one step.
     */
    fun grantBeyondStockConsent() {
        viewModelScope.launch {
            prefs.setBeyondStockConsent(true)
            prefs.setGpuOcTier(GpuOcTier.BEYOND_STOCK)
        }
    }

    // ── Adaptive mode active ──────────────────────────────────────────────────

    val adaptiveModeActive: StateFlow<Boolean> = prefs.adaptiveModeActive
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Enable or disable Adaptive mode.
     *
     * UNIT 5: persists the active flag AND drives the daemon. Turning Adaptive ON builds the
     * resolved [AdaptiveRunConfig] from the current VM state (effective intent + OC tier +
     * consent + cached probe verdict) and engages the unified governor via
     * [AutoTdpController.start]; turning it OFF stops the daemon (the single revert handle
     * restores CPU + GPU + OC + DDR to stock). Mutual exclusion: the adaptive config carries
     * no [io.github.mayusi.calibratesoc.data.autotdp.GoalProfile], so the legacy single-goal
     * path is never engaged this session.
     */
    fun setAdaptiveActive(on: Boolean) {
        viewModelScope.launch {
            prefs.setAdaptiveModeActive(on)
            if (on) {
                controller.start(buildAdaptiveRunConfig())
            } else {
                controller.stop()
            }
        }
    }

    /**
     * UNIT 5: snapshot the current VM state into the round-trip-safe [AdaptiveRunConfig] the
     * controller hands to the daemon. The intent is the NORMALIZED [effectiveIntent]; the OC
     * tier + consent are the user's choices (the policy gates beyond-stock again on the
     * daemon side); the verdict record is the cached probe string (fingerprint stripped — the
     * daemon re-probes per session anyway, so the cache is only a hint here).
     */
    private fun buildAdaptiveRunConfig(): AdaptiveRunConfig {
        val intent = effectiveIntent.value
        // The prefs store the verdict as "<fingerprint>|<verdict>"; strip the fingerprint so
        // the carrier holds just the bare verdict form the coordinator parses.
        val verdictRecord = beyondStockProbeVerdict.value?.substringAfterLast('|')
        return AdaptiveRunConfig(
            wPerformance = intent.wPerformance,
            wBattery = intent.wBattery,
            wStability = intent.wStability,
            wThermalHeadroom = intent.wThermalHeadroom,
            gpuOcTierOrdinal = gpuOcTier.value.ordinal,
            beyondStockConsent = beyondStockConsent.value,
            probeVerdictRecord = verdictRecord,
        )
    }

    // ── Live readout (from AutoTdpController run-state) ───────────────────────

    /** Full run-state; the UI picks what to render in the live readout card. */
    val runState: StateFlow<AutoTdpRunState> = controller.state

    /** True while the daemon is RUNNING (any mode, not just adaptive). */
    val isRunning: StateFlow<Boolean> = controller.state
        .map { it.status == AutoTdpStatus.RUNNING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Formatted "why" label from the last decision — shown in the live readout.
     * Drawn from [AutoTdpRunState.lastReason] (the human-readable decision string).
     */
    val liveWhyLabel: StateFlow<String> = controller.state
        .map { state ->
            when {
                state.lastReason.isNotBlank() -> state.lastReason
                state.status == AutoTdpStatus.RUNNING -> "governing"
                else -> ""
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Human-readable CPU cap from the last applied [TdpState].
     * Example: "2.1 GHz" — shown in the live readout card.
     * Null when no state has been applied yet.
     */
    val liveCpuCapLabel: StateFlow<String?> = controller.state
        .map { state ->
            val capKhz = state.appliedState?.bigClusterCapKhz ?: return@map null
            val ghz = capKhz / 1_000_000.0
            "%.1f GHz".format(ghz)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Human-readable GPU devfreq max from the last applied [TdpState].
     * Example: "540 MHz" — shown in the live readout card.
     * Null when not available.
     */
    val liveGpuLabel: StateFlow<String?> = controller.state
        .map { state ->
            val hz = state.appliedState?.gpuDevfreqMaxHz ?: return@map null
            val mhz = hz / 1_000_000L
            "$mhz MHz"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Unit 5 hook ───────────────────────────────────────────────────────────

    /**
     * The resolved adaptive configuration that Unit 5 should pass to the governor.
     *
     * Combines the effective intent + GPU OC tier so Unit 5 can compute
     * [AdaptiveSetpoints] via [AdaptivePolicy.resolve(effectiveIntent, caps, optIn, probe)].
     *
     * Unit 5 accesses:
     *  - [effectiveIntent]      — the normalized weight vector
     *  - [gpuOcTier]            — the chosen OC tier (consent already gated)
     *  - [beyondStockConsent]   — the raw consent flag
     *  - [beyondStockProbeVerdict] — the cached probe result
     *
     * TODO(Unit 5): add AutoTdpController.start(setpoints: AdaptiveSetpoints) overload,
     * then observe adaptiveModeActive + effectiveIntent here and call it.
     */
    val adaptiveConfigReady: StateFlow<Boolean> = combine(
        adaptiveModeActive,
        effectiveIntent,
    ) { active, _ -> active }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
