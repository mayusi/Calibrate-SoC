package io.github.mayusi.calibratesoc.ui.fancurve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.fancurve.ApplyResult
import io.github.mayusi.calibratesoc.data.fancurve.FanCurve
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveAvailability
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveController
import io.github.mayusi.calibratesoc.data.fancurve.FanCurvePoint
import io.github.mayusi.calibratesoc.data.fancurve.FanCurvePreset
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveStore
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveValidation
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveWarning
import io.github.mayusi.calibratesoc.data.fancurve.LiveFanReading
import io.github.mayusi.calibratesoc.data.fancurve.ReadCurveResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [FanCurveScreen].
 *
 * Holds the editable curve, the selected preset, availability, the live fan
 * readout, and the apply status. All device interaction goes through
 * [FanCurveController]; this VM never touches the privileged path directly.
 */
@HiltViewModel
class FanCurveViewModel @Inject constructor(
    private val controller: FanCurveController,
    private val store: FanCurveStore,
) : ViewModel() {

    // ── Availability ────────────────────────────────────────────────────────
    private val _availability = MutableStateFlow<FanCurveAvailability>(
        FanCurveAvailability.Unavailable("Checking…"),
    )
    val availability: StateFlow<FanCurveAvailability> = _availability.asStateFlow()

    // ── Editable curve ──────────────────────────────────────────────────────
    private val _curve = MutableStateFlow(FanCurvePreset.DEFAULT.curve)
    val curve: StateFlow<FanCurve> = _curve.asStateFlow()

    /** Selected preset id, or null when the curve has been hand-edited. */
    private val _selectedPresetId = MutableStateFlow<String?>(FanCurvePreset.DEFAULT.id)
    val selectedPresetId: StateFlow<String?> = _selectedPresetId.asStateFlow()

    // ── Opt-ins ─────────────────────────────────────────────────────────────
    val allowSubFloor: StateFlow<Boolean> = store.allowSubFloor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val applyOnOpen: StateFlow<Boolean> = store.applyOnOpen
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Live readout ────────────────────────────────────────────────────────
    private val _live = MutableStateFlow<LiveFanReading?>(null)
    val live: StateFlow<LiveFanReading?> = _live.asStateFlow()

    // ── Apply status ────────────────────────────────────────────────────────
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _lastApply = MutableStateFlow<ApplyResult?>(null)
    val lastApply: StateFlow<ApplyResult?> = _lastApply.asStateFlow()

    // ── Validation / warnings (recomputed on every edit for instant feedback) ──
    private val _warnings = MutableStateFlow<List<FanCurveWarning>>(emptyList())
    val warnings: StateFlow<List<FanCurveWarning>> = _warnings.asStateFlow()

    private val _validationState = MutableStateFlow<FanCurveValidation>(FanCurveValidation.Valid)
    val validationState: StateFlow<FanCurveValidation> = _validationState.asStateFlow()

    init {
        recomputeDerived()
        viewModelScope.launch { _availability.value = controller.availability() }
        // Load the saved curve/preset.
        viewModelScope.launch {
            val savedCurve = store.savedCurveNow()
            if (savedCurve != null) {
                _curve.value = savedCurve
                _selectedPresetId.value = store.savedPresetId.first()
                recomputeDerived()
            }
        }
        startLivePolling()
    }

    /** Re-resolve availability (call from onResume after the user runs unlock). */
    fun refreshAvailability() {
        viewModelScope.launch {
            _availability.value = controller.availability()
        }
    }

    /** Poll the live fan duty every few seconds while the screen is shown. */
    private fun startLivePolling() {
        viewModelScope.launch {
            while (isActive) {
                if (_availability.value is FanCurveAvailability.Available) {
                    _live.value = controller.readLiveFanDuty()
                }
                delay(LIVE_POLL_MS)
            }
        }
    }

    // ── Editing ───────────────────────────────────────────────────────────────

    fun selectPreset(preset: FanCurvePreset) {
        _curve.value = preset.curve
        _selectedPresetId.value = preset.id
        recomputeDerived()
        viewModelScope.launch { store.savePreset(preset) }
    }

    /** Update the duty of the point at [index] (0..100, clamped). Marks the
     *  curve as a hand-edited custom (clears preset selection). */
    fun setPointDuty(index: Int, dutyPct: Int) {
        val pts = _curve.value.points.toMutableList()
        if (index !in pts.indices) return
        pts[index] = pts[index].copy(dutyPct = dutyPct.coerceIn(0, 100))
        commitCustom(pts)
    }

    private fun commitCustom(points: List<FanCurvePoint>) {
        _curve.value = FanCurve(points)
        _selectedPresetId.value = null
        recomputeDerived()
        viewModelScope.launch { store.saveCustomCurve(_curve.value) }
    }

    private fun recomputeDerived() {
        val c = _curve.value
        _validationState.value = c.validate()
        _warnings.value = c.warnings()
    }

    // ── Opt-in toggles ──────────────────────────────────────────────────────

    fun setAllowSubFloor(enabled: Boolean) {
        viewModelScope.launch { store.setAllowSubFloor(enabled) }
    }

    fun setApplyOnOpen(enabled: Boolean) {
        viewModelScope.launch { store.setApplyOnOpen(enabled) }
    }

    // ── Apply / read ────────────────────────────────────────────────────────

    /** Read the device's current curve and load it into the editor. */
    fun loadCurrentFromDevice() {
        viewModelScope.launch {
            when (val r = controller.readCurrentCurve()) {
                is ReadCurveResult.Ok -> {
                    _curve.value = r.curve
                    _selectedPresetId.value = null
                    recomputeDerived()
                }
                is ReadCurveResult.Error -> {
                    // Surface via lastApply slot as an honest read error reusing Failed.
                    _lastApply.value = ApplyResult.Failed("Read failed: ${r.reason}")
                }
            }
        }
    }

    fun applyCurrentCurve() {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            _lastApply.value = null
            val result = controller.applyCurve(
                curve = _curve.value,
                allowSubFloor = allowSubFloor.value,
            )
            _lastApply.value = result
            _applying.value = false
            // Refresh the live readout right after an apply.
            if (_availability.value is FanCurveAvailability.Available) {
                _live.value = controller.readLiveFanDuty()
            }
        }
    }

    fun clearApplyStatus() {
        _lastApply.value = null
    }

    private companion object {
        const val LIVE_POLL_MS = 3_000L
    }
}
