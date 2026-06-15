package io.github.mayusi.calibratesoc.data.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.R
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateful alert monitor. Wraps [TempAlertEvaluator] with hysteresis and
 * rate-limiting so the user isn't spammed with notifications every second.
 *
 * Hysteresis rules:
 *   - COLD → HOT  (fires): when hottestC >= threshold. Posts notification;
 *     optionally applies [UserPrefs.tempAlertAutoProfileId].
 *   - HOT  → COOL (resets): when hottestC < (threshold − HYSTERESIS_MARGIN_C).
 *     Resets the state machine so a new crossing can fire again.
 *   - HOT  → HOT  (suppressed): no re-fire while still above the cool band.
 *
 * Rate-limit: never fire more than once per [RATE_LIMIT_MS] even if the
 * temp keeps oscillating around the threshold.
 *
 * Integration: call [observe] with the telemetry flow from [MonitorService].
 * It collects for as long as the coroutine scope lives. The OverlayService
 * (which already collects that same flow for the HUD) calls this once in
 * its onCreate so alerts fire whenever the HUD is running. If the HUD is not
 * running — e.g. the user is just on the dashboard — the DashboardViewModel
 * also calls this (see TempAlertMonitor.observe usage in DashboardViewModel).
 *
 * v1 scope: alerts fire while monitoring is active (HUD open OR dashboard
 * open). There is no always-on background collector in the current code
 * base, so we hook into the existing collectors rather than adding a new
 * foreground service.
 */
@Singleton
class TempAlertMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPrefs,
    private val profileRepository: ProfileRepository,
    private val profileApplier: ProfileApplier,
    private val capabilityProbe: CapabilityProbe,
) {

    // ── State machine ─────────────────────────────────────────────────────────

    private enum class AlertState { COOL, HOT }

    private var state = AlertState.COOL
    private var lastFiredMs = 0L

    // ── Pref state flows ──────────────────────────────────────────────────────

    // Shared scope for converting DataStore flows to StateFlows. Using a
    // singleton scope (SupervisorJob + IO) means the StateFlows stay warm
    // for the lifetime of this @Singleton, so every call to observe() reads
    // the same hot flow rather than each spinning up its own collector.
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefsState: StateFlow<Triple<Boolean, Int, String?>> =
        combine(
            userPrefs.tempAlertsEnabled,
            userPrefs.tempAlertThresholdC,
            userPrefs.tempAlertAutoProfileId,
        ) { enabled, threshold, profileId ->
            Triple(enabled, threshold, profileId)
        }.stateIn(
            scope = monitorScope,
            started = SharingStarted.Eagerly,
            initialValue = Triple(false, HYSTERESIS_MARGIN_C + DEFAULT_ALERT_THRESHOLD_C, null),
        )

    // ── Notification channel ──────────────────────────────────────────────────

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Temperature alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifies you when your device gets too hot."
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Collect [telemetry] and fire alerts per the hysteresis + rate-limit
     * rules. This suspends for as long as the caller's coroutine scope
     * lives — intended to be launched with [kotlinx.coroutines.launch].
     *
     * BUG FIX (BUG 4): previously used collectLatest on the outer pref
     * combine, which cancelled + restarted the inner telemetry.collect on
     * every pref emission. This reset HOT alert state and dropped samples,
     * making alerts flap or fail to fire entirely. The fix: collect the
     * telemetry stream directly and read the latest pref values from a
     * hot StateFlow ([prefsState]) on every sample. A pref change now
     * takes effect on the very next telemetry tick without interrupting the
     * running collect — HOT state is preserved across pref updates unless
     * the threshold itself changes significantly.
     */
    suspend fun observe(telemetry: Flow<Telemetry>) {
        var lastThreshold: Int? = null
        telemetry.collect { sample ->
            val (enabled, threshold, autoProfileId) = prefsState.value
            // Reset the state machine whenever the threshold changes so the
            // new threshold doesn't latch onto old HOT state from the previous
            // setting — mirrors the previous per-settings-change reset.
            if (threshold != lastThreshold) {
                resetState()
                lastThreshold = threshold
            }
            if (!enabled) return@collect
            onSample(sample, threshold, autoProfileId)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun resetState() {
        state = AlertState.COOL
    }

    private suspend fun onSample(
        sample: Telemetry,
        thresholdC: Int,
        autoProfileId: String?,
    ) {
        val eval = TempAlertEvaluator.evaluate(sample, thresholdC)

        when (state) {
            AlertState.COOL -> {
                if (eval.tripped) {
                    // Crossing up — fire if not rate-limited.
                    val now = System.currentTimeMillis()
                    if (now - lastFiredMs >= RATE_LIMIT_MS) {
                        lastFiredMs = now
                        state = AlertState.HOT
                        fireAlert(eval, thresholdC, autoProfileId)
                    } else {
                        // Rate-limited: still transition to HOT so we
                        // don't re-fire immediately when the limit expires.
                        state = AlertState.HOT
                    }
                }
                // else: still cool, nothing to do
            }

            AlertState.HOT -> {
                val coolBand = thresholdC - HYSTERESIS_MARGIN_C
                if ((eval.hottestC ?: 0f) < coolBand) {
                    // Cooled down below the hysteresis band — reset.
                    state = AlertState.COOL
                }
                // else: still hot, suppress
            }
        }
    }

    private suspend fun fireAlert(
        eval: TempAlertEvaluation,
        thresholdC: Int,
        autoProfileId: String?,
    ) {
        val hottestStr = eval.hottestC?.let { "%.0f°C".format(it) } ?: "${thresholdC}°C"
        val sourceStr = eval.sourceLabel.replaceFirstChar { it.uppercase() }

        // Resolve auto-profile name (if any) BEFORE posting — async,
        // but we do it on the caller's IO dispatcher.
        val autoProfile = autoProfileId?.let { id ->
            profileRepository.snapshot().profiles.firstOrNull { it.id == id }
        }

        val titleText = "Device is hot — $hottestStr"
        val bodyText = buildString {
            append("$sourceStr sensor hit $hottestStr. Consider pausing or reducing load.")
            if (autoProfile != null) {
                append(" Switching to \"${autoProfile.name}\" to cool down.")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Apply the auto-switch profile if configured.
        if (autoProfile != null) {
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            profileApplier.apply(
                preset = autoProfile.toPreset(),
                report = report,
                reason = "temp-alert: $hottestStr >= ${thresholdC}°C",
            )
        }
    }

    companion object {
        /** Notification channel for alerts (distinct from the HUD service channel). */
        const val CHANNEL_ID = "temp_alerts"

        /** Notification ID — reused so each new alert replaces the previous one. */
        private const val NOTIFICATION_ID = 5050

        /** How many degrees below the threshold the temp must drop before
         *  we allow a new alert to fire (prevents flapping on the boundary). */
        const val HYSTERESIS_MARGIN_C = 3

        /** Minimum time between any two alert notifications (ms). */
        const val RATE_LIMIT_MS = 60_000L

        /** Default alert threshold in °C — matches [UserPrefs.DEFAULT_ALERT_THRESHOLD_C].
         *  Duplicated here so the initial StateFlow value is correct before
         *  DataStore has emitted its first value. */
        private const val DEFAULT_ALERT_THRESHOLD_C = 80
    }
}
