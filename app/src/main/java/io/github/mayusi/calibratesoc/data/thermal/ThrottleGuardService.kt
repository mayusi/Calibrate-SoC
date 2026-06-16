package io.github.mayusi.calibratesoc.data.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.R
import io.github.mayusi.calibratesoc.data.boost.BoostArbiter
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Predictive Throttle Guard LIVE daemon — the live consumer that finally makes the
 * dormant [PredictiveThrottleGuard] forecaster ACT.
 *
 * It feeds [MonitorService] telemetry into the pure forecaster and, when the forecast
 * says a kernel throttle cliff is imminent, applies a gentle PRE-EMPTIVE big-cluster
 * cap via [TunableWriter] so FPS degrades smoothly instead of dropping off a cliff.
 * When the forecast clears, it reverts the cap. On stop, [TunableWriter.revertAll]
 * restores everything (the guaranteed backstop).
 *
 * ## Relationship to AutoTDP / Game Boost (the honest design)
 *
 * This guard is a SAFETY ASSIST and a STANDALONE toggle. It is AUTO-SUPPRESSED while
 * AutoTDP or Game Boost owns the clocks (they already do their own thermal management),
 * so it never fights them. Suppression is observed via [BoostArbiter.throttleGuardSuppressed]:
 * while suppressed, the service reverts its own cap and applies nothing, but keeps the
 * loop alive so it resumes automatically the moment the owner stops.
 *
 * It is NOT a [BoostArbiter] exclusive owner — it is the lighter pre-emptive layer that
 * yields to the exclusive owners.
 *
 * ## Safety
 *  - Same machinery as AutoTDP: snapshot + readback-verify + revert, cpu0 sacred
 *    (the guard only writes big-cluster scaling_max_freq, never cpu0/online).
 *  - Honest skip: if scaling_max_freq is not live-writable, the service self-stops.
 */
@AndroidEntryPoint
class ThrottleGuardService : Service() {

    @Inject lateinit var monitorService: MonitorService
    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var tunableWriter: TunableWriter
    @Inject lateinit var controller: ThrottleGuardController
    @Inject lateinit var writerRegistry: WriterRegistry
    @Inject lateinit var pServerWriter: PServerWriter
    @Inject lateinit var arbiter: BoostArbiter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDaemon()
            ACTION_STOP -> stopDaemon(ThrottleGuardStatus.STOPPED)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Daemon lifecycle ──────────────────────────────────────────────────────

    private fun startDaemon() {
        if (loopJob?.isActive == true) return
        loopJob = serviceScope.launch {
            withContext(Dispatchers.IO) { runDaemon() }
        }
    }

    private suspend fun runDaemon() {
        // ── Resolve capabilities + LIVE gate ──────────────────────────────────────
        pServerWriter.invalidateTransactableCache()
        val report: CapabilityReport = capabilityProbe.refresh()

        val bigPolicy = report.cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }
        val liveReason = liveUnavailableReason(report, bigPolicy?.policyId)
        if (liveReason != null || bigPolicy == null) {
            val reason = liveReason ?: "no CPU policies discovered — nothing to guard"
            Log.w(TAG, "Throttle Guard LIVE not available: $reason")
            controller.updateState(
                ThrottleGuardState(
                    status = ThrottleGuardStatus.LIVE_UNAVAILABLE,
                    liveAvailable = false,
                    liveUnavailableReason = reason,
                )
            )
            stopSelf()
            return
        }

        val stockCeilingKhz = bigPolicy.availableFreqsKhz.maxOrNull()
            ?: bigPolicy.currentMaxKhz
        val actuator = ThrottleGuardActuator(
            bigPolicyId = bigPolicy.policyId,
            stockCeilingKhz = stockCeilingKhz,
        )

        controller.updateState(
            ThrottleGuardState(
                status = ThrottleGuardStatus.RUNNING,
                liveAvailable = true,
                suppressed = arbiter.throttleGuardSuppressed.value,
            )
        )
        updateNotification("Watching", "Pre-emptive throttle guard active")

