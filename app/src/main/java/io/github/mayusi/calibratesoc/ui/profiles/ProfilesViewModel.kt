package io.github.mayusi.calibratesoc.ui.profiles

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Profiles + per-app override management. Loads the installed-apps
 * list lazily (it's slow on devices with hundreds of apps) and only
 * when the user opens the override-editor sheet.
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProfileRepository,
    private val applier: ProfileApplier,
    private val capabilityProbe: CapabilityProbe,
) : ViewModel() {

    val store: StateFlow<ProfileStore> =
        repository.store.stateIn(viewModelScope, SharingStarted.Eagerly, repository.snapshot())

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    fun apply(profile: UserProfile) {
        viewModelScope.launch {
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            applier.apply(profile.toPreset(), report, reason = "Profile: ${profile.name}")
        }
    }

    fun delete(profile: UserProfile) {
        viewModelScope.launch { repository.deleteProfile(profile.id) }
    }

    fun toggleApplyOnBoot(profile: UserProfile) {
        viewModelScope.launch {
            repository.saveProfile(profile.copy(applyOnBoot = !profile.applyOnBoot))
        }
    }

    fun setOverride(packageName: String, profileId: String?) {
        viewModelScope.launch { repository.setOverride(packageName, profileId) }
    }

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    // System apps are noise here. The override picker is
                    // for games + games' launchers, not for the platform.
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { info ->
                        InstalledApp(
                            packageName = info.packageName,
                            label = pm.getApplicationLabel(info).toString(),
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    data class InstalledApp(
        val packageName: String,
        val label: String,
    )
}
