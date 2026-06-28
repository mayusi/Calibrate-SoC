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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 *   - Temperature kill: if any zone exceeds [ThermalKillEvaluator.KILL_THRESHOLD_MILLI_C]
 *     for [ThermalKillEvaluator.REQUIRED_CONSECUTIVE] consecutive samples, we stop.
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
    @Inject lateinit var sampler: AutoTdpSampler
    @Inject lateinit var writerRegistry: io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
    @Inject lateinit var pServerWriter: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
    /**
     * CRITICAL-1: the AYANEO vendor-binder availability cache must be busted right before
     * each `capabilityProbe.refresh()` — exactly as [pServerWriter]'s transactable cache is
     * — so a stale `true` from before a gamewindow force-stop/restart can't make the LIVE
     * gate claim live on a binder we can no longer drive. (It's a @Singleton; injected the
     * same way as PServerWriter.)
     */
    @Inject lateinit var ayaneoBinderClient: io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient
    @Inject lateinit var deviceAdapterRegistry: io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry

    /**
     * WAVE 3a: clock-ownership arbiter. AutoTDP and Game Boost both write the
     * big-cluster clocks, so they are MUTUALLY EXCLUSIVE — acquiring GAME_BOOST or
     * AUTO_TDP stops the other. Acquiring also suppresses the Predictive Throttle
     * Guard (it stands down while an exclusive owner manages thermals). See
     * [io.github.mayusi.calibratesoc.data.boost.BoostArbiter].
     */
    @Inject lateinit var arbiter: io.github.mayusi.calibratesoc.data.boost.BoostArbiter

    /** Used to stop Game Boost cleanly when AutoTDP starts while Boost owns clocks. */
    @Inject lateinit var gameBoostController: io.github.mayusi.calibratesoc.data.boost.GameBoostController

    /**
     * UNIT 1 (PER-GAME LEARNING): the per-package learned-parameter store. Read ONCE at
     * session start to seed the controller; written ONCE at session end (off the tick
     * thread) with the EWMA ratchet. SAFETY: a learned seed only sets the STARTING cap --
     * thermal pre-empt / kill / the 40% floor / NonCancellable revert all still run.
     */
    @Inject lateinit var learnedGameModel: LearnedGameModel

    /**
     * UNIT 4 (RICHER GOAL MODES): the persisted objective setpoints (fps floor / temp
     * ceiling / runtime hours). Snapshotted ONCE at session start into [sessionGoalParams]
     * so the tick loop reads a stable value object with no per-tick DataStore I/O.
     */
    @Inject lateinit var goalParamsPrefs: GoalParamsPrefs

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    /**
     * UNIT 4: the 60-second TARGET_RUNTIME outer-budget loop job. Separate from [loopJob]
     * (the 1 Hz tick loop) — it recomputes the runtime cap-ceiling every minute as the
     * battery drains. Cancelled alongside [loopJob] on every stop path.
     */
    private var runtimeBudgetJob: Job? = null

    /**
     * UNIT 4: the objective setpoints captured at session start (sanitized). Defaults to
     * [GoalParams.DEFAULT] so a session that never read prefs still has safe values.
     */
    @Volatile private var sessionGoalParams: GoalParams = GoalParams.DEFAULT

    /**
     * UNIT 4: the live TARGET_RUNTIME cap CEILING (kHz), recomputed by [runtimeBudgetJob]
     * every 60 s. Null = no runtime ceiling in force (fails OPEN to the band controller,
     * which is itself always safe). The per-tick loop reads this and clamps the band
     * controller's cap so it never loosens ABOVE the ceiling. @Volatile: written by the
     * outer loop, read by the tick loop.
     */
    @Volatile private var runtimeCapCeilingKhz: Int? = null

    /**
     * UNIT 4: the modelled TARGET_RUNTIME projection note (honesty-labelled) for the HUD.
     * Written by [runtimeBudgetJob]; surfaced to the run state by the tick loop.
     */
    @Volatile private var runtimeProjectionNote: String? = null

    /**
     * UNIT 4: the live TARGET_TEMP_CEILING cap CEILING (kHz), walked by the per-tick outer
     * guard as the smoothed die approaches/leaves the user ceiling. @Volatile mirrors the
     * runtime ceiling; null = no temp ceiling in force yet.
     */
    @Volatile private var tempCapCeilingKhz: Int? = null

    /**
     * UNIT 4: the most recent battery voltage (µV) + draw (mW) folded from the tick loop,
     * read by the 60s runtime-budget loop (which runs off the tick thread). Null until the
     * first sample carries them. Kept separate from the kill-path % read.
     */
    @Volatile private var lastBatteryVoltageUv: Long? = null
    @Volatile private var lastBatteryDrawMilliW: Long? = null

    /**
     * UNIT 4: an isolated cap → measured-draw (mW) collector for the runtime PowerModel
     * fit. Owned entirely by this service (NOT Unit 1's [SessionStatsAccumulator]); the
     * tick loop records (appliedCap, drawMw) at steady state and the 60s loop fits it.
     * A simple synchronized map (cross-thread). Latest draw per distinct cap wins.
     */
    private val capDrawSamples = java.util.Collections.synchronizedMap(LinkedHashMap<Int, Long>())

    /**
     * UNIT 1: the foreground game package resolved at session start (best-effort via
     * UsageStats). Null when usage access is denied / no game in foreground -- then the
     * session neither seeds nor learns (cold start, no fabrication).
     */
    @Volatile private var sessionPackage: String? = null

    /**
     * UNIT 1: the lightweight per-session stats accumulator. Built fresh at daemon start,
     * fed once per tick, converted to a [SessionOutcome] at session end. @Volatile because
     * the session-end write may run on a different coroutine than the tick loop.
     */
    @Volatile private var sessionStats: SessionStatsAccumulator? = null

    /**
     * UNIT 1: the big-cluster OPP table resolved at session start, captured so the
     * session-end learning write can pass it for the OPP-snap + 40% floor clamp on store.
     */
    @Volatile private var sessionOppStepsKhz: List<Int> = emptyList()

    /** Battery read-out to approximate low-battery detection. */
    private var batteryManager: android.os.BatteryManager? = null

    /**
     * GUARDRAIL 2: the CapabilityReport the daemon resolved this session, hoisted to a
     * field so EVERY exit path (stopDaemon, onDestroy, onTaskRemoved, the runDaemon
     * `finally`) can revert against it. Null before the daemon resolves capabilities
     * (nothing has been written yet, so there is nothing to revert).
     */
    @Volatile private var sessionReport: CapabilityReport? = null

    /**
     * GUARDRAIL 2: the idempotent, NonCancellable revert. A single instance per session
     * (rebuilt at daemon start) latches so the four exit paths revert AT MOST ONCE while
     * GUARANTEEING the revert survives the loopJob cancellation that DEFECT B silently
     * swallowed. Lazily created so a never-started service still has a no-op to call.
     */
    private var revert: AutoTdpRevert? = null

    /**
     * WAVE 3A: session-level perfd lifecycle. On a PServer-LIVE device we stop the
     * vendor perf daemons ONCE when the daemon engages and restart them ONCE on
     * exit (surgical + reversible) instead of the per-write thrash. Like [revert]
     * it is rebuilt at daemon start and its restore is NonCancellable + idempotent
     * so EVERY exit path (stopDaemon / onDestroy / onTaskRemoved / the finally)
     * restarts the daemons exactly once — they can NEVER be left stopped. Null
     * before the daemon resolves capabilities (nothing has been stopped yet).
     */
    private var perfDaemons: PerfDaemonController? = null

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Rebuild the config (profile + watts + Smart goal) from the intent
                // extras. EXTRA_GOAL (when present) is what carries the 5-mode goal /
                // AUTO through to the engine's goalOverride. See configFromStartIntent.
                val config = configFromStartIntent(intent)
                startDaemon(config)
            }
            ACTION_STOP -> {
                stopDaemon(AutoTdpStatus.STOPPED, killReason = null)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    /**
     * GUARDRAIL 2: revert EVERY write to stock before the service is torn down.
     *
     * The KDoc at the top of this class always CLAIMED onDestroy reverted — it never
     * did (DEFECT B). It now reverts SYNCHRONOUSLY: we block here (the lifecycle callback
     * must finish the revert before the process can be reclaimed) via runBlocking, and
     * [AutoTdpRevert.revertNow] runs the actual writes under NonCancellable so they land
     * even though serviceScope is about to be cancelled. Idempotent with stopDaemon /
     * onTaskRemoved / the finally — whichever ran first wins; the rest are no-ops.
     */
    override fun onDestroy() {
        // Idempotent clear of the cross-app coexistence sentinel — covers the case where the
        // process is torn down without an orderly stopDaemon (e.g. system kill).
        clearActiveMarker()
        val report = sessionReport
        val revertHandle = revert
        val perfHandle = perfDaemons
        if (report != null && revertHandle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { revertHandle.revertNow(report) }
            }.onFailure { Log.w(TAG, "onDestroy revert failed", it) }
        }
        // WAVE 3A: restart the vendor perf daemons before teardown. NonCancellable +
        // idempotent inside restoreForSession; runBlocking so it completes before the
        // process can be reclaimed. No-op if an earlier exit path already restored.
        if (perfHandle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { perfHandle.restoreForSession() }
            }.onFailure { Log.w(TAG, "onDestroy perfd restore failed", it) }
        }
        loopJob?.cancel()
        loopJob = null
        // UNIT 4: cancel the runtime-budget outer loop too (serviceScope.cancel below also
        // tears it down, but null it explicitly so a restart never sees a stale job).
        runtimeBudgetJob?.cancel()
        runtimeBudgetJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * GUARDRAIL 2: when the user swipes the app away from Recents, Android may kill the
     * process without an orderly onDestroy in time to flush a cancelled-job revert. Revert
     * to stock here too, before deferring to the default teardown. Without this, a swipe-
     * away mid-session could leave the cap pinned (DEFECT B's sibling path). Idempotent.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Idempotent clear of the cross-app coexistence sentinel on swipe-away.
        clearActiveMarker()
        val report = sessionReport
        val revertHandle = revert
        val perfHandle = perfDaemons
        if (report != null && revertHandle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { revertHandle.revertNow(report) }
            }.onFailure { Log.w(TAG, "onTaskRemoved revert failed", it) }
        }
        // WAVE 3A: restart the vendor perf daemons on swipe-away too — the sibling of the
        // revert path. NonCancellable + idempotent; runBlocking so it lands before the
        // process is killed. Without this, a swipe-away mid-session could leave the vendor
        // perf daemons stopped until reboot.
        if (perfHandle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { perfHandle.restoreForSession() }
            }.onFailure { Log.w(TAG, "onTaskRemoved perfd restore failed", it) }
        }
        super.onTaskRemoved(rootIntent)
    }

    // ── Daemon lifecycle ──────────────────────────────────────────────────────

    private fun startDaemon(config: AutoTdpProfileConfig) {
        if (loopJob?.isActive == true) return // already running

        // CROSS-APP COEXISTENCE SIGNAL: write the "AutoTDP is active" sentinel so other
        // perf tools (notably Nova/GameNative, which can pin the CPU governor to
        // `performance` and would otherwise fight AutoTDP's TDP capping) can detect that
        // AutoTDP owns the CPU right now and DEFER. The marker lives in our own private
        // files dir; a peer with root (the same PServer bridge AutoTDP uses) reads it via
        // `cat`. Deleted on every stop path (stopDaemon / onDestroy / onTaskRemoved).
        writeActiveMarker()

        // GUARDRAIL 2: a fresh, single-session revert latch. Reset the session report so
        // a stop before capabilities resolve has nothing stale to revert against.
        sessionReport = null
        revert = AutoTdpRevert(tunableWriter)
        // WAVE 3A: a fresh, single-session perfd controller. The daemon list is filled
        // in once capabilities + adapter resolve (runDaemon, after the LIVE gate). Built
        // here as a no-op (empty daemons) so a stop before capabilities resolve has a
        // valid handle whose restore is a safe no-op.
        perfDaemons = PerfDaemonController(pServerWriter, tunableWriter, emptyList())

        // UNIT 1 (PER-GAME LEARNING): fresh per-session learning state. Reset here so a
        // new session never carries stale stats from a prior run. The package + OPP table
        // are resolved on the IO thread inside runDaemon.
        sessionPackage = null
        sessionStats = SessionStatsAccumulator()

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
     *   1. Resolve CapabilityReport (with PServer cache bust — FIX 2).
     *   2. Check LIVE availability — if denied for a PServer-related reason,
     *      retry once after 300 ms (FIX 2). Only then emit LIVE_UNAVAILABLE.
     *   3. Build TdpCaps.
     *   4. Collect telemetry, maintain rolling window, call AutoTdpEngine.decide.
     *   5. Apply delta via TunableWriter.
     *   6. On exit (any path), revertAll.
     */
    private suspend fun runDaemon(config: AutoTdpProfileConfig) {
        // ── Step 1: resolve capabilities ──────────────────────────────────────
        // FIX 2(a): invalidate the PServer transactableCache BEFORE the refresh
        // so we never evaluate the LIVE gate against a stale false cached from an
        // earlier binder blip. The cache is cheap to rebuild (one `true` transact).
        pServerWriter.invalidateTransactableCache()
        // CRITICAL-1: bust the AYANEO vendor-binder availability cache the SAME way, so
        // the refresh re-probes the binder (one bind round-trip) instead of trusting a
        // stale `true` from before a gamewindow force-stop/restart. Mirrors the PServer
        // bust exactly — without it the LIVE gate could claim live on a dead binder.
        ayaneoBinderClient.invalidateAvailabilityCache()

        // Always refresh rather than using the cached value so that
        // pserverSysfsLive / sysfsDirectlyWritable reflect the CURRENT
        // device state at daemon-start time. A stale report could have
        // pserverSysfsLive=false (probed before the whitelist step was
        // run) and cause the availability gate to reject a device that
        // is actually writable.
        var report: CapabilityReport = capabilityProbe.refresh()

        // ── Step 2: LIVE availability gate ────────────────────────────────────
        // FIX 2(b): if the first probe indicates PServer is unavailable, retry
        // once with a 300 ms delay. A transient binder blip (PServer service
        // restarting, HAL not yet up) often clears within hundreds of ms.
        // We only retry for PServer-related denials (the reason string mentions
        // "pserver" or comes from the live-write path) — structural denials
        // (no writer tier for a node family) are never retried.
        var liveReason = liveUnavailableReason(report)
        if (liveReason != null && isPserverRelatedDenial(report, liveReason)) {
            Log.w(TAG, "LIVE not available (PServer-related), retrying in 300ms: $liveReason")
            delay(300)
            pServerWriter.invalidateTransactableCache()
            // CRITICAL-1: bust the AYANEO cache on the retry too — a transient binder blip
            // (gamewindow restarting) often clears within hundreds of ms, and the retry
            // probe must re-bind rather than echo the stale first-probe result.
            ayaneoBinderClient.invalidateAvailabilityCache()
            report = capabilityProbe.refresh()
            liveReason = liveUnavailableReason(report)
        }

        if (liveReason != null) {
            Log.w(TAG, "LIVE not available after retry: $liveReason")
            controller.updateState(
                AutoTdpRunState(
                    status = AutoTdpStatus.LIVE_UNAVAILABLE,
                    liveAvailable = false,
                    // FIX 2(c): preserve the reason string so the UI can show it (FIX 4).
                    liveUnavailableReason = liveReason,
                )
            )
            stopSelf()
            return
        }

        // ── MUTUAL EXCLUSION (WAVE 3a): claim clock ownership ─────────────────────
        // AutoTDP and Game Boost both write big-cluster clocks. Claim AUTO_TDP; if
        // Game Boost owned the clocks, stop it cleanly first (it reverts its own
        // writes via its own journal) so the two never write concurrently. Acquiring
        // also suppresses the Predictive Throttle Guard for the duration.
        val acquire = arbiter.acquire(
            io.github.mayusi.calibratesoc.data.boost.BoostArbiter.ClockOwner.AUTO_TDP
        )
        if (acquire.mustStopPreviousOwner ==
            io.github.mayusi.calibratesoc.data.boost.BoostArbiter.ClockOwner.GAME_BOOST
        ) {
            Log.i(TAG, "AutoTDP stopping Game Boost before tuning (mutual exclusion)")
            gameBoostController.stop()
            io.github.mayusi.calibratesoc.data.boost.GameBoostService.stop(this@AutoTdpService)
            // Let Game Boost revert before we start writing (revert vs write must not race).
            delay(ARBITER_SETTLE_MS)
        }

        // ── Step 3: build device caps ──────────────────────────────────────────
        // GUARDRAIL 2: publish the resolved report so EVERY exit path (stopDaemon /
        // onDestroy / onTaskRemoved / the finally) can revert against it. From here on
        // the daemon may write, so from here on a revert is meaningful.
        sessionReport = report
        val caps = TdpCaps.from(report)
        val gpuRootPath = report.gpu?.rootPath
        // RUNTIME-GAP FIX: the CORE LIVE NODE — the big-cluster CAP the daemon actually
        // writes (cpuMaxFreq(caps.bigPolicyId), via TdpStateTransition.delta). A
        // CapabilityDenied on THIS node is a total loss of the live path → fatal; a
        // CapabilityDenied on any OTHER (non-bindable) lever is a single-lever denial we
        // skip so the daemon keeps running on the cap path. Derived from the same caps the
        // engine uses, so it tracks the actuated node exactly.
        val capNodeTarget = Tunables.cpuMaxFreq(caps.bigPolicyId).target

        // Resolve the vendor fan_mode Settings.System key from the device adapter
        // (AYN/Retroid expose it as `fan_mode`). Null when this device has no
        // controllable fan key — in which case the fan governor's writes are
        // honestly skipped by TdpStateTransition.delta. We never invent a key.
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        val fanModeKey: String? = adapter
            ?.fanAdapter
            ?.takeIf { it.kind == io.github.mayusi.calibratesoc.data.devicedb.FanAdapterKind.SETTINGS_KEY }
            ?.target

        // ── WAVE 3A: session-level perfd stop (PServer-LIVE only) ─────────────────
        // Rebuild the controller with THIS device's declared perf daemons, then stop
        // them ONCE for the whole session. Gated on pserverSysfsLive: only the PServer
        // root tier can surgically stop/start init services. On every OTHER tier
        // (AYANEO binder, Shizuku, chmod) the per-write daemon dance in TunableWriter
        // stays the right tool, so we pass enabled=false and the controller is a no-op.
        // The restore is wired into EVERY exit path below (stopDaemon / onDestroy /
        // onTaskRemoved / the finally), NonCancellable + idempotent — the daemons can
        // never be left stopped. Commands route via PServer.executeShell → the guard,
        // which allow-lists only `stop`/`start <perf-daemon>`.
        val perfDaemonController = PerfDaemonController(
            pServerWriter = pServerWriter,
            tunableWriter = tunableWriter,
            daemons = adapter?.perfDaemonsToStopOnWrite.orEmpty(),
        )
        perfDaemons = perfDaemonController
        val perfStopped = perfDaemonController.stopForSession(enabled = report.pserverSysfsLive)
        if (perfStopped) {
            Log.i(TAG, "Session perfd stop engaged: ${adapter?.perfDaemonsToStopOnWrite} (PServer-LIVE)")
        }

        Log.i(TAG, "Starting LIVE daemon: profile=${config.profile}, caps=$caps")
        // sessionStartEpochMs marks the RUNNING transition — used for the heartbeat
        // baseline and session-energy integration. Captured here (Dispatchers.IO).
        val sessionStartEpochMs = System.currentTimeMillis()
        controller.updateState(
            AutoTdpRunState(
                status = AutoTdpStatus.RUNNING,
                liveAvailable = true,
                sessionStartEpochMs = sessionStartEpochMs,
            )
        )
        updateNotification(status = "Running", detail = "")

        // ── REVIVE THE SAMPLER (proof-of-effect keystone) ──────────────────────
        // Kick a one-shot baseline→tuned probe. The sampler now waits for RUNNING
        // (already true) then collects its baseline window. To keep that baseline
        // HONEST (pre-cap), the control loop below holds at STOCK — applying NO
        // writes — for BASELINE_GRACE_MS so the sampler's first window measures the
        // device's stock draw. After the grace period the daemon begins tuning and
        // the sampler's tuned window captures the effect. The result populates the
        // MEASURED effect fields once it has enough samples; until then the UI hides
        // them (it never shows a fabricated number).
        sampler.runOnce()

        // UNIT 1 (PER-GAME LEARNING): resolve package + read the learned seed ONCE here,
        // before the loop. Cold start (null package, no row, or sessionCount < 2) -> seed
        // == null -> the engine behaves EXACTLY as today. The seed only sets the STARTING
        // cap; thermal pre-empt / kill / the 40% floor all still run from tick 1.
        val resolvedPkg = runCatching { resolveForegroundPackage() }.getOrNull()
        sessionPackage = resolvedPkg
        sessionOppStepsKhz = caps.bigClusterOppStepsKhz
        val learnedSeed: LearnedSeed? = runCatching {
            learnedGameModel.seedFor(resolvedPkg, caps.bigClusterOppStepsKhz)
        }.getOrNull()
        if (learnedSeed != null) {
            Log.i(TAG, "Per-game seed for $resolvedPkg: cap=${learnedSeed.safeSustainedCapKhz} " +
                "onset=${learnedSeed.throttleOnsetSec}s (${learnedSeed.sessionCount} sessions)")
        }

        // ── UNIT 4 (RICHER GOAL MODES): snapshot the objective setpoints ONCE ──────
        // Read the persisted sliders into a stable value object so the tick loop never
        // touches DataStore. Best-effort: a read failure falls back to GoalParams.DEFAULT
        // (safe values), never blocks the daemon. Reset the outer-ceiling state per session.
        sessionGoalParams = runCatching {
            goalParamsPrefs.params.first()
        }.getOrNull()?.sanitized() ?: GoalParams.DEFAULT
        runtimeCapCeilingKhz = null
        tempCapCeilingKhz = null
        runtimeProjectionNote = null
        lastBatteryVoltageUv = null
        lastBatteryDrawMilliW = null
        capDrawSamples.clear()

        // ── UNIT 4: start the TARGET_RUNTIME 60s outer-budget loop (only for that goal) ──
        // It recomputes the runtime cap-ceiling every minute from remaining Wh + the
        // PowerModel, and the per-tick loop clamps the band controller's cap to it (never
        // loosen above). For every other goal this job is never started → zero overhead.
        if (config.goal == GoalProfile.TARGET_RUNTIME) {
            startRuntimeBudgetLoop(caps)
        }

        // ── Step 4: collect telemetry + control loop ───────────────────────────
        val window = ArrayDeque<Telemetry>(WINDOW_SIZE + 1)
        var currentState = TdpState.STOCK
        val decisions = ArrayDeque<DecisionRecord>(MAX_DECISION_HISTORY + 1)
        val thermalKill = ThermalKillEvaluator()   // stateful: tracks consecutive over-threshold samples
        val stockCeilingKhz = caps.bigClusterOppStepsKhz.lastOrNull() // top OPP = stock big ceiling

        // ── WAVE 4a: persist the controller state ACROSS ticks (THE critical fix) ──
        // Wave 1 made AutoTdpEngine.decide() RETURN its carried ControllerState (the
        // EWMA accumulators, cool-down quietTicks, direction-episode confirm counters,
        // the active lever, the fan governor state, and the context-classifier state).
        // The daemon MUST thread it back in each tick — otherwise every tick decides
        // from ControllerState.INITIAL and gpuEwma / dTempSlopeEwma / quietTicks /
        // classifier hysteresis / activeLever all RESET every second, defeating the
        // entire tightened control spec (no smoothing, no oscillation protection, no
        // classifier hysteresis). We hold it here OUTSIDE the collect{} loop, pass it
        // INTO decide(), and reassign it from the returned decision AFTER each tick.
        //
        // INITIAL is the per-session reset: this runs once per (re)start, so a new
        // session always begins from a clean state (no stale EWMA leaking across
        // start/stop cycles).
        var controllerState = ControllerState.INITIAL

        // ── UNIT 2: ADAPTIVE TICK CADENCE (decimate to honour nextTickHintMs) ──────
        // The shared MonitorService stream stays a fixed 1 Hz (process-shared with the HUD
        // — we never speed it up or spawn a thread). Instead the daemon DECIMATES: the
        // engine REQUESTS a re-eval cadence on TdpDecision.nextTickHintMs (500 ms warming,
        // 1000 ms calm) and we only run the (relatively expensive) engine decision + sysfs
        // writes for a sample when at least that much wall-clock has elapsed since the last
        // processed tick. SAFETY: the per-tick thermal/battery KILL checks below run on
        // EVERY 1 Hz sample and are NEVER decimated — only the tune/apply work is gated.
        // The hint is clamped to ADAPTIVE_TICK_FLOOR_MS (500 ms) so we can never process
        // faster than 2 Hz, and the calm default (1000 ms) keeps today's exact 1 Hz cadence.
        // lastDecisionAtMs = 0 forces the FIRST sample to process immediately (no stall).
        var nextTickHintMs = AutoTdpEngine.CALM_TICK_MS_DEFAULT
        var lastDecisionAtMs = 0L

        try {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                // Roll the window.
                window.addLast(sample)
                if (window.size > WINDOW_SIZE) window.removeFirst()

                // ── Safety checks ──────────────────────────────────────────────
                val tempKill = thermalKill.evaluate(sample)
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

                // ── UNIT 2: ADAPTIVE-CADENCE DECIMATION GATE ───────────────────
                // Honour the engine's nextTickHintMs by skipping the tune/apply work for
                // samples that arrive sooner than the requested cadence (clamped to the
                // 500 ms floor). The window is already rolled and the safety kills above
                // have already run (NEVER skipped) — only the engine decision + sysfs writes
                // are decimated. A calm 1 Hz hint on the 1 Hz stream processes every sample
                // (today's behaviour); a warming 500 ms hint also processes every sample (the
                // 1 Hz stream never out-runs the floor). A future calm hint > 1000 ms would
                // skip intervening samples here to save battery. lastDecisionAtMs starts at 0
                // so the first sample always processes.
                val sampleMs = sample.timestampMs
                val effectiveHintMs =
                    nextTickHintMs.coerceAtLeast(AutoTdpEngine.ADAPTIVE_TICK_FLOOR_MS).toLong()
                if (lastDecisionAtMs != 0L && (sampleMs - lastDecisionAtMs) < effectiveHintMs) {
                    return@collect // decimated this tick — re-eval cadence not yet elapsed
                }
                lastDecisionAtMs = sampleMs

                // ── Engine decision ────────────────────────────────────────────
                // Thread the PERSISTED controllerState in, and pass the Smart goal as
                // goalOverride so the new 5-mode goal / AUTO actually reaches the
                // engine. config.goal == null ⇒ goalOverride == null ⇒ decide() maps
                // the legacy profile internally (today's behaviour, unchanged).
                // UNIT 1: thread the learned seed + the session clock so the engine can
                // seed the starting cap ONCE and arm the proactive pre-empt near
                // 0.85 x the learned onset. Both inert when seed == null (cold start).
                val sessionElapsedSec =
                    ((sample.timestampMs - sessionStartEpochMs) / 1000L).toInt().coerceAtLeast(0)
                // UNIT 4: read battery level + charging for the charge-aware AUTO gate.
                // Cheap BatteryManager reads (already used for the battery kill). Null when
                // unreadable → AUTO falls through to the classifier path (no new guess).
                val batteryPct = readBatteryPct()
                val charging = batteryManager?.isCharging
                val decision = AutoTdpEngine.decide(
                    window = window.toList(),
                    config = config,
                    caps = caps,
                    current = currentState,
                    controllerState = controllerState,
                    goalOverride = config.goal,
                    seed = learnedSeed,
                    sessionElapsedSec = sessionElapsedSec,
                    // UNIT 4: objective setpoints + charge-aware AUTO inputs.
                    goalParams = sessionGoalParams,
                    batteryPct = batteryPct,
                    charging = charging,
                )
                // PERSIST: carry the engine's threaded state into the NEXT tick. This
                // single reassignment is what keeps EWMA smoothing, the cross-actuator
                // cool-down, the direction-episode confirm counters, the active lever,
                // the fan governor, and the classifier hysteresis ALIVE across ticks.
                controllerState = decision.controllerState

                // UNIT 2: adopt the engine's requested re-eval cadence for the NEXT tick.
                // Null (legacy / no-telemetry early return) → keep the calm 1 Hz default,
                // preserving today's exact behaviour. The decimation gate above re-clamps to
                // the 500 ms floor every tick, so a bad hint can never speed past 2 Hz.
                nextTickHintMs = decision.nextTickHintMs ?: AutoTdpEngine.CALM_TICK_MS_DEFAULT

                // ── Honest-baseline grace gate ─────────────────────────────────
                // For the first BASELINE_GRACE_MS after RUNNING, hold at STOCK so the
                // sampler's baseline window measures genuine pre-cap draw. We still
                // run the engine (so the HUD shows what it WOULD do) but apply nothing.
                val inBaselineGrace =
                    (System.currentTimeMillis() - sessionStartEpochMs) < BASELINE_GRACE_MS

                // ── UNIT 4: OUTER-SETPOINT CLAMP (objective goal modes) ─────────────
                // The committed band controller already chose decision.target (with its
                // 40% floor, thermal pre-empt, cool-downs). The three objective modes ride
                // ON TOP as a cap CEILING / anti-tighten: each can only ever TIGHTEN the
                // cap (strictly safer); none can raise the cap above what the band
                // controller chose, push below the 40% floor (CapFloor-snapped), or bypass
                // the kill/revert. Applied only on the matching goal; a no-op otherwise.
                val clampedTarget =
                    applyOuterSetpoint(decision, currentState, caps, sample)
                val effectiveTarget = if (inBaselineGrace) currentState else clampedTarget

                // ── Apply delta ────────────────────────────────────────────────
                // BUG E FIX (honest Applied): track at the tick level whether all ops
                // succeeded. Declared outside the `if` block so displayReason can use it.
                var allOpsSucceeded = true

                if (effectiveTarget != currentState) {
                    val ops = TdpStateTransition.delta(
                        from = currentState,
                        to = effectiveTarget,
                        bigPolicyId = caps.bigPolicyId,
                        gpuRootPath = gpuRootPath,
                        fanModeKey = fanModeKey,
                    )

                    for (op in ops) {
                        // BUG A FIX: retry transient failures (Rejected / Failed) up to
                        // WRITE_RETRY_ATTEMPTS times with WRITE_RETRY_DELAY_MS backoff before
                        // giving up. CapabilityDenied is structural (no writer tier) and is
                        // never retried — the daemon stops immediately and honestly on that.
                        val result = writeWithRetry(op, report)
                        when (result) {
                            is WriteResult.Success -> {
                                // Good — continue to next op.
                            }
                            is WriteResult.CapabilityDenied -> {
                                // RUNTIME-GAP FIX: a CapabilityDenied is FATAL only when it
                                // denies the CORE LIVE NODE — the big-cluster CAP the daemon's
                                // whole live path rides (cpuMaxFreq(caps.bigPolicyId)). Denying
                                // THAT is a total loss of write: stop honestly so the user isn't
                                // left with a "Running" notification that does nothing.
                                //
                                // For ANY OTHER lever (min-freq floor, core-park cpu/online,
                                // GPU pwrlevel, devfreq-min, uclamp), CapabilityDenied means
                                // "this single lever isn't writable on THIS tier" — not a total
                                // loss. On a binder-only tier like AYANEO the tighten ladder
                                // escalates past the CAP floor to these NON-bindable levers;
                                // treating that as fatal would self-terminate the daemon
                                // mid-session. Skip the op and KEEP RUNNING (same posture as
                                // Rejected): the engine rides the cap lever, which is live.
                                if (op.id.target == capNodeTarget) {
                                    val failure = "No write tier available for ${op.description}: ${result.reason}"
                                    Log.e(TAG, failure)
                                    stopDaemon(AutoTdpStatus.WRITE_DENIED, writeFailure = failure)
                                    return@collect
                                } else {
                                    Log.w(
                                        TAG,
                                        "Non-cap lever not writable on this tier (skipping, daemon keeps running): " +
                                            "${op.description}: ${result.reason}",
                                    )
                                    allOpsSucceeded = false
                                    // Do not return@collect — skip this lever, continue the loop.
                                }
                            }
                            is WriteResult.Rejected -> {
                                // Still rejected after all retries — transient became persistent
                                // (e.g. governor is permanently protecting this OPP). Log it and
                                // keep the daemon running; this op is skipped for this cycle.
                                // The engine will try again next tick; if the condition clears
                                // the write will succeed. We do NOT stop because a single
                                // transient EBUSY from a governor that has since moved on should
                                // not terminate the daemon session.
                                Log.w(TAG, "Write still rejected after retries for ${op.description}: ${result.message}")
                                allOpsSucceeded = false
                                // Do not return@collect — skip this op, continue the loop.
                            }
                            is WriteResult.Failed -> {
                                // Unexpected failure (binder death, IO) after retries. Log it
                                // and keep running; the next tick will try again. If the device
                                // has entered a state where binder is permanently dead the
                                // safety-kill path will handle shutdown through temp / battery.
                                Log.w(TAG, "Write failed after retries for ${op.description}: ${result.error.message}")
                                allOpsSucceeded = false
                                // Do not return@collect — skip this op, continue the loop.
                            }
                        }
                    }
                    // Only advance currentState when all ops succeeded (BUG E FIX).
                    // If any op was skipped, currentState stays at the last fully-applied
                    // state so the HUD shows the ACTUAL written state, not the intent.
                    if (allOpsSucceeded) {
                        currentState = effectiveTarget
                    }
                }

                // BUG E FIX: show the actual applied state reason, not just the engine intent.
                // If any ops were skipped (!allOpsSucceeded), tag the reason as partial
                // so the HUD honestly reflects that not all writes landed this tick.
                val displayReason = if (!allOpsSucceeded) {
                    "${decision.reason} [partial — some writes skipped]"
                } else {
                    decision.reason
                }
                Log.v(TAG, "decision: $displayReason")

                // UNIT 1 (PER-GAME LEARNING): feed the session-stats accumulator. Cheap
                // per-tick fold (a few scalar writes): the converged cap, the first steady-
                // state pre-empt (for the learned onset), the band center, and the real-FPS
                // roll. Never touches the device -- the learning WRITE happens at session end.
                sessionStats?.onTick(
                    elapsedSec = sessionElapsedSec,
                    inBaselineGrace = inBaselineGrace,
                    appliedCapKhz = currentState.bigClusterCapKhz,
                    reason = decision.reason,
                    bandCenterPct = decision.resolvedGoal.let {
                        (it.gpuBandLowPct + it.gpuBandHighPct) / 2
                    },
                    realFpsX10 = sample.realFpsX10.takeIf { sample.isRealFps },
                )

                // ── UNIT 4: fold battery + cap→draw for the runtime-budget loop ─────
                // Cheap scalar writes: the 60s outer loop reads these off the tick thread.
                // We record the (applied cap, measured draw) pair only OUTSIDE baseline
                // grace (so warm-up STOCK draw never poisons the model) and only when both
                // the cap and a real draw are known — keeps the PowerModel fit honest.
                sample.batteryVoltageUv?.let { lastBatteryVoltageUv = it }
                val drawMw = sample.batteryDrawMilliW
                if (drawMw != null && drawMw > 0L) {
                    lastBatteryDrawMilliW = drawMw
                    if (!inBaselineGrace) {
                        val cap = currentState.bigClusterCapKhz
                            ?: caps.bigClusterOppStepsKhz.lastOrNull() // null cap = stock = top OPP
                        if (cap != null) capDrawSamples[cap] = drawMw
                    }
                }

                // ── Proof-of-effect wiring ─────────────────────────────────────
                // HEARTBEAT: record wall-clock of this applied tick.
                val nowMs = System.currentTimeMillis()
                // Carry the engine's HoldReason into run state (UI's clean label).
                val holdReason = decision.holdReason
                // Append a DecisionRecord (FIFO, bounded by MAX_DECISION_HISTORY).
                decisions.addLast(
                    DecisionRecord(
                        epochMs = nowMs,
                        holdReason = holdReason,
                        bigCapKhz = currentState.bigClusterCapKhz,
                        parkedCount = currentState.parkedPrimeCores.size,
                        rawReason = displayReason,
                    )
                )
                while (decisions.size > MAX_DECISION_HISTORY) decisions.removeFirst()

                // Build the effect bundle. DERIVED fields (cap delta, parked cores,
                // GPU floor) are ALWAYS populated from currentState + caps. MEASURED
                // fields come from `savings` once the probe has enough data; the
                // sampler also patches temp/fps in via updateProbeResult. We re-stamp
                // the stock ceiling here because TdpState carries no ceiling itself.
                val savings = controller.state.value.savings
                val sessionElapsedMs = nowMs - sessionStartEpochMs
                val effect = AutoTdpEffect.from(
                    appliedState = currentState,
                    caps = caps,
                    savings = savings,
                    sessionElapsedMs = sessionElapsedMs,
                ).let {
                    // Preserve measured temp/fps the sampler may have already patched
                    // onto the prior effect (from() always nulls them).
                    val prior = controller.state.value.effect
                    if (prior != null && it.effectSource == EffectSource.MEASURED) {
                        it.copy(tempDeltaC = prior.tempDeltaC, fpsDelta = prior.fpsDelta)
                    } else {
                        it
                    }
                }.let {
                    // Defensive: ensure the stock ceiling reflects caps even if the
                    // OPP table was somehow empty in from() (keeps capDelta honest).
                    if (it.stockBigCeilingKhz == null && stockCeilingKhz != null) {
                        val cap = it.bigCapKhz
                        it.copy(
                            stockBigCeilingKhz = stockCeilingKhz,
                            capDeltaKhz = if (cap != null) stockCeilingKhz - cap else null,
                        )
                    } else {
                        it
                    }
                }

                // ── WAVE 4a: expose goal + detected context (read-only HUD state) ──
                // activeGoal: the goal the daemon is RUNNING. Only meaningful on the
                // Smart path (config.goal != null) — there it is the CONCRETE goal the
                // engine resolved (AUTO → the classifier's choice via decision.resolvedGoal).
                // On the pure legacy-profile path we expose null (no Smart goal picked).
                // detectedContext: the classifier's COMMITTED belief (classifier.stable)
                // — the DETECTED honesty tier. It is a belief, never a measurement; we
                // always surface it (the classifier runs every tick regardless of path).
                val activeGoal = if (config.goal != null) decision.resolvedGoal else null
                val detectedContext = controllerState.classifier.stable

                controller.updateState(
                    controller.state.value.copy(
                        status = AutoTdpStatus.RUNNING,
                        lastReason = displayReason,
                        appliedState = currentState,
                        holdReason = holdReason,
                        lastAppliedEpochMs = nowMs,
                        effect = effect,
                        decisions = decisions.toList(),
                        activeGoal = activeGoal,
                        detectedContext = detectedContext,
                        // UNIT 4: honesty surface for the objective goal modes.
                        fpsFloorDegraded = decision.fpsFloorDegraded,
                        runtimeProjectionNote =
                            if (config.goal == GoalProfile.TARGET_RUNTIME) runtimeProjectionNote else null,
                    )
                )
                updateNotification(status = "Running", detail = displayReason)
            }
        } finally {
            // Stop the savings probe if it's still mid-cycle — the session is over.
            sampler.cancel()
            // ── Revert all writes (CRITICAL — GUARDRAIL 2) ─────────────────────
            // DEFECT B: this finally runs on the loopJob, and EVERY stop path reaches
            // it via loopJob.cancel(). The old direct revertAll() routed through
            // PServerWriter's withContext(Dispatchers.IO), which throws
            // CancellationException on the already-cancelled job → writes silently
            // skipped → 384 MHz cap stayed pinned until reboot. Routing through
            // revertNow() runs the revert under NonCancellable so it ACTUALLY lands even
            // when this finally fires because the job was cancelled. Idempotent: if
            // stopDaemon/onDestroy already reverted, this is a no-op (returns null).
            Log.i(TAG, "Reverting all AutoTDP writes via TunableWriter (finally)")
            val summary = revert?.revertNow(report)
            if (summary != null) {
                Log.i(TAG, "Revert complete: ok=${summary.ok} failed=${summary.failed}")
            } else {
                Log.i(TAG, "Revert already performed by an earlier exit path (no-op)")
            }
            // ── WAVE 3A: restart the vendor perf daemons (CRITICAL — NonCancellable) ──
            // Mirror of the revert above: the perfd restart MUST land on every exit so
            // the device is never left with vendor perf management disabled. restoreForSession
            // is NonCancellable + idempotent, so it completes even though this finally rides
            // the cancelled loopJob, and is a no-op if an earlier exit path already restored.
            perfDaemons?.restoreForSession()
            // ── WAVE 3a: release clock ownership (un-suppresses the throttle guard). ──
            // No-op if a later owner (Game Boost) already took over — release() checks.
            arbiter.release(io.github.mayusi.calibratesoc.data.boost.BoostArbiter.ClockOwner.AUTO_TDP)
            // UNIT 1 (PER-GAME LEARNING): persist this session learned params AFTER the
            // safety-critical revert/perfd/arbiter work so a learning write can never delay
            // or interfere with reverting to stock. NonCancellable + runCatching inside;
            // idempotent per session (latched).
            persistLearnedParams()
            // Update notification if service is still technically alive
            updateNotification(status = "Stopped", detail = "All writes reverted")
        }
    }

    /**
     * UNIT 1 (PER-GAME LEARNING): fold this session accumulated outcome into the learned
     * params store, exactly once per session. Safe on every exit path: NonCancellable so the
     * suspend DAO write completes even though the enclosing finally rides the cancelled
     * loopJob; runCatching so a DAO failure can NEVER delay the revert; latched on
     * [sessionStats] so concurrent exits write at most once; no-op when the package is
     * unknown or no stats accrued (cold start -- nothing learned).
     */
    private suspend fun persistLearnedParams() {
        val stats = sessionStats ?: return
        sessionStats = null // latch: at most one learning write per session
        val pkg = sessionPackage ?: return
        val outcome = stats.toOutcome(oppStepsKhz = sessionOppStepsKhz) ?: return
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                learnedGameModel.updateAfterSession(pkg, outcome, System.currentTimeMillis())
            }
        }.onFailure { Log.w(TAG, "persistLearnedParams failed (non-fatal)", it) }
    }

    /**
     * BUG A FIX: Wraps [TunableWriter.write] with per-op retry for transient errors.
     *
     * Classification:
     *  - [WriteResult.CapabilityDenied] → structural, no retry (no writer tier exists).
     *  - [WriteResult.Rejected]         → transient (EBUSY, binder hiccup), retry up to
     *                                     [WRITE_RETRY_ATTEMPTS] times with [WRITE_RETRY_DELAY_MS].
     *  - [WriteResult.Failed]           → unexpected I/O / binder death, retry same policy.
     *  - [WriteResult.Success]          → return immediately.
     *
     * If the final attempt is still Rejected/Failed the last result is returned to the
     * caller which logs it and skips the op (daemon keeps running).
     */
    private suspend fun writeWithRetry(
        op: TdpStateTransition.WriteOp,
        report: CapabilityReport,
    ): WriteResult {
        var lastResult: WriteResult = tunableWriter.write(
            id = op.id,
            value = op.value,
            report = report,
            reason = "AutoTDP: ${op.description}",
        )
        if (lastResult is WriteResult.Success || lastResult is WriteResult.CapabilityDenied) {
            return lastResult
        }
        // Transient: retry with backoff.
        repeat(WRITE_RETRY_ATTEMPTS - 1) { attempt ->
            Log.d(TAG, "Write retry ${attempt + 1}/$WRITE_RETRY_ATTEMPTS for ${op.description}")
            delay(WRITE_RETRY_DELAY_MS)
            lastResult = tunableWriter.write(
                id = op.id,
                value = op.value,
                report = report,
                reason = "AutoTDP retry ${attempt + 2}: ${op.description}",
            )
            if (lastResult is WriteResult.Success || lastResult is WriteResult.CapabilityDenied) {
                return lastResult
            }
        }
        return lastResult
    }

    /**
     * Stop the daemon and revert EVERY write to stock — GUARDRAIL 2.
     *
     * DEFECT B: the old body cancelled the loop and called stopSelf() WITHOUT awaiting a
     * revert; the only revert was the loop's `finally`, which rode the now-cancelled job
     * and was silently skipped (PServer's withContext(Dispatchers.IO) threw
     * CancellationException). So the 384 MHz cap stayed pinned until a reboot.
     *
     * Now: launch on the service scope, run [AutoTdpRevert.revertNow] (NonCancellable +
     * IO) to completion FIRST, THEN cancel the loop and stopSelf. The revert is on a
     * fresh coroutine and NonCancellable internally, so it lands regardless. Idempotent
     * with the `finally` and onDestroy paths via the revert latch. Cancelling the loop
     * AFTER the revert avoids a write/revert race (the loop is paused on its 1 Hz
     * telemetry suspension between ticks; the NonCancellable revert writes stock, then we
     * cancel so no further tick can re-tighten).
     *
     * Thermal-kill and battery-kill paths call this same function, so they revert too.
     */
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
        // Clear the cross-app coexistence sentinel immediately on the stop request — the
        // daemon is no longer the CPU owner from this instant, so a peer (Nova) can resume.
        clearActiveMarker()
        val report = sessionReport
        val revertHandle = revert
        val perfHandle = perfDaemons
        serviceScope.launch {
            // Revert to stock FIRST (NonCancellable inside revertNow), awaiting completion
            // so the device is back at kernel-default clocks before we tear down.
            if (report != null && revertHandle != null) {
                runCatching {
                    val summary = revertHandle.revertNow(report)
                    if (summary != null) {
                        Log.i(TAG, "stopDaemon revert: ok=${summary.ok} failed=${summary.failed}")
                    }
                }.onFailure { Log.w(TAG, "stopDaemon revert failed", it) }
            }
            // WAVE 3A: restart the vendor perf daemons (NonCancellable + idempotent).
            // Paired with the session-level stop in runDaemon — must land on this exit
            // path too so the daemons are never left stopped. No-op if already restored.
            runCatching { perfHandle?.restoreForSession() }
                .onFailure { Log.w(TAG, "stopDaemon perfd restore failed", it) }
            // Now it is safe to cancel the loop (its finally's revert is a latched no-op)
            // and stop the service.
            loopJob?.cancel()
            loopJob = null
            // UNIT 4: stop the TARGET_RUNTIME 60s outer-budget loop too (no-op if absent).
            runtimeBudgetJob?.cancel()
            runtimeBudgetJob = null
            stopSelf()
        }
    }

    // ── Safety checks ─────────────────────────────────────────────────────────
    // Thermal kill is now handled by [ThermalKillEvaluator] (stateful, debounced).
    // See ThermalKillEvaluator.kt for threshold and debounce rationale.

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

    // ════════════════════════════════════════════════════════════════════════════
    //  UNIT 4 — OBJECTIVE-MODE OUTER SETPOINTS (cap-ceiling / fps-floor / runtime)
    // ════════════════════════════════════════════════════════════════════════════

    /** Battery level (%), or null when unreadable. Feeds the charge-aware AUTO gate. */
    private fun readBatteryPct(): Int? =
        batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    /**
     * UNIT 4: apply the active objective mode's OUTER SETPOINT to the band controller's
     * chosen [TdpDecision.target], producing the cap the daemon actually writes this tick.
     *
     * Each mode keys off [TdpDecision.resolvedGoal] (so AUTO that resolved to an objective
     * mode is handled too) and acts ONLY on the big-cluster cap. Every path can only ever
     * TIGHTEN the cap (lower it toward a ceiling, or hold it at the FPS knee) — strictly
     * safer. The band controller's 40% floor, thermal pre-empt, cool-downs and the thermal
     * kill all already ran inside decide(); this never raises a cap above the band
     * controller's choice and never pushes below the 40% floor ([RuntimeBudgetController]
     * snaps every ceiling through [CapFloor]).
     *
     *  - TARGET_RUNTIME       → clamp cap ≤ [runtimeCapCeilingKhz] (60s outer loop).
     *  - TARGET_TEMP_CEILING  → walk [tempCapCeilingKhz] vs the user ceiling, clamp to it.
     *  - TARGET_FPS_FLOOR     → when real FPS < floor, block the tighten (hold the knee).
     *  - anything else        → the band controller's target unchanged.
     */
    private fun applyOuterSetpoint(
        decision: TdpDecision,
        currentState: TdpState,
        caps: TdpCaps,
        sample: Telemetry,
    ): TdpState {
        val steps = caps.bigClusterOppStepsKhz
        val proposedCap = decision.target.bigClusterCapKhz
        val clampedCap: Int? = when (decision.resolvedGoal) {
            GoalProfile.TARGET_RUNTIME ->
                RuntimeBudgetController.clampCapToCeiling(proposedCap, runtimeCapCeilingKhz, steps)

            GoalProfile.TARGET_TEMP_CEILING -> {
                // Walk the temp cap-ceiling from the smoothed die vs the user ceiling, then
                // clamp to it. We read the die from this sample (prefer the GPU die in the
                // sane band, else the hottest zone) — same source the engine prefers.
                val dieC = sampleDieTempC(sample)
                val nextCeiling = RuntimeBudgetController.computeTempCeiling(
                    currentCeilingKhz = tempCapCeilingKhz,
                    smoothedDieC = dieC,
                    ceilingC = sessionGoalParams.tempCeilingC,
                    oppStepsKhz = steps,
                )
                tempCapCeilingKhz = nextCeiling
                RuntimeBudgetController.clampCapToCeiling(proposedCap, nextCeiling, steps)
            }

            GoalProfile.TARGET_FPS_FLOOR ->
                RuntimeBudgetController.applyFpsFloorBlock(
                    proposedCapKhz = proposedCap,
                    currentCapKhz = currentState.bigClusterCapKhz,
                    realFpsX10 = sample.realFpsX10,
                    isRealFps = sample.isRealFps,
                    fpsFloor = sessionGoalParams.fpsFloor,
                    oppStepsKhz = steps,
                )

            else -> proposedCap // no objective outer setpoint for this goal
        }
        return if (clampedCap != proposedCap) {
            decision.target.copy(bigClusterCapKhz = clampedCap)
        } else {
            decision.target
        }
    }

    /**
     * The die temp (°C) from a telemetry [sample] for the temp-ceiling guard: prefer the
     * GPU die read when it is a sane milli-°C value, else the hottest skin zone. Mirrors
     * the engine's preference (defense-in-depth against a bad-unit die). Null when unknown.
     */
    private fun sampleDieTempC(sample: Telemetry): Int? {
        val die = sample.gpuDieTempMilliC?.takeIf { it in DIE_SANE_MILLI_MIN..DIE_SANE_MILLI_MAX }
        val milli = die ?: sample.zoneTempsMilliC.maxByOrNull { it.tempMilliC }?.tempMilliC
        return milli?.let { it / 1000 }
    }

    /**
     * UNIT 4: the TARGET_RUNTIME 60-second outer-budget loop. Recomputes the runtime cap
     * CEILING every minute from remaining Wh + the PowerModel, writing it to
     * [runtimeCapCeilingKhz] (the per-tick loop clamps the band controller's cap to it,
     * never loosening above). Runs in [serviceScope] (cancelled on every stop path). It
     * NEVER writes sysfs and NEVER bypasses safety — it only LOWERS a ceiling the tick loop
     * then applies as a strictly-safer upper bound. The projection is MODELLED + labelled.
     */
    private fun startRuntimeBudgetLoop(caps: TdpCaps) {
        runtimeBudgetJob?.cancel()
        runtimeBudgetJob = serviceScope.launch {
            while (true) {
                runCatching { recomputeRuntimeBudget(caps) }
                    .onFailure { Log.w(TAG, "runtime budget recompute failed", it) }
                delay(RuntimeBudgetController.RECOMPUTE_INTERVAL_MS)
            }
        }
    }

    /**
     * One recompute of the runtime cap ceiling: read remaining Wh + live draw, fit the
     * PowerModel from the session's measured (cap, draw) pairs when available, and ask
     * [RuntimeBudgetController] for the largest OPP whose modelled draw fits the budget.
     * Writes [runtimeCapCeilingKhz] + [runtimeProjectionNote]. All reads are best-effort;
     * on missing data the ceiling stays null (fails OPEN to the always-safe band controller).
     */
    private fun recomputeRuntimeBudget(caps: TdpCaps) {
        val bm = batteryManager ?: return
        val chargeUah = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it > 0 } ?: return
        // Live battery draw + voltage from the most recent monitor sample carried on state.
        val voltageUv = lastBatteryVoltageUv
        val drawMw = lastBatteryDrawMilliW
        if (voltageUv == null || voltageUv <= 0L) return
        val remainingWh = (chargeUah / 1_000_000.0) * (voltageUv / 1_000_000.0)
        val targetHours = sessionGoalParams.targetRuntimeHours.toDouble()

        // Session's measured (cap, draw) pairs → optional non-linear PowerModel fit.
        // Collected by the tick loop into [capDrawSamples] (isolated from Unit 1's stats).
        val pairs = capDrawSamples.toMap()
        val fit = if (pairs.size >= 2) PowerModel.fit(pairs) else null

        val budget = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = remainingWh,
            targetHours = targetHours,
            oppStepsKhz = caps.bigClusterOppStepsKhz,
            powerModelFit = fit,
            referenceCapKhz = caps.bigClusterOppStepsKhz.lastOrNull(),
            referenceDrawMilliW = drawMw,
        )
        runtimeCapCeilingKhz = budget.capCeilingKhz
        runtimeProjectionNote = budget.projectedHours?.let { hrs ->
            val confTag = if (budget.confidence == PowerModel.Confidence.MEASURED) "modelled" else "estimated"
            "Projected: ${BatteryTarget.formatHours(hrs)} ($confTag)"
        } ?: budget.note
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
        //
        // EXCEPTION — AYANEO vendor-binder live path: the binder drives the CPU cluster
        // CAP (scaling_max_freq), governor, GPU max, and fan, but has NO command for the
        // other levers (core-park cpu/online, min-freq floor, GPU pwrlevel, devfreq-min,
        // uclamp). The CAP is the live gate below; cpu/online is NOT required here.
        //
        // The CAP is the daemon's PRIMARY live lever, but not its ONLY emitted op: when the
        // tighten ladder bottoms the cap at the 40% floor and keeps tightening, the engine
        // does emit non-bindable levers (min-freq floor / park / GPU floor). Those come back
        // CapabilityDenied — which the runDaemon op-loop now SKIPS (keeps running) rather
        // than treating as fatal; only a CapabilityDenied on the CAP node itself stops the
        // daemon. So AutoTDP stays alive on the cap path even under sustained tightening.
        // (The hard cap floor + revert-on-exit still apply equally.)
        if (!report.ayaneoBinderLive) {
            val onlineId = Tunables.cpuOnline(0)
            if (!writerRegistry.isLiveWritable(onlineId, report)) {
                val why = Tunables.whyWriteDenied(onlineId, report)
                return "cpu online/offline not writable — ${why ?: "writer would deny"}"
            }
        }
        // HIGH-3: gate on the EXACT node the daemon actuates — cpuMaxFreq(caps.bigPolicyId),
        // the gold/big policy [TdpCaps.from] selects and [TdpStateTransition.delta] writes —
        // NOT maxByOrNull{availableFreqsKhz} (which picks the prime policy7 on a 3-cluster
        // AYANEO, a DIFFERENT node than the cap write to policy3). Deriving from the SAME
        // TdpCaps the engine uses means the gate and the actuator can never drift.
        val caps = TdpCaps.from(report)
        if (report.cpuPolicies.isNotEmpty()) {
            val freqId = Tunables.cpuMaxFreq(caps.bigPolicyId)
            if (!writerRegistry.isLiveWritable(freqId, report)) {
                val why = Tunables.whyWriteDenied(freqId, report)
                return "scaling_max_freq not writable — ${why ?: "writer would deny"}"
            }
        }
        return null // all clear — both critical node families have a live writer
    }

    /**
     * FIX 2: Returns true when the LIVE-unavailable denial is PServer-related —
     * i.e. the report shows no live path AND the device likely has PServer
     * (vendor app present). These are the transient cases worth retrying.
     * Structural denials (root tier required, no cpu policies, etc.) return false.
     */
    private fun isPserverRelatedDenial(report: CapabilityReport, reason: String): Boolean {
        // If the report already confirms pserverSysfsLive=false but a vendor app
        // is present (meaning PServer binder exists), it's a binder blip worth
        // retrying. Also catch reason strings that reference the live-write path.
        val vendorPresent = report.vendorApps.aynGameAssistant || report.vendorApps.langerhansOdinTools
        return vendorPresent && !report.pserverSysfsLive
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

    // ── Cross-app coexistence sentinel ─────────────────────────────────────────

    /**
     * Create the "AutoTDP is active" marker file in our private files dir, containing
     * THIS process's PID. The PID makes the marker LIVENESS-VERIFIABLE by a peer: a hard
     * kill (`am force-stop`) or a crash tears down the process WITHOUT running our orderly
     * stop paths, so the marker file is left behind stale. A peer that only checked
     * file-existence would then defer forever. By writing our PID, a peer can confirm the
     * process is still alive (`/proc/<pid>` exists and is CalibrateSoC) before deferring,
     * and treat a dead-PID marker as "not active". Best-effort; a failure only means a peer
     * can't auto-detect us this session (it falls back to applying — the documented
     * behaviour for an old/unsignalled AutoTDP).
     */
    private fun writeActiveMarker() {
        runCatching {
            java.io.File(filesDir, AUTOTDP_ACTIVE_MARKER).writeText(
                android.os.Process.myPid().toString(),
            )
        }.onFailure { Log.w(TAG, "writeActiveMarker failed (non-fatal)", it) }
    }

    /** Delete the active marker. Idempotent — a no-op if it was never created. */
    private fun clearActiveMarker() {
        runCatching {
            val f = java.io.File(filesDir, AUTOTDP_ACTIVE_MARKER)
            if (f.exists()) f.delete()
        }.onFailure { Log.w(TAG, "clearActiveMarker failed (non-fatal)", it) }
    }

    // ── UNIT 1 (PER-GAME LEARNING): foreground package resolver + stats accumulator ──

    /**
     * Best-effort foreground game package via UsageStatsManager — the SAME source
     * [io.github.mayusi.calibratesoc.data.session.SessionRecorder] uses, so the learned
     * key matches the recorded-session key. Returns null when PACKAGE_USAGE_STATS is not
     * granted or no foreground app (other than ourselves) was seen in the last 60 s —
     * then the session neither seeds nor learns (cold start, no fabrication).
     */
    private fun resolveForegroundPackage(): String? {
        val um = getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        if (packageManager.checkPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS,
                packageName,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val now = System.currentTimeMillis()
        val events = um.queryEvents(now - 60_000L, now)
        val ev = android.app.usage.UsageEvents.Event()
        var lastPkg: String? = null
        while (events.getNextEvent(ev)) {
            if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = ev.packageName
            }
        }
        return lastPkg?.takeIf { it != packageName }
    }

    /**
     * UNIT 1: the lightweight per-session stats accumulator. Fed once per processed tick
     * (a handful of scalar writes — no allocation, no device I/O) and converted to a
     * [SessionOutcome] at session end.
     *
     * Honesty / safety choices baked in here:
     *  - Only STEADY-STATE ticks count (after the honest-baseline grace), so the warm-up
     *    cap walk never looks like a throttle event.
     *  - Only a REAL thermal pre-empt ("thermal pre-empt …") marks the session as throttled
     *    and records the onset. The PROACTIVE pre-empt ("proactive pre-empt …") is the
     *    engine acting on what we already learned — counting it would create a runaway
     *    ratchet-down feedback loop, so it is deliberately ignored for learning.
     *  - convergedCap = the LAST applied steady-state cap (where the controller settled).
     *  - avgFpsHeldTarget is computed ONLY from real SurfaceFlinger FPS; with no real FPS
     *    the session is treated as NOT-clean (never ratchet the cap UP on an unverified run).
     */
    private class SessionStatsAccumulator {
        private var sawSteadyState = false
        private var lastSteadyCapKhz: Int? = null
        private var preemptFired = false
        private var firstPreemptSec: Int? = null
        private var lastBandCenterPct: Int? = null
        // Real-FPS roll (steady state only).
        private var fpsSum = 0L
        private var fpsCount = 0
        private var fpsPeakX10 = 0

        fun onTick(
            elapsedSec: Int,
            inBaselineGrace: Boolean,
            appliedCapKhz: Int?,
            reason: String,
            bandCenterPct: Int,
            realFpsX10: Int?,
        ) {
            if (inBaselineGrace) return // warm-up ticks never count toward learning
            sawSteadyState = true
            lastSteadyCapKhz = appliedCapKhz
            lastBandCenterPct = bandCenterPct
            // A REAL thermal pre-empt (NOT the proactive one) marks the throttle onset.
            if (!preemptFired &&
                reason.startsWith("thermal pre-empt", ignoreCase = true)
            ) {
                preemptFired = true
                firstPreemptSec = elapsedSec
            }
            if (realFpsX10 != null && realFpsX10 > 0) {
                fpsSum += realFpsX10
                fpsCount++
                if (realFpsX10 > fpsPeakX10) fpsPeakX10 = realFpsX10
            }
        }

        /**
         * Convert to a [SessionOutcome], or null when no steady-state tick ever ran (the
         * session was too short / all warm-up) — then there is nothing to learn and the
         * caller skips the write. avgFpsHeldTarget: real FPS averaged at/above 90% of this
         * session's own observed peak (a smooth run held its frame-rate); false when no real
         * FPS was measured, so a clean-run cap ratchet-up only ever happens on a verified run.
         */
        fun toOutcome(oppStepsKhz: List<Int>): SessionOutcome? {
            if (!sawSteadyState) return null
            val avgHeld = if (fpsCount > 0 && fpsPeakX10 > 0) {
                val avg = fpsSum.toDouble() / fpsCount
                avg >= 0.90 * fpsPeakX10
            } else {
                false // no real FPS → not verified clean
            }
            return SessionOutcome(
                preemptFiredInSteadyState = preemptFired,
                avgFpsHeldTarget = avgHeld,
                convergedCapKhz = lastSteadyCapKhz,
                observedOnsetSec = firstPreemptSec,
                observedBandCenterPct = lastBandCenterPct,
                oppStepsKhz = oppStepsKhz,
            )
        }
    }

    // ── Companions ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AutoTdpService"
        private const val NOTIFICATION_ID = 5511
        private const val CHANNEL_ID = "autotdp"

        /**
         * Cross-app coexistence sentinel filename, written into this service's private
         * files dir (with our PID) while the daemon is active and deleted on every stop
         * path. A peer perf tool with root (e.g. Nova/GameNative, via its PServer bridge)
         * reads `/data/data/<our-pkg>/files/autotdp_active.marker`, verifies the PID is a
         * live CalibrateSoC process, and defers so the two never fight over CPU clock
         * control. The filename is part of the cross-app contract — do not rename without
         * updating the peer reader.
         */
        const val AUTOTDP_ACTIVE_MARKER = "autotdp_active.marker"

        /** Rolling telemetry window depth (matches design spec: ~4 samples). */
        private const val WINDOW_SIZE = 4

        /**
         * Honest-baseline grace period (ms): the daemon holds at STOCK (applies no
         * writes) for this long after RUNNING so the sampler's baseline window
         * measures genuine pre-cap draw. Matches [AutoTdpSampler.SAMPLE_DURATION_S]
         * (20 s) so the baseline window completes before tuning begins.
         */
        private const val BASELINE_GRACE_MS = AutoTdpSampler.SAMPLE_DURATION_S * 1000L

        /**
         * Battery percentage below which the daemon stops. 5% is the same
         * floor the benchmark runner uses — leaves enough headroom for the OS
         * to save state cleanly.
         */
        private const val BATTERY_KILL_PCT = 5

        /**
         * UNIT 4: plausible GPU die-temp band (milli-°C) for the temp-ceiling guard. Mirrors
         * the engine's defense-in-depth band so a bad-unit / off-scale die never drives the
         * outer temp guard; outside this band we fall back to the hottest skin zone.
         */
        private const val DIE_SANE_MILLI_MIN = 20_000
        private const val DIE_SANE_MILLI_MAX = 130_000

        /**
         * BUG A FIX: Maximum number of write attempts for transient failures.
         * First attempt + (WRITE_RETRY_ATTEMPTS - 1) retries = 2 total tries.
         */
        private const val WRITE_RETRY_ATTEMPTS = 2

        /**
         * BUG A FIX: Backoff delay between write retry attempts (milliseconds).
         * 50 ms is enough for a binder glitch or EBUSY from a governor to clear.
         */
        private const val WRITE_RETRY_DELAY_MS = 50L

        /**
         * WAVE 3a: settle time after stopping the other clock owner (Game Boost)
         * before AutoTDP begins writing, so the other's revert and our writes do not
         * interleave. Both route through TunableWriter; 250 ms is ample.
         */
        private const val ARBITER_SETTLE_MS = 250L

        // Intent actions
        const val ACTION_START = "io.github.mayusi.calibratesoc.AUTOTDP_START"
        const val ACTION_STOP  = "io.github.mayusi.calibratesoc.AUTOTDP_STOP"

        // Extras
        const val EXTRA_PROFILE_ORDINAL = "profile_ordinal"
        const val EXTRA_TARGET_MW       = "target_mw"

        /**
         * WAVE 4a: the active Smart [GoalProfile] name, or absent when running the
         * legacy profile path. Carried as a String (the enum NAME) so the goal — incl.
         * AUTO and the 5 modes the old [AutoTdpProfile] ordinal cannot express —
         * survives the intent round-trip. Rebuilt in [configFromStartIntent]. Absent ⇒
         * config.goal == null ⇒ daemon uses the legacy [EXTRA_PROFILE_ORDINAL] mapping.
         */
        const val EXTRA_GOAL = "goal_name"

        /**
         * Build the ACTION_START intent for [config] (pure — no service-start side
         * effect). Extracted so the goal/profile/watts round-trip is unit-testable
         * without an Android service runtime.
         *
         * EXTRA_PROFILE_ORDINAL is ALWAYS written (back-compat + the watts-ceiling
         * path). EXTRA_GOAL is written only when [AutoTdpProfileConfig.goal] is set;
         * its presence is what makes the Smart engine reachable.
         */
        fun buildStartIntent(context: Context, config: AutoTdpProfileConfig): Intent =
            Intent(context, AutoTdpService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ORDINAL, config.profile.ordinal)
                config.targetMilliWatts?.let { putExtra(EXTRA_TARGET_MW, it) }
                config.goal?.let { putExtra(EXTRA_GOAL, it.name) }
            }

        /**
         * Rebuild the [AutoTdpProfileConfig] from an ACTION_START intent (pure inverse
         * of [buildStartIntent]). Unknown/absent extras degrade to the safe legacy
         * default (BALANCED, no goal). An unparseable EXTRA_GOAL name is dropped (goal
         * == null) rather than crashing — the daemon then runs the legacy profile.
         */
        fun configFromStartIntent(intent: Intent): AutoTdpProfileConfig {
            val profileOrdinal = intent.getIntExtra(EXTRA_PROFILE_ORDINAL, AutoTdpProfile.BALANCED.ordinal)
            val profile = AutoTdpProfile.entries.getOrElse(profileOrdinal) { AutoTdpProfile.BALANCED }
            val targetMw = intent.getLongExtra(EXTRA_TARGET_MW, -1L).let { if (it < 0) null else it }
            val goal = intent.getStringExtra(EXTRA_GOAL)?.let { name ->
                GoalProfile.entries.firstOrNull { it.name == name }
            }
            return AutoTdpProfileConfig(profile = profile, targetMilliWatts = targetMw, goal = goal)
        }

        fun start(context: Context, config: AutoTdpProfileConfig) {
            val intent = buildStartIntent(context, config)
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