        // ── Telemetry → forecast → actuate ────────────────────────────────────────
        val window = ArrayDeque<PredictiveThrottleGuard.TelemetryPoint>(WINDOW_CAP + 1)
        try {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                val suppressed = arbiter.throttleGuardSuppressed.value

                // Maintain the rolling thermal window from live telemetry.
                window.addLast(PredictiveThrottleGuard.TelemetryPoint.from(sample))
                while (window.size > WINDOW_CAP) window.removeFirst()

                // Forecast (pure). When suppressed we still compute it for the HUD, but
                // the actuator will refuse to apply and will revert any active cap.
                val forecast = PredictiveThrottleGuard.predict(window.toList())
                val action = actuator.decide(forecast, suppressed = suppressed)

                // Apply the single big-cluster write the actuator asked for, if any.
                action.write?.let { w ->
                    when (val result = tunableWriter.write(
                        id = w.id,
                        value = w.value,
                        report = report,
                        reason = "ThrottleGuard: ${w.description}",
                    )) {
                        is WriteResult.Success -> {
                            // Good — cap applied/reverted; readback-verify is inside the writer.
                        }
                        is WriteResult.CapabilityDenied -> {
                            // Lost the live tier mid-run — surface honestly, keep watching.
                            Log.w(TAG, "ThrottleGuard write denied: ${result.reason}")
                            controller.updateState(
                                controller.state.value.copy(writeFailure = result.reason)
                            )
                        }
                        is WriteResult.Rejected ->
                            Log.w(TAG, "ThrottleGuard write rejected: ${result.message}")
                        is WriteResult.Failed ->
                            Log.w(TAG, "ThrottleGuard write failed: ${result.error.message}")
                    }
                }

                controller.updateState(
                    controller.state.value.copy(
                        status = ThrottleGuardStatus.RUNNING,
                        suppressed = suppressed,
                        lastForecastReason = forecast.reason,
                        willThrottleInSec = forecast.willThrottleInSec,
                        activeCapKhz = action.activeCapKhz,
                    )
                )
                val detail = when {
                    suppressed -> "Standing down (AutoTDP/Boost active)"
                    action.activeCapKhz != null -> "Pre-emptive cap: ${action.activeCapKhz!! / 1000} MHz"
                    else -> "Watching — no throttle predicted"
                }
                updateNotification("Watching", detail)
            }
        } finally {
            Log.i(TAG, "Throttle Guard reverting all writes via TunableWriter")
            val summary = tunableWriter.revertAll(report)
            Log.i(TAG, "Throttle Guard revert complete: ok=${summary.ok} failed=${summary.failed}")
            updateNotification("Stopped", "Cap reverted")
        }
    }

    private fun stopDaemon(status: ThrottleGuardStatus) {
        controller.updateState(
            controller.state.value.copy(status = status, activeCapKhz = null)
        )
        loopJob?.cancel()
        loopJob = null
        stopSelf()
    }

    // ── LIVE availability gate ─────────────────────────────────────────────────

    private fun liveUnavailableReason(report: CapabilityReport, bigPolicyId: Int?): String? {
        if (bigPolicyId == null) return "no CPU policies discovered — nothing to guard"
        val freqId = Tunables.cpuMaxFreq(bigPolicyId)
        if (!writerRegistry.isLiveWritable(freqId, report)) {
            val why = Tunables.whyWriteDenied(freqId, report)
            return "scaling_max_freq not writable — ${why ?: "writer would deny"}"
        }
        return null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Throttle Guard", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the predictive throttle guard is active."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting…", ""),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…", ""))
        }
    }

    private fun updateNotification(status: String, detail: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status, detail))
    }

    private fun buildNotification(status: String, detail: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Throttle Guard — $status")
            .setContentText(detail.ifBlank { "Predicting thermal throttle to smooth FPS." })
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val TAG = "ThrottleGuardService"
        private const val NOTIFICATION_ID = 5533
        private const val CHANNEL_ID = "throttleguard"

        /** Rolling thermal window depth. ~8 samples at 1 Hz = 8 s of trend history. */
        private const val WINDOW_CAP = 8

        const val ACTION_START = "io.github.mayusi.calibratesoc.THROTTLEGUARD_START"
        const val ACTION_STOP = "io.github.mayusi.calibratesoc.THROTTLEGUARD_STOP"

        fun start(context: Context) {
            val intent = Intent(context, ThrottleGuardService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ThrottleGuardService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
