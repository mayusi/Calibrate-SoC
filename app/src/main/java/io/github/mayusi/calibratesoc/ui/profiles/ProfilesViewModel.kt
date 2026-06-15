package io.github.mayusi.calibratesoc.ui.profiles

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.backup.BackupManager
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.profiles.ForegroundAppWatcher
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.share.PresetShareCodec
import io.github.mayusi.calibratesoc.data.share.ShareDecodeResult
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val shareCodec: PresetShareCodec,
    private val backupManager: BackupManager,
    private val tuneHistoryStore: TuneHistoryStore,
) : ViewModel() {

    val store: StateFlow<ProfileStore> =
        repository.store.stateIn(viewModelScope, SharingStarted.Eagerly, repository.snapshot())

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    // ─── Active-profile awareness ──────────────────────────────────────────────

    /**
     * The id of the profile that was most recently applied, or null when no
     * history entry matches any saved profile by name.  Derived by comparing
     * the newest TuneHistoryEntry.presetName against each UserProfile.name.
     *
     * This is a best-effort heuristic: profiles are matched by name because
     * TuneHistoryEntry pre-dates stable profile ids on every apply path.
     * The match is intentionally loose (trimmed, case-insensitive) so it
     * survives minor renames during import/export round-trips.
     */
    val activeProfileId: StateFlow<String?> = combine(
        store,
        tuneHistoryStore.entries,
    ) { profileStore, history ->
        val lastPresetName = history.firstOrNull()?.presetName?.trim() ?: return@combine null
        profileStore.profiles.firstOrNull { p ->
            p.name.trim().equals(lastPresetName, ignoreCase = true)
        }?.id
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ─── Accessibility grant awareness ────────────────────────────────────────

    /**
     * True when the Calibrate SoC ForegroundAppWatcher Accessibility service
     * is enabled in system settings. Refreshed each time [refreshAccessibility]
     * is called (called by the UI on every screen resume via LaunchedEffect).
     */
    private val _accessibilityGranted = MutableStateFlow(checkAccessibilityGranted())
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted.asStateFlow()

    /** Re-read the system accessibility setting. Call from screen resume. */
    fun refreshAccessibility() {
        _accessibilityGranted.value = checkAccessibilityGranted()
    }

    private fun checkAccessibilityGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val needle = ComponentName(context, ForegroundAppWatcher::class.java).flattenToString()
        return enabled.split(':').any { it.equals(needle, ignoreCase = true) }
    }

    // ─── App label cache ───────────────────────────────────────────────────────

    /**
     * Cache of package-name -> human-readable app label.  Populated lazily
     * on first request via [resolveAppLabel]; avoids calling PackageManager
     * from the main thread on every recomposition.
     */
    private val appLabelCache = mutableMapOf<String, String>()

    /**
     * Returns the resolved app label for [packageName], falling back to the
     * package name itself if the app is not installed.  The result is cached
     * in [appLabelCache] after the first resolution so recompositions are
     * cheap.  IO is performed inline on the calling coroutine (caller must be
     * off the main thread or tolerate a brief block; in practice the cache
     * hit rate is 100 % after first render).
     */
    fun resolveAppLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrElse { packageName }
        appLabelCache[packageName] = label
        return label
    }

    // ─── Share / import state ──────────────────────────────────────────────────

    /**
     * The share code for the profile being shared, or null when no share is
     * in progress. The UI consumes this to show copy/share options.
     */
    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    /**
     * Current import dialog state. Null = dialog closed.
     */
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** Generate the share code for [profile]. Exposed via [shareCode]. */
    fun generateShareCode(profile: UserProfile) {
        val code = runCatching { shareCodec.encode(profile) }.getOrElse {
            // Encoding should never fail for valid profiles, but guard anyway.
            _shareCode.value = null
            return
        }
        _shareCode.value = code
    }

    fun clearShareCode() {
        _shareCode.value = null
    }

    /**
     * Decode a pasted code string and update [importState].
     *  - If the code is blank, resets to Idle.
     *  - On error, transitions to [ImportState.Error].
     *  - On success, transitions to [ImportState.Preview] with the decoded profile.
     */
    fun decodeImportCode(code: String) {
        if (code.isBlank()) {
            _importState.value = ImportState.Idle
            return
        }
        when (val result = shareCodec.decode(code)) {
            is ShareDecodeResult.Success -> _importState.value = ImportState.Preview(result.profile)
            is ShareDecodeResult.Error -> _importState.value = ImportState.Error(result.reason)
        }
    }

    /**
     * Validate then save the previewed profile to the user's profile store.
     * The profile is marked as-is (description will contain the "[Shared]" tag
     * applied in [ImportState.Preview]).
     * Returns a human-readable error string on failure, or null on success.
     */
    fun confirmImport(profile: UserProfile, onResult: (error: String?) -> Unit) {
        viewModelScope.launch {
            // Reuse BackupManager.validateProfile for defence-in-depth.
            val validationError = backupManager.validateProfile(profile)
            if (validationError != null) {
                onResult("Cannot import: $validationError")
                return@launch
            }
            runCatching { repository.saveProfile(profile) }
                .onSuccess { onResult(null) }
                .onFailure { onResult("Failed to save: ${it.message}") }
        }
    }

    fun dismissImport() {
        _importState.value = ImportState.Idle
    }

    /** States of the import dialog. */
    sealed class ImportState {
        object Idle : ImportState()
        data class Preview(val profile: UserProfile) : ImportState()
        data class Error(val reason: String) : ImportState()
    }

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
