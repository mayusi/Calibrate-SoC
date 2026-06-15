package io.github.mayusi.calibratesoc.data.autotdp

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
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * AutoTDP LIVE daemon — foreground Service (LIVE rung of the 3-rung design).
 *
 * Mirrors [OverlayService] / MonitorService lifecycle:
 *   - [onCreate] → startForeground + capability gate → start loop
 *   - [onStartCommand] → ACTION_START / ACTION_STOP
 *   - [onDestroy]  → revert ALL writes via TunableWriter.revertAll
 *
 * LIVE AVAILABILITY GATE:
 *   On startup this service checks whether the sysfs nodes it needs to write
 *   are actually writable ([CapabilityReport.sysfsDirectlyWritable] or ROOT tier).
 *   If they are NOT, the service updates state to [AutoTdpStatus.LIVE_UNAVAILABLE]
 *   with a reason string and calls [stopSelf] — the UI routes to SCRIPT/ADVISORY.
 *   We never pretend to live-tune when writes are denied.
 *
 * SAFETY:
 *   - cpu0 is asserted-not-parked in [TdpStateTransition.delta] before every write.
 *   - Temperature kill: if any zone exceeds [TEMP_KILL_MILLI_C] we stop.
 *   - Battery kill: if [batteryDrawMilliW] shows charge below [BATTERY_KILL_PCT_APPROX]
 *     (approximated from current UA sign flip) we stop. Proper pct check needs
 *     BatteryManager — we use the sign flip as a conservative proxy.
 *   - Write failure: if TunableWriter returns anything other than Success,
 *     we stop and surface the failure honestly.
 *   - Stop → [TunableWriter.revertAll] restores every written node.
 *
 * State is exposed via [stateFlow] (a [StateFlow<AutoTdpRunState>]).
 * [AutoTdpController] wraps this and is the clean API surface for the UI.
 */
@AndroidEntryPoint
class AutoTdpService : Service() {

