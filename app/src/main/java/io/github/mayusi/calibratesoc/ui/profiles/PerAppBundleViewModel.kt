package io.github.mayusi.calibratesoc.ui.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.profiles.AppReaper
import io.github.mayusi.calibratesoc.data.profiles.InstalledApp
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.ReaperConfig
import io.github.mayusi.calibratesoc.data.profiles.ReaperRepository
import io.github.mayusi.calibratesoc.data.profiles.enumerateInstalledUserApps
import io.github.mayusi.calibratesoc.data.profiles.ALWAYS_EXCLUDED_PREFIXES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the per-app bundle editor and reaper app-picker (Wave 4b).
 *
 * Owns:
 *  - [store]: profile store (includes perAppBundles map).
 *  - [installedApps]: lazily loaded list of user-installed apps (for both
 *    the bundle editor and the reaper picker). Filtered to exclude
 *    [ALWAYS_EXCLUDED_PREFIXES] entries in [reaperEligibleApps].
 *  - [reaperConfig]: live config from [ReaperRepository].
 *
 * Actions:
 *  - [setBundle]: save a full [PerAppBundle] for a package.
 *  - [clearBundle]: remove a bundle (and legacy override) for a package.
 *  - [setReaperEnabled]: toggle the reaper on/off globally.
 *  - [setReaperDenylist]: replace the full denylist (from picker selection).
 *  - [loadInstalledApps]: trigger lazy load of the installed-apps list.
 */
@HiltViewModel
class PerAppBundleViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val reaperRepository: ReaperRepository,
) : ViewModel() {

    val store: StateFlow<ProfileStore> =
        profileRepository.store.stateIn(viewModelScope, SharingStarted.Eagerly, profileRepository.snapshot())

    val reaperConfig: Flow<ReaperConfig> = reaperRepository.config

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    /**
     * User-installed apps that are eligible for the reaper denylist.
     * Excludes [ALWAYS_EXCLUDED_PREFIXES] entries (system/GMS/Calibrate).
     * Derived from [installedApps] (pure filter, no I/O).
     */
    val reaperEligibleApps: StateFlow<List<InstalledApp>> = _installedApps
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Lazy-load the installed apps list. No-op if already loading or loaded. */
    fun loadInstalledApps() {
        if (_appsLoading.value || _installedApps.value.isNotEmpty()) return
        _appsLoading.value = true
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                enumerateInstalledUserApps(context)
            }
            _appsLoading.value = false
        }
    }

    /**
     * Eligible apps for the reaper denylist — excludes always-excluded prefixes.
     * Call [loadInstalledApps] first; this is a derived view of [installedApps].
     */
    fun filterReaperEligible(apps: List<InstalledApp>): List<InstalledApp> = apps.filter { app ->
        val pkg = app.packageName
        ALWAYS_EXCLUDED_PREFIXES.none { prefix ->
            val bare = prefix.trimEnd('.')
            pkg == bare || pkg.startsWith("$bare.")
        }
    }

    // ── Per-app bundle actions ────────────────────────────────────────────────

    /** Save a full [PerAppBundle] for [packageName]. */
    fun setBundle(packageName: String, bundle: PerAppBundle) {
        viewModelScope.launch { profileRepository.setBundle(packageName, bundle) }
    }

    /** Remove the bundle (and legacy override) for [packageName]. */
    fun clearBundle(packageName: String) {
        viewModelScope.launch { profileRepository.clearPerAppMapping(packageName) }
    }

    // ── Reaper actions ────────────────────────────────────────────────────────

    fun setReaperEnabled(enabled: Boolean) {
        viewModelScope.launch { reaperRepository.setEnabled(enabled) }
    }

    fun setReaperDenylist(packages: Set<String>) {
        viewModelScope.launch { reaperRepository.setDenylist(packages) }
    }
}
