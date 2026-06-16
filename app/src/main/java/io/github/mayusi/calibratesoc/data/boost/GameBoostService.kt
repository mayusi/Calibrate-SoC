package io.github.mayusi.calibratesoc.data.boost

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
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpService
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.devicedb.FanAdapterKind
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Game Boost LIVE daemon — foreground Service that brute-pins the SoC to its
 * ceiling for raw FPS, time-boxed and thermally guarded, fully reverted on stop.
 *
 * Mirrors [AutoTdpService] (its scaffolding, notification, LIVE gate, and revert
 * journal pattern) but with a different intent: AutoTDP *optimises* (caps clocks);
 * Game Boost *brute-maxes* (pins clocks to the ceiling).
 *
 * ## Lifecycle
 *   - [onCreate] → startForeground.
 *   - [onStartCommand] → ACTION_START (with [GameBoostConfig]) / ACTION_STOP.
 *   - [runDaemon] → LIVE gate → acquire arbiter (stop AutoTDP if it owns clocks) →
 *     apply brute-max bundle → telemetry loop ([BoostGuard]: time-box + thermal trip)
 *     → on any exit: revertAll + release arbiter.
 *
 * ## Mutual exclusion (sacred)
 *   Game Boost and AutoTDP both write the big-cluster clocks, so they are MUTUALLY
 *   EXCLUSIVE. On start, this service [BoostArbiter.acquire]s GAME_BOOST; if AutoTDP
 *   owned the clocks, it cleanly stops AutoTDP first (which reverts AutoTDP's writes).
 *   The arbiter also suppresses the Predictive Throttle Guard while we boost.
 *
 * ## Safety
 *   - cpu0 is never offlined (Game Boost never parks cores — it keeps all online).
 *   - Thermal trip via [BoostGuard] reusing [ThermalKillEvaluator] (same threshold +
 *     debounce + grace as AutoTDP) → revertAll + stop.
 *   - Time box → revertAll + stop (the EXPECTED end).
 *   - Any node not writable on this firmware is honestly skipped (never faked).
 *   - Stop / timeout / thermal-trip → [TunableWriter.revertAll].
 */
@AndroidEntryPoint
class GameBoostService : Service() {

    @Inject lateinit var monitorService: MonitorService
    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var tunableWriter: TunableWriter
    @Inject lateinit var controller: GameBoostController
    @Inject lateinit var writerRegistry: WriterRegistry
    @Inject lateinit var pServerWriter: PServerWriter
    @Inject lateinit var deviceAdapterRegistry: DeviceAdapterRegistry
    @Inject lateinit var arbiter: BoostArbiter
    @Inject lateinit var autoTdpController: AutoTdpController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var batteryManager: android.os.BatteryManager? = null

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val minutes = intent.getIntExtra(EXTRA_TIME_BOX_MIN, GameBoostConfig.DEFAULT_TIME_BOX_MINUTES)
                val fanSport = intent.getBooleanExtra(EXTRA_FAN_SPORT, true)
                val config = GameBoostConfig(timeBoxMinutes = minutes, setFanSport = fanSport).normalized()
                startDaemon(config)
            }
            ACTION_STOP -> stopDaemon(GameBoostStatus.STOPPED)
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

    private fun startDaemon(config: GameBoostConfig) {
        if (loopJob?.isActive == true) return
        loopJob = serviceScope.launch {
            withContext(Dispatchers.IO) { runDaemon(config) }
        }
    }

    private suspend fun runDaemon(config: GameBoostConfig) {
        // ── Resolve capabilities (bust PServer cache first, same as AutoTDP) ──────
        pServerWriter.invalidateTransactableCache()
        val report: CapabilityReport = capabilityProbe.refresh()

        // ── LIVE availability gate ────────────────────────────────────────────────
        val liveReason = liveUnavailableReason(report)
        if (liveReason != null) {
            Log.w(TAG, "Game Boost LIVE not available: $liveReason")
            controller.updateState(
                GameBoostState(
                    status = GameBoostStatus.LIVE_UNAVAILABLE,
                    liveAvailable = false,
                    liveUnavailableReason = liveReason,
                )
            )
            stopSelf()
            return
        }

        // ── MUTUAL EXCLUSION: claim clock ownership; stop AutoTDP if it owns it ───
        val acquire = arbiter.acquire(BoostArbiter.ClockOwner.GAME_BOOST)
        if (acquire.mustStopPreviousOwner == BoostArbiter.ClockOwner.AUTO_TDP) {
            Log.i(TAG, "Game Boost stopping AutoTDP before boosting (mutual exclusion)")
            // Stop AutoTDP cleanly — it reverts its own writes via its own revert journal.
            autoTdpController.stop()
            AutoTdpService.stop(this)
            // Give AutoTDP a moment to revert before we pin (its revert + our pin must
            // not interleave). A short settle is enough; both go through TunableWriter.
            delay(ARBITER_SETTLE_MS)
        }

        // ── Resolve the vendor fan key (same path AutoTDP uses) ───────────────────
        val fanModeKey: String? = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            ?.fanAdapter
            ?.takeIf { it.kind == FanAdapterKind.SETTINGS_KEY }
            ?.target

        // ── Compose + apply the brute-max bundle ──────────────────────────────────
        val bundle = GameBoostBundle.build(
            report = report,
            fanModeKey = fanModeKey,
            setFanSport = config.setFanSport,
        )
        val sessionStartEpochMs = System.currentTimeMillis()
        val expiresAt = sessionStartEpochMs + config.timeBoxMillis

        Log.i(TAG, "Game Boost applying ${bundle.size} pins (timeBox=${config.timeBoxMinutes}m)")
        var pinned = 0
        val skipped = mutableListOf<String>()
        for (op in bundle) {
            when (val result = tunableWriter.write(
                id = op.id,
                value = op.value,
                report = report,
                reason = "GameBoost: ${op.description}",
            )) {
                is WriteResult.Success -> pinned++
                is WriteResult.CapabilityDenied -> {
                    // No write tier for this node on this firmware — honest skip, keep going.
                    Log.w(TAG, "Game Boost skip (denied): ${op.description}: ${result.reason}")
                    skipped += op.id.target
                }
                is WriteResult.Rejected -> {
                    Log.w(TAG, "Game Boost skip (rejected): ${op.description}: ${result.message}")
                    skipped += op.id.target
                }
                is WriteResult.Failed -> {
                    Log.w(TAG, "Game Boost skip (failed): ${op.description}: ${result.error.message}")
                    skipped += op.id.target
                }
            }
        }

        // If literally nothing landed, this device can't boost — revert (no-op) + stop honestly.
        if (pinned == 0) {
            Log.w(TAG, "Game Boost pinned 0 nodes — nothing writable; stopping")
            controller.updateState(
                GameBoostState(
                    status = GameBoostStatus.WRITE_DENIED,
                    liveAvailable = true,
                    writeFailure = "No boost nodes were writable on this firmware.",
                    skippedNodes = skipped,
                )
            )
            // Revert any partial writes (defensive) + release ownership.
            tunableWriter.revertAll(report)
            arbiter.release(BoostArbiter.ClockOwner.GAME_BOOST)
            stopSelf()
            return
        }

        controller.updateState(
            GameBoostState(
                status = GameBoostStatus.BOOSTING,
                liveAvailable = true,
                sessionStartEpochMs = sessionStartEpochMs,
                timeBoxExpiresEpochMs = expiresAt,
                pinnedNodeCount = pinned,
                skippedNodes = skipped,
            )
        )
        updateNotification("Boosting", "Max performance — pinned $pinned nodes")

        // ── Telemetry loop: time-box + thermal trip via BoostGuard ────────────────
        val guard = BoostGuard(
            timeBoxMillis = config.timeBoxMillis,
            sessionStartEpochMs = sessionStartEpochMs,
        )
        try {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                val now = System.currentTimeMillis()
                val hottestC = sample.zoneTempsMilliC.maxByOrNull { it.tempMilliC }
                    ?.tempMilliC?.let { it / 1000f }

                val decision = guard.evaluate(now, sample)
                when (decision.stop) {
                    BoostGuard.BoostStop.THERMAL -> {
                        Log.w(TAG, "Game Boost thermal trip: ${decision.reason}")
                        stopDaemon(GameBoostStatus.THERMAL_TRIPPED, thermalTripReason = decision.reason)
                        return@collect
                    }
                    BoostGuard.BoostStop.TIME_BOX -> {
                        Log.i(TAG, "Game Boost time box expired: ${decision.reason}")
                        stopDaemon(GameBoostStatus.TIME_BOX_EXPIRED)
                        return@collect
                    }
                    BoostGuard.BoostStop.NONE -> {
                        // Keep boosting — refresh the live temp for the HUD.
                        controller.updateState(
                            controller.state.value.copy(lastHottestTempC = hottestC)
                        )
                    }
                }
            }
        } finally {
            Log.i(TAG, "Game Boost reverting all writes via TunableWriter")
            val summary = tunableWriter.revertAll(report)
            Log.i(TAG, "Game Boost revert complete: ok=${summary.ok} failed=${summary.failed}")
            arbiter.release(BoostArbiter.ClockOwner.GAME_BOOST)
            updateNotification("Stopped", "All writes reverted")
        }
    }

    private fun stopDaemon(
        status: GameBoostStatus,
        thermalTripReason: String? = null,
    ) {
        val current = controller.state.value
        controller.updateState(
            current.copy(status = status, thermalTripReason = thermalTripReason)
        )
        loopJob?.cancel()
        loopJob = null
        stopSelf()
    }

    // ── LIVE availability gate ─────────────────────────────────────────────────

    /**
     * Returns a reason string if LIVE writes are not available, or null if usable.
     * Game Boost pins scaling_max_freq on the big cluster, so we gate on the SAME
     * critical family AutoTDP does (scaling_max_freq writability) — if that is not
     * live-writable, no boost can land. We do NOT gate on cpu online (boost never
     * offlines cores), unlike AutoTDP.
     */
    private fun liveUnavailableReason(report: CapabilityReport): String? {
        val bigPolicy = report.cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }
            ?: return "no CPU policies discovered — nothing to boost"
        val freqId = Tunables.cpuMaxFreq(bigPolicy.policyId)
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
                CHANNEL_ID, "Game Boost", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Game Boost max-performance mode is active."
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
            .setContentTitle("Game Boost — $status")
            .setContentText(detail.ifBlank { "Max performance. Time-boxed; plug in recommended." })
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val TAG = "GameBoostService"
        private const val NOTIFICATION_ID = 5522
        private const val CHANNEL_ID = "gameboost"

        /** Settle time after stopping AutoTDP before we pin (revert vs pin must not race). */
        private const val ARBITER_SETTLE_MS = 250L

        const val ACTION_START = "io.github.mayusi.calibratesoc.GAMEBOOST_START"
        const val ACTION_STOP = "io.github.mayusi.calibratesoc.GAMEBOOST_STOP"
        const val EXTRA_TIME_BOX_MIN = "time_box_min"
        const val EXTRA_FAN_SPORT = "fan_sport"

        fun start(context: Context, config: GameBoostConfig) {
            val c = config.normalized()
            val intent = Intent(context, GameBoostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TIME_BOX_MIN, c.timeBoxMinutes)
                putExtra(EXTRA_FAN_SPORT, c.setFanSport)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GameBoostService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