    @Inject lateinit var monitorService: MonitorService
    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var tunableWriter: TunableWriter
    @Inject lateinit var controller: AutoTdpController
    @Inject lateinit var writerRegistry: io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    /** Battery read-out to approximate low-battery detection. */
    private var batteryManager: android.os.BatteryManager? = null

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileOrdinal = intent.getIntExtra(EXTRA_PROFILE_ORDINAL, AutoTdpProfile.BALANCED.ordinal)
                val targetMw = intent.getLongExtra(EXTRA_TARGET_MW, -1L).let { if (it < 0) null else it }
                val profile = AutoTdpProfile.entries.getOrElse(profileOrdinal) { AutoTdpProfile.BALANCED }
                val config = AutoTdpProfileConfig(profile = profile, targetMilliWatts = targetMw)
                startDaemon(config)
            }
            ACTION_STOP -> {
                stopDaemon(AutoTdpStatus.STOPPED, killReason = null)
            }
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

    private fun startDaemon(config: AutoTdpProfileConfig) {
        if (loopJob?.isActive == true) return // already running

        loopJob = serviceScope.launch {
            withContext(Dispatchers.IO) {
                runDaemon(config)
            }
        }
    }

    /**
     * Main daemon body. Runs on [Dispatchers.IO].
     *
     * Steps:
     *   1. Resolve CapabilityReport.
     *   2. Check LIVE availability — if denied, emit state + stop.
     *   3. Build TdpCaps.
     *   4. Collect telemetry, maintain rolling window, call AutoTdpEngine.decide.
     *   5. Apply delta via TunableWriter.
     *   6. On exit (any path), revertAll.
     */
    private suspend fun runDaemon(config: AutoTdpProfileConfig) {
        // ── Step 1: resolve capabilities ──────────────────────────────────────
        // Always refresh rather than using the cached value so that
        // pserverSysfsLive / sysfsDirectlyWritable reflect the CURRENT
        // device state at daemon-start time. A stale report could have
        // pserverSysfsLive=false (probed before the whitelist step was
        // run) and cause the availability gate to reject a device that
        // is actually writable.
        val report: CapabilityReport = capabilityProbe.refresh()

        // ── Step 2: LIVE availability gate ────────────────────────────────────
        val liveReason = liveUnavailableReason(report)
        if (liveReason != null) {
            Log.w(TAG, "LIVE not available: $liveReason")
            controller.updateState(
                AutoTdpRunState(
                    status = AutoTdpStatus.LIVE_UNAVAILABLE,
                    liveAvailable = false,
                    liveUnavailableReason = liveReason,
                )
            )
            stopSelf()
            return
        }

        // ── Step 3: build device caps ──────────────────────────────────────────
        val caps = TdpCaps.from(report)
        val gpuRootPath = report.gpu?.rootPath

        Log.i(TAG, "Starting LIVE daemon: profile=${config.profile}, caps=$caps")
        controller.updateState(
            AutoTdpRunState(
                status = AutoTdpStatus.RUNNING,
                liveAvailable = true,
            )
        )
        updateNotification(status = "Running", detail = "")

        // ── Step 4: collect telemetry + control loop ───────────────────────────
        val window = ArrayDeque<Telemetry>(WINDOW_SIZE + 1)
        var currentState = TdpState.STOCK

        try {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                // Roll the window.
                window.addLast(sample)
                if (window.size > WINDOW_SIZE) window.removeFirst()

                // ── Safety checks ──────────────────────────────────────────────
                val tempKill = checkTempKill(sample)
                if (tempKill != null) {
                    Log.w(TAG, "Temp kill: $tempKill")
                    stopDaemon(AutoTdpStatus.KILLED_BY_SAFETY, killReason = tempKill)
                    return@collect
                }
                val battKill = checkBatteryKill()
                if (battKill != null) {
                    Log.w(TAG, "Battery kill: $battKill")
                    stopDaemon(AutoTdpStatus.KILLED_BY_SAFETY, killReason = battKill)
                    return@collect
                }

                // ── Engine decision ────────────────────────────────────────────
                val decision = AutoTdpEngine.decide(
                    window = window.toList(),
                    config = config,
                    caps = caps,
                    current = currentState,
                )

                // ── Apply delta ────────────────────────────────────────────────
                if (decision.target != currentState) {
                    val ops = TdpStateTransition.delta(
                        from = currentState,
                        to = decision.target,
                        bigPolicyId = caps.bigPolicyId,
                        gpuRootPath = gpuRootPath,
                    )

                    for (op in ops) {
                        val result = tunableWriter.write(
                            id = op.id,
                            value = op.value,
                            report = report,
                            reason = "AutoTDP: ${op.description}",
                        )
                        when (result) {
                            is WriteResult.Success -> {
                                // Good — continue to next op.
                            }
                            is WriteResult.CapabilityDenied -> {
                                // CapabilityDenied means WriterRegistry resolved to NoopWriter —
                                // no tier can write this node. This is genuinely unrecoverable
                                // for the current capability state: stop the daemon so the user
                                // isn't left with a "Running" notification that does nothing.
                                val failure = "No write tier available for ${op.description}: ${result.reason}"
                                Log.e(TAG, failure)
                                stopDaemon(AutoTdpStatus.WRITE_DENIED, writeFailure = failure)
                                return@collect
                            }
                            is WriteResult.Rejected,
                            is WriteResult.Failed -> {
                                // Rejected / Failed: the tier was tried (PServer or libsu) but
                                // the write didn't land. This could be transient (binder hiccup,
                                // EBUSY from a governor protecting an OPP). Log it and stop —
                                // we don't retry here because re-trying a Rejected write in a
                                // tight loop would spam logcat and potentially corrupt the clock
                                // state. Stopping cleanly and letting the user restart is safer.
                                val failure = "Write failed for ${op.description}: $result"
                                Log.e(TAG, failure)
                                stopDaemon(AutoTdpStatus.WRITE_DENIED, writeFailure = failure)
                                return@collect
                            }
                        }
                    }
                    currentState = decision.target
                }

                Log.v(TAG, "decision: ${decision.reason}")
                controller.updateState(
                    controller.state.value.copy(
                        status = AutoTdpStatus.RUNNING,
                        lastReason = decision.reason,
                        appliedState = currentState,
                    )
                )
                updateNotification(status = "Running", detail = decision.reason)
            }
        } finally {
            // ── Revert all writes (CRITICAL) ───────────────────────────────────
            Log.i(TAG, "Reverting all AutoTDP writes via TunableWriter")
            val summary = tunableWriter.revertAll(report)
            Log.i(TAG, "Revert complete: ok=${summary.ok} failed=${summary.failed}")
            // Update notification if service is still technically alive
            updateNotification(status = "Stopped", detail = "All writes reverted")
        }
    }

    private fun stopDaemon(
        status: AutoTdpStatus,
        killReason: String? = null,
        writeFailure: String? = null,
    ) {
        val current = controller.state.value
        controller.updateState(
            current.copy(
                status = status,
                killReason = killReason,
                writeFailure = writeFailure,
            )
        )
        loopJob?.cancel()
        loopJob = null
        stopSelf()
    }

    // ── Safety checks ─────────────────────────────────────────────────────────

    /**
     * Returns a kill reason if any thermal zone exceeds [TEMP_KILL_MILLI_C].
     * Skin/CPU/GPU zones all apply — we use the zone max rather than a
     * specific zone so this works across device topologies.
     */
    private fun checkTempKill(sample: Telemetry): String? {
        val hotZone = sample.zoneTempsMilliC.maxByOrNull { it.tempMilliC } ?: return null
        return if (hotZone.tempMilliC >= TEMP_KILL_MILLI_C) {
            "Thermal kill: ${hotZone.label} ${hotZone.tempMilliC / 1000}°C ≥ ${TEMP_KILL_MILLI_C / 1000}°C"
        } else null
    }

    /**
     * Returns a kill reason if battery is critically low.
     * Uses [android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY] for an integer %.
     */
    private fun checkBatteryKill(): String? {
        val pct = batteryManager?.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        ) ?: return null // null = can't read, don't kill
        return if (pct in 1 until BATTERY_KILL_PCT) {
            "Battery kill: $pct% < $BATTERY_KILL_PCT%"
        } else null
    }

    // ── LIVE availability gate ─────────────────────────────────────────────────

    /**
     * Returns a human-readable reason if LIVE writes are NOT available, or null
     * if LIVE is usable. We check the two critical node families:
     *   - cpu$N/online (core parking)
     *   - policy$N/scaling_max_freq (big-cluster cap)
     *
     * We use [WriterRegistry.isLiveWritable] because it encodes the full
     * tier-resolution logic including the Shizuku per-node probe cache. If a node
     * passed the shell-UID probe it is considered live-writable here; if it failed
     * (vendor SELinux denial) the Shizuku tier is honestly reported unavailable
     * for that node family and the daemon does not start.
     *
     * NOTE: on the Shizuku tier, liveUnavailableReason returning null means at
     * LEAST the probe-candidate nodes passed. The daemon will write only probed
     * nodes; the per-write gate in ShizukuWriter provides a second honesty check.
     */
    private fun liveUnavailableReason(report: CapabilityReport): String? {
        // Check cpu0/online as a proxy for all cpu$N/online nodes (same privilege).
        // cpu0 is never parked, but its writability tells us if the cpu/online family
        // is accessible. If it's not, parking any other core will fail too.
        val onlineId = Tunables.cpuOnline(0)
        if (!writerRegistry.isLiveWritable(onlineId, report)) {
            val why = Tunables.whyWriteDenied(onlineId, report)
            return "cpu online/offline not writable — ${why ?: "writer would deny"}"
        }
        // Check the first policy's scaling_max_freq.
        val bigPolicy = report.cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }
        if (bigPolicy != null) {
            val freqId = Tunables.cpuMaxFreq(bigPolicy.policyId)
            if (!writerRegistry.isLiveWritable(freqId, report)) {
                val why = Tunables.whyWriteDenied(freqId, report)
                return "scaling_max_freq not writable — ${why ?: "writer would deny"}"
            }
        }
        return null // all clear — both critical node families have a live writer
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoTDP",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while AutoTDP live daemon is active."
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
            .setContentTitle("AutoTDP — $status")
            .setContentText(detail.ifBlank { "Dynamic power management active." })
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    // ── Companions ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AutoTdpService"
        private const val NOTIFICATION_ID = 5511
        private const val CHANNEL_ID = "autotdp"

        /** Rolling telemetry window depth (matches design spec: ~4 samples). */
        private const val WINDOW_SIZE = 4

        /**
         * Thermal kill threshold in milli-°C. 95 000 = 95 °C — a conservative
         * but safe ceiling for sustained operation on phone-derived SoCs.
         */
        private const val TEMP_KILL_MILLI_C = 95_000

        /**
         * Battery percentage below which the daemon stops. 5% is the same
         * floor the benchmark runner uses — leaves enough headroom for the OS
         * to save state cleanly.
         */
        private const val BATTERY_KILL_PCT = 5

        // Intent actions
        const val ACTION_START = "io.github.mayusi.calibratesoc.AUTOTDP_START"
        const val ACTION_STOP  = "io.github.mayusi.calibratesoc.AUTOTDP_STOP"

        // Extras
        const val EXTRA_PROFILE_ORDINAL = "profile_ordinal"
        const val EXTRA_TARGET_MW       = "target_mw"

        fun start(context: Context, config: AutoTdpProfileConfig) {
            val intent = Intent(context, AutoTdpService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ORDINAL, config.profile.ordinal)
                config.targetMilliWatts?.let { putExtra(EXTRA_TARGET_MW, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AutoTdpService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
