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
import io.github.mayusi.calibratesoc.data.boost.ServiceRevert
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

    /**
     * CRITICAL-1 (DEFECT B sibling): the [CapabilityReport] the daemon resolved this
     * session, hoisted to a field so EVERY exit path (stopDaemon, onDestroy, onTaskRemoved,
     * the runDaemon `finally`) can revert against it. Null before the daemon resolves
     * capabilities (nothing has been written, so nothing to revert).
     */
    @Volatile private var sessionReport: CapabilityReport? = null

    /**
     * CRITICAL-1: the idempotent, NonCancellable revert latch (one per session, rebuilt at
     * daemon start). GUARANTEES the cap revert survives the `loopJob.cancel()` that DEFECT B
     * silently swallowed (the inner PServer `withContext(Dispatchers.IO)` threw
     * CancellationException → revert skipped → cap pinned until reboot). Shared with
     * GameBoost via [ServiceRevert]. Lazily set so a never-started service has nothing to call.
     */
    private var revert: ServiceRevert? = null

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

    /**
     * CRITICAL-1: revert the pre-emptive cap to stock before teardown. The old onDestroy
     * cancelled the loop only — the loop's `finally` revert rode the cancelled job and was
     * silently skipped. Now we block here (the lifecycle callback must finish the revert
     * before the process can be reclaimed) and [ServiceRevert.revertNow] runs the writes
     * under NonCancellable so they land even though serviceScope is about to be cancelled.
     * Idempotent with stopDaemon / onTaskRemoved / the finally.
     */
    override fun onDestroy() {
        val report = sessionReport
        val revertHandle = revert
        if (report != null && revertHandle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { revertHandle.revertNow(report) }
            }.onFailure { Log.w(TAG, "onDestroy revert failed", it) }
        }
        loopJob?.cancel()
        loopJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * CRITICAL-1: when the user swipes the app away from Recents, Android may kill the
     * process without an orderly onDestroy in time to flush a cancelled-job revert. Revert
     * the cap to stock here too. Without this, a swipe-away mid-session could leave the
     * pre-emptive cap pinned. Idempotent.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val report = sessionReport
        val revertHandle = revert
        if (report != null && revertHandle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { revertHandle.revertNow(report) }
            }.onFailure { Log.w(TAG, "onTaskRemoved revert failed", it) }
        }
        super.onTaskRemoved(rootIntent)
    }

    // ── Daemon lifecycle ──────────────────────────────────────────────────────

    private fun startDaemon() {
        if (loopJob?.isActive == true) return
        // CRITICAL-1: a fresh single-session revert latch. Reset the session report so a stop
        // before capabilities resolve has nothing stale to revert against.
        sessionReport = null
        revert = ServiceRevert(tunableWriter)
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

        // CRITICAL (AYANEO crash fix, sibling of the AutoTDP writable-ceiling fix in
        // TdpCaps.from): bigPolicy.availableFreqsKhz is the RAW full kernel OPP table
        // (top 2 592 000 on AYANEO Pocket DS). On a constrained vendor write path
        // (AYANEO's `gamewindow` overlay, no root/PServer/chmod-direct) the vendor
        // REJECTS any scaling_max_freq above the stock ceiling (1 785 600) — targeting
        // above it is the same rejected-write storm that crashed `com.ayaneo.gamewindow`
        // via the AutoTDP idle path. Route through TdpCaps.from(report), the SAME
        // writable-bounded model AutoTdpEngine now uses, so the guard's stock ceiling
        // AND its OPP/snap table are both clamped to what this device actually accepts.
        // On Odin/RP6 (proven full-kernel write path) the writable ceiling == the
        // kernel top, so behavior is unregressed — the guard still reaches max.
        //
        // Actuate against caps.bigPolicyId, NOT the local `bigPolicy` above: on 2-cluster
        // devices (little+big — AYANEO, Odin 3, RP6) they're the same policy, but on a
        // 3+ cluster device TdpCaps.from's bigPolicyId is the gold/big policy (second
        // highest top OPP) while the local `bigPolicy` here is the prime policy (highest)
        // — mixing the two would pair the wrong cluster's write id with this ceiling/OPP
        // table. caps.bigPolicyId is the exact node AutoTdpEngine/AutoTdpService cap
        // (Tunables.cpuMaxFreq(caps.bigPolicyId)), so mirroring it here keeps the guard
        // and AutoTDP capping the identical node.
        val caps = io.github.mayusi.calibratesoc.data.autotdp.TdpCaps.from(report)
        val actuatedPolicy = report.cpuPolicies.firstOrNull { it.policyId == caps.bigPolicyId }
            ?: bigPolicy
        val stockCeilingKhz = caps.bigClusterWritableMaxKhz.takeIf { it > 0 }
            ?: (actuatedPolicy.availableFreqsKhz.maxOrNull() ?: actuatedPolicy.currentMaxKhz)
        val actuator = ThrottleGuardActuator(
            bigPolicyId = caps.bigPolicyId,
            stockCeilingKhz = stockCeilingKhz,
            // HIGH-2 + AYANEO fix: writable-bounded OPP steps for the cap (never the raw
            // kernel table), so the guard never writes a value the kernel will silently
            // clamp OR the vendor overlay will reject, and the shared 40% hard floor
            // below is snapped to a real, WRITABLE OPP.
            availableFreqsKhz = caps.bigClusterOppStepsKhz,
        )

        // CRITICAL-1: publish the resolved report so EVERY exit path (stopDaemon /
        // onDestroy / onTaskRemoved / the finally) can revert against it. From here on the
        // guard may write a cap, so from here on a revert is meaningful.
        sessionReport = report

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
            // CRITICAL-1 (DEFECT B sibling): route the revert through the NonCancellable
            // latch. This finally runs on the loopJob, which EVERY stop path cancels — the
            // old direct revertAll() rode that cancelled job and was silently skipped
            // (PServer's withContext(Dispatchers.IO) throws CancellationException), leaving
            // the pre-emptive cap pinned until reboot. revertNow runs under NonCancellable
            // so it lands; idempotent — a no-op if stopDaemon/onDestroy already reverted.
            Log.i(TAG, "Throttle Guard reverting all writes via TunableWriter (finally)")
            val summary = revert?.revertNow(report)
            if (summary != null) {
                Log.i(TAG, "Throttle Guard revert complete: ok=${summary.ok} failed=${summary.failed}")
            } else {
                Log.i(TAG, "Throttle Guard revert already performed by an earlier exit path (no-op)")
            }
            updateNotification("Stopped", "Cap reverted")
        }
    }

    /**
     * CRITICAL-1: stop the daemon and revert the cap to stock FIRST (NonCancellable, awaited)
     * THEN cancel the loop and stopSelf — so a write/revert race cannot leave the cap pinned.
     * The old body cancelled the loop and called stopSelf() WITHOUT awaiting a revert; the
     * only revert was the loop's `finally`, which rode the cancelled job and was skipped.
     */
    private fun stopDaemon(status: ThrottleGuardStatus) {
        controller.updateState(
            controller.state.value.copy(status = status, activeCapKhz = null)
        )
        val report = sessionReport
        val revertHandle = revert
        serviceScope.launch {
            if (report != null && revertHandle != null) {
                runCatching {
                    val summary = revertHandle.revertNow(report)
                    if (summary != null) {
                        Log.i(TAG, "stopDaemon revert: ok=${summary.ok} failed=${summary.failed}")
                    }
                }.onFailure { Log.w(TAG, "stopDaemon revert failed", it) }
            }
            // Now safe to cancel the loop (its finally revert is a latched no-op) + stop.
            loopJob?.cancel()
            loopJob = null
            stopSelf()
        }
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
