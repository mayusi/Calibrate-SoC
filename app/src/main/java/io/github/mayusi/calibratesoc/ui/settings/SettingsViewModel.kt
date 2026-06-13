package io.github.mayusi.calibratesoc.ui.settings

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.baseline.FactoryBaseline
import io.github.mayusi.calibratesoc.data.baseline.FactoryBaselineRecorder
import io.github.mayusi.calibratesoc.data.baseline.FactoryRestorer
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.prefs.ClockUnit
import io.github.mayusi.calibratesoc.data.prefs.TempUnit
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.profiles.ForegroundAppWatcher
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.ui.theme.AccentColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityProbe: CapabilityProbe,
    private val userPrefs: UserPrefs,
    private val baselineRecorder: FactoryBaselineRecorder,
    private val factoryRestorer: FactoryRestorer,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    /** Factory baseline state surfaced in Settings so the user can
     *  see "captured at <date>, <N> tunables" before restoring. */
    private val _baseline = MutableStateFlow<FactoryBaseline?>(baselineRecorder.existing())
    val baseline: StateFlow<FactoryBaseline?> = _baseline.asStateFlow()

    private val _restoreSummary = MutableStateFlow<FactoryRestorer.RestoreSummary?>(null)
    val restoreSummary: StateFlow<FactoryRestorer.RestoreSummary?> = _restoreSummary.asStateFlow()

    fun restoreToFactory() {
        viewModelScope.launch {
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            val current = _baseline.value ?: return@launch
            _restoreSummary.value = factoryRestorer.restore(current, report)
        }
    }

    fun clearRestoreSummary() { _restoreSummary.value = null }

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    private val _accessibilityGranted = MutableStateFlow(checkAccessibilityGranted())
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted.asStateFlow()

    /** User opt-in to Magisk/KernelSU tier. False = the app behaves as
     *  if root doesn't exist (the friendlier default for non-modder
     *  users). True = the capability probe selects ROOT when su is
     *  present. */
    val rootModeEnabled: StateFlow<Boolean> = userPrefs.rootModeEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Master switch for experimental features (HUD ± steppers, etc).
     *  Default OFF. Flipped ON requires a typed-confirm modal in the
     *  Settings UI. */
    val experimentalEnabled: StateFlow<Boolean> = userPrefs.experimentalEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setExperimentalEnabled(value: Boolean) {
        viewModelScope.launch { userPrefs.setExperimentalEnabled(value) }
    }

    /** Whether root could theoretically be enabled — surfaced so the
     *  Settings UI can disable the toggle on devices with no su. */
    val rootDetected: StateFlow<Boolean> = capabilityProbe.report
        .let { reportFlow ->
            MutableStateFlow(false).also { sink ->
                viewModelScope.launch {
                    reportFlow.collect { report ->
                        sink.value = (report?.rootKind ?: RootKind.NONE) != RootKind.NONE
                    }
                }
            }
        }

    val appVersion: String = BuildConfig.VERSION_NAME
    val appVersionCode: Int = BuildConfig.VERSION_CODE

    fun setRootModeEnabled(value: Boolean) {
        viewModelScope.launch {
            userPrefs.setRootModeEnabled(value)
            // Re-probe so the new tier classification takes effect
            // immediately rather than waiting for next screen resume.
            capabilityProbe.refresh()
        }
    }

    // ── Accent colour ────────────────────────────────────────────────────────

    val accentColor: StateFlow<AccentColor> = userPrefs.accentColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.BLUE)

    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { userPrefs.setAccentColor(accent) }
    }

    // ── Units ────────────────────────────────────────────────────────────────

    val clockUnit: StateFlow<ClockUnit> = userPrefs.clockUnit
        .stateIn(viewModelScope, SharingStarted.Eagerly, ClockUnit.MHZ)

    val tempUnit: StateFlow<TempUnit> = userPrefs.tempUnit
        .stateIn(viewModelScope, SharingStarted.Eagerly, TempUnit.CELSIUS)

    fun setClockUnit(unit: ClockUnit) {
        viewModelScope.launch { userPrefs.setClockUnit(unit) }
    }

    fun setTempUnit(unit: TempUnit) {
        viewModelScope.launch { userPrefs.setTempUnit(unit) }
    }

    // ── Temperature alerts ────────────────────────────────────────────────────

    val tempAlertsEnabled: StateFlow<Boolean> = userPrefs.tempAlertsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val tempAlertThresholdC: StateFlow<Int> = userPrefs.tempAlertThresholdC
        .stateIn(viewModelScope, SharingStarted.Eagerly, 80)

    val tempAlertAutoProfileId: StateFlow<String?> = userPrefs.tempAlertAutoProfileId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Saved profiles for the auto-switch picker. Empty until profiles are
     *  loaded; UI shows "None" as the first option regardless. */
    val savedProfiles: StateFlow<List<UserProfile>> = profileRepository.store
        .map { it.profiles.sortedByDescending { p -> p.createdAtMs } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setTempAlertsEnabled(value: Boolean) {
        viewModelScope.launch { userPrefs.setTempAlertsEnabled(value) }
    }

    fun setTempAlertThresholdC(value: Int) {
        viewModelScope.launch { userPrefs.setTempAlertThresholdC(value) }
    }

    fun setTempAlertAutoProfileId(profileId: String?) {
        viewModelScope.launch { userPrefs.setTempAlertAutoProfileId(profileId) }
    }

    // ── What's New / update banner ────────────────────────────────────────────

    /** True when the current versionCode hasn't been seen yet.
     *  Shown as a dismissible banner at the top of the Settings screen. */
    val shouldShowWhatsNew: StateFlow<Boolean> = userPrefs.lastSeenVersion
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
        .let { lastSeenFlow ->
            MutableStateFlow(false).also { sink ->
                viewModelScope.launch {
                    lastSeenFlow.collect { lastSeen ->
                        sink.value = BuildConfig.VERSION_CODE > lastSeen
                    }
                }
            }
        }

    fun markWhatsNewSeen() {
        viewModelScope.launch { userPrefs.setLastSeenVersion(BuildConfig.VERSION_CODE) }
    }

    // ── Navigation state for What's New overlay ───────────────────────────────

    private val _showWhatsNewScreen = MutableStateFlow(false)
    val showWhatsNewScreen: StateFlow<Boolean> = _showWhatsNewScreen.asStateFlow()

    fun openWhatsNew() { _showWhatsNewScreen.value = true }
    fun closeWhatsNew() { _showWhatsNewScreen.value = false }

    fun refresh() {
        _accessibilityGranted.value = checkAccessibilityGranted()
        viewModelScope.launch { capabilityProbe.refresh() }
    }

    /**
     * Read AccessibilityManager's enabled-services string for our
     * component. Returns true if the user has flipped the switch in
     * Settings > Accessibility. Updated on each screen resume — no
     * way to observe the system setting from a Compose StateFlow
     * without a content observer, which is overkill here.
     */
    private fun checkAccessibilityGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val needle = ComponentName(context, ForegroundAppWatcher::class.java).flattenToString()
        return enabled.split(':').any { it.equals(needle, ignoreCase = true) }
    }
}
