package io.github.mayusi.calibratesoc.ui.insights

import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.insights.InsightsAggregator
import io.github.mayusi.calibratesoc.data.insights.SessionReport
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportDao
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

/**
 * ViewModel for InsightsScreen.
 *
 * Wires [SessionReportDao] → [InsightsAggregator] → UI state.
 * All aggregation is pure and synchronous; the ViewModel only handles
 * the Flow wiring and the week-window calculation.
 *
 * Also owns [applyBestProfile]: one-tap binding of the learned best profile
 * for a game. Resolves appLabel → packageName via PackageManager (honest
 * fallback: surfaces an error if the label can't be uniquely resolved) and
 * profileName → profileId via [ProfileRepository] (honest fallback: surfaces
 * an error if the profile was renamed/deleted). Preserves existing bundle
 * fields (autoTdpGoal, refreshRateHz, fanMode, gameBoostOnLaunch) so this
 * never clobbers a bundle the user already configured.
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SessionReportDao,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    /**
     * Live snapshot of the profile store — used by InsightsScreen to show
     * "APPLIED" state on best-profile panels when a profile is already bound.
     */
    val profileStore: StateFlow<io.github.mayusi.calibratesoc.data.profiles.ProfileStore> =
        profileRepository.store
            .stateIn(viewModelScope, SharingStarted.Eagerly, profileRepository.snapshot())

    /** All recent session reports, newest first, from the DAO. */
    val reports: StateFlow<List<SessionReport>> = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Rolled-up insights summary, recomputed whenever [reports] changes.
     * Week window = last 7 days (inclusive of today).
     */
    val summary: StateFlow<InsightsAggregator.InsightsSummary> = reports
        .map { all ->
            val weekStart = weekStartMs()
            val weekEnd = System.currentTimeMillis()
            val weekReports = all.filter { it.startedAtMs in weekStart..weekEnd }
            InsightsAggregator.compute(all, weekReports)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            InsightsAggregator.InsightsSummary(
                batterySavedThisWeekMwh = null,
                tempTrendCPerSession = null,
                bestProfilePerApp = emptyMap(),
                bestProfilePerPackage = emptyMap(),
                insufficientDataReason = "Loading…",
            ),
        )

    /**
     * Returns the most recent [SessionReport] — surfaced as a "post-session
     * report" after a play session ends. Null when no sessions exist.
     */
    val latestReport: StateFlow<SessionReport?> = reports
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Apply best profile ─────────────────────────────────────────────────────

    /**
     * Result of a one-tap "Apply best profile" action.
     *
     * [Idle] — no action in progress or pending display.
     * [Success] — profile was bound. [packageName], [profileName], [appLabel]
     *   are populated for the snackbar message.
     * [Error] — something went wrong honestly. [reason] is a user-readable
     *   explanation (profile deleted, app not found, etc.). No write occurred.
     */
    sealed interface ApplyResult {
        data object Idle : ApplyResult
        data class Success(
            val appLabel: String,
            val profileName: String,
            val packageName: String,
        ) : ApplyResult
        data class Error(val reason: String) : ApplyResult
    }

    private val _applyResult = MutableStateFlow<ApplyResult>(ApplyResult.Idle)
    val applyResult: StateFlow<ApplyResult> = _applyResult.asStateFlow()

    /**
     * One-tap apply: bind the named [profileName] to the app identified
     * by [appLabel] (which is the key used in [InsightsAggregator.bestProfilePerApp]).
     *
     * Steps:
     * 1. Resolve [appLabel] → packageName via PackageManager.
     *    — If no installed app has that label: error, no write.
     *    — If multiple apps share that label: error, no write (ambiguous).
     * 2. Resolve [profileName] → profileId via [ProfileRepository.snapshot].
     *    — If the profile no longer exists (renamed/deleted): error, no write.
     * 3. Preserve existing bundle fields (autoTdpGoal, refreshRateHz, fanMode,
     *    gameBoostOnLaunch); update only profileId.
     * 4. Call [ProfileRepository.setBundle]. Emit [ApplyResult.Success] on
     *    completion so the Screen shows a confirmation.
     *
     * [ForegroundAppWatcher] picks up the new bundle automatically on the next
     * foreground switch — zero extra wiring needed.
     */
    fun applyBestProfile(appLabel: String, profileName: String) {
        viewModelScope.launch {
            // ── Step 1: resolve appLabel → packageName ─────────────────────────
            val packageName = withContext(Dispatchers.IO) {
                resolvePackageName(context, appLabel)
            }
            when (packageName) {
                null -> {
                    _applyResult.value = ApplyResult.Error(
                        "Couldn't find an installed app matching \"$appLabel\" — " +
                            "the app may have been uninstalled.",
                    )
                    return@launch
                }
                AMBIGUOUS_PACKAGE -> {
                    _applyResult.value = ApplyResult.Error(
                        "Multiple apps are named \"$appLabel\" — " +
                            "can't auto-bind without a unique package name.",
                    )
                    return@launch
                }
            }

            // ── Step 2: resolve profileName → profileId ────────────────────────
            val store = profileRepository.snapshot()
            val profile = store.profiles.firstOrNull { it.name == profileName }
            if (profile == null) {
                _applyResult.value = ApplyResult.Error(
                    "Profile \"$profileName\" no longer exists — " +
                        "it may have been renamed or deleted. No changes were made.",
                )
                return@launch
            }

            // ── Step 3: preserve existing bundle, update only profileId ─────────
            val existing = store.perAppBundles[packageName]
            val updated = (existing ?: PerAppBundle()).copy(profileId = profile.id)

            // ── Step 4: write ──────────────────────────────────────────────────
            profileRepository.setBundle(packageName!!, updated)

            _applyResult.value = ApplyResult.Success(
                appLabel = appLabel,
                profileName = profileName,
                packageName = packageName,
            )
        }
    }

    /**
     * Package-keyed variant of [applyBestProfile].
     *
     * Skips the fragile PackageManager label-scan by using [packageName] directly
     * as the bundle key (which is what [ProfileRepository.setBundle] and
     * [ProfileStore.perAppBundles] index on anyway).
     *
     * Steps:
     * 1. Resolve [profileName] → UserProfile via [ProfileRepository.snapshot].
     *    If the profile no longer exists: error, no write.
     * 2. Preserve all existing bundle fields; update only profileId.
     * 3. Call [ProfileRepository.setBundle]. Emit [ApplyResult.Success].
     *
     * KEEP [applyBestProfile] (label-keyed) for existing InsightsScreen panels.
     */
    fun applyBestProfileByPackage(
        packageName: String,
        appLabel: String,
        profileName: String,
    ) {
        viewModelScope.launch {
            val store = profileRepository.snapshot()
            val profile = store.profiles.firstOrNull { it.name == profileName }
            if (profile == null) {
                _applyResult.value = ApplyResult.Error(
                    "Profile \"$profileName\" no longer exists — " +
                        "it may have been renamed or deleted. No changes were made.",
                )
                return@launch
            }
            val existing = store.perAppBundles[packageName]
            val updated = (existing ?: PerAppBundle()).copy(profileId = profile.id)
            profileRepository.setBundle(packageName, updated)
            _applyResult.value = ApplyResult.Success(
                appLabel = appLabel,
                profileName = profileName,
                packageName = packageName,
            )
        }
    }

    /** Reset [applyResult] to [ApplyResult.Idle] after the UI has consumed it. */
    fun clearApplyResult() {
        _applyResult.value = ApplyResult.Idle
    }

    companion object {
        /**
         * Sentinel returned by [resolvePackageName] when multiple installed apps
         * share the same display label. Callers treat this as an ambiguity error.
         */
        internal const val AMBIGUOUS_PACKAGE = "__AMBIGUOUS__"

        /** Start of the current calendar week (Monday 00:00:00.000 local). */
        fun weekStartMs(): Long {
            val cal = Calendar.getInstance()
            // Pin the week to start on Monday regardless of the device locale's
            // first-day-of-week. Without this, on a Sunday-first locale (e.g. en-US)
            // setting DAY_OF_WEEK=MONDAY while today is Sunday rolls FORWARD to next
            // Monday — putting the week start in the future. Forcing firstDayOfWeek
            // makes "this week's Monday" always resolve to the past Monday.
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /**
         * Resolve a human-readable app label to a package name using
         * [PackageManager.getInstalledApplications].
         *
         * Returns:
         * - The unique package name when exactly one installed app matches.
         * - [AMBIGUOUS_PACKAGE] when multiple apps share the same label.
         * - `null` when no installed app matches the label.
         *
         * Matching is case-insensitive to tolerate minor label inconsistencies
         * (some launchers trim or capitalize differently).
         *
         * This is IO-bound and should be called from [Dispatchers.IO].
         */
        internal fun resolvePackageName(context: Context, appLabel: String): String? {
            val pm = context.packageManager
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            val labelLower = appLabel.trim().lowercase()
            val matches = infos.filter { info ->
                runCatching {
                    pm.getApplicationLabel(info).toString().trim().lowercase() == labelLower
                }.getOrDefault(false)
            }
            return when (matches.size) {
                0 -> null
                1 -> matches[0].packageName
                else -> AMBIGUOUS_PACKAGE
            }
        }
    }
}
