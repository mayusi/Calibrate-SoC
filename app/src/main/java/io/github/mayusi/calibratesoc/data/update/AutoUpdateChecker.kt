package io.github.mayusi.calibratesoc.data.update

import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a best-effort, throttled update check on app launch and exposes the
 * result as a [StateFlow] that the app-level UI observes to show the banner.
 *
 * Decisions (all pure — see [AutoUpdateDecision]):
 *   - Only runs when [UserPrefs.autoUpdateCheckEnabled] is true.
 *   - Throttled to at most once per 24 hours via [UserPrefs.lastUpdateCheckMs].
 *   - Silent no-op when offline (fetchLatest returns null).
 *   - Never auto-downloads or auto-installs. Only sets [pendingUpdate].
 *   - Suppresses the banner if the user dismissed this specific tag
 *     ([UserPrefs.dismissedUpdateTag]) or tapped "Later" and the snooze
 *     window hasn't passed ([UserPrefs.updateRemindAfterMs]).
 *
 * Call [runIfDue] once from [CalibrateSocApplication.onCreate] or a
 * top-level ViewModel. It returns immediately (fire-and-forget coroutine
 * started by the caller's scope).
 */
@Singleton
class AutoUpdateChecker @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val userPrefs: UserPrefs,
) {

    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)

    /**
     * Non-null when an update is available AND should be shown (not dismissed,
     * not snoozed). Collect this from the Scaffold or Dashboard composable to
     * show the app-level banner.
     */
    val pendingUpdate: StateFlow<UpdateInfo?> = _pendingUpdate.asStateFlow()

    /**
     * Performs the check if conditions are met. Must be called from a
     * coroutine on a background dispatcher (e.g. Dispatchers.IO via the
     * app's detached scope). Never throws — all errors are swallowed.
     */
    suspend fun runIfDue() {
        runCatching { runChecked() }
        // Silently swallow any unexpected error — never crash startup.
    }

    private suspend fun runChecked() {
        val enabled     = userPrefs.autoUpdateCheckEnabled.first()
        val lastCheckMs = userPrefs.lastUpdateCheckMs.first()
        val remindAfter = userPrefs.updateRemindAfterMs.first()
        val dismissed   = userPrefs.dismissedUpdateTag.first()

        if (!enabled) return

        val nowMs = System.currentTimeMillis()
        if (!AutoUpdateDecision.isDue(nowMs, lastCheckMs)) return

        val info = updateChecker.fetchLatest() ?: return  // offline or error — silent no-op

        // Always update the timestamp so we don't retry on the next launch
        // within the 24-hour window, even if nothing new was found.
        userPrefs.setLastUpdateCheckMs(nowMs)

        if (!info.isNewer) return

        // Evaluate display conditions against current prefs.
        if (!AutoUpdateDecision.shouldShow(
                nowMs = nowMs,
                remindAfterMs = remindAfter,
                dismissedTag = dismissed,
                incomingTag = info.tag,
            )
        ) return

        _pendingUpdate.value = info
    }

    // ── Banner action callbacks ───────────────────────────────────────────────

    /**
     * User tapped "Later" — snooze the banner for 7 days, but don't dismiss
     * this tag permanently (a second "Later" resets the snooze window).
     */
    suspend fun snooze() {
        val snoozeUntil = System.currentTimeMillis() + SNOOZE_DURATION_MS
        userPrefs.setUpdateRemindAfterMs(snoozeUntil)
        _pendingUpdate.value = null
    }

    /**
     * User tapped the dismiss (x) button — remember this tag so we don't nag
     * about it again. A newer release tag will still trigger the banner.
     */
    suspend fun dismiss() {
        val tag = _pendingUpdate.value?.tag ?: return
        userPrefs.setDismissedUpdateTag(tag)
        _pendingUpdate.value = null
    }

    /** Hide the banner immediately without persisting any preference
     *  (e.g. user navigated to Settings to start the update). */
    fun consume() {
        _pendingUpdate.value = null
    }

    companion object {
        /** Snooze duration for "Later": 7 days in milliseconds. */
        const val SNOOZE_DURATION_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * Pure, side-effect-free decision functions extracted so they can be
 * unit-tested without any Android or coroutine infrastructure.
 */
object AutoUpdateDecision {

    private const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000  // 24 hours

    /**
     * Returns true when a new check is warranted — i.e. more than 24 hours
     * have elapsed since [lastCheckMs]. A [lastCheckMs] of 0 means never
     * checked, so the first run is always due.
     */
    fun isDue(nowMs: Long, lastCheckMs: Long): Boolean =
        nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    /**
     * Returns true when all display conditions pass:
     *   1. The snooze window has expired ([nowMs] > [remindAfterMs]).
     *   2. The user hasn't dismissed exactly this release tag
     *      ([incomingTag] != [dismissedTag]).
     */
    fun shouldShow(
        nowMs: Long,
        remindAfterMs: Long,
        dismissedTag: String?,
        incomingTag: String,
    ): Boolean =
        nowMs > remindAfterMs && incomingTag != dismissedTag
}
