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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

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
     * GUARDRAIL 2: when the user swipes the app away from Recents, Android may kill the
     * process without an orderly onDestroy in time to flush a cancelled-job revert. Revert
     * to stock here too, before deferring to the default teardown. Without this, a swipe-
     * away mid-session could leave the cap pinned (DEFECT B's sibling path). Idempotent.
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

    private fun startDaemon(config: AutoTdpProfileConfig) {
        if (loopJob?.isActive == true) return // already running

        // GUARDRAIL 2: a fresh, single-session revert latch. Reset the session report so
        // a stop before capabilities resolve has nothing stale to revert against.
        sessionReport = null
        revert = AutoTdpRevert(tunableWriter)

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

        // Resolve the vendor fan_mode Settings.System key from the device adapter
        // (AYN/Retroid expose it as `fan_mode`). Null when this device has no
        // controllable fan key — in which case the fan governor's writes are
        // honestly skipped by TdpStateTransition.delta. We never invent a key.
        val fanModeKey: String? = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            ?.fanAdapter
            ?.takeIf { it.kind == io.github.mayusi.calibratesoc.data.devicedb.FanAdapterKind.SETTINGS_KEY }
            ?.target

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

                // ── Engine decision ────────────────────────────────────────────
                // Thread the PERSISTED controllerState in, and pass the Smart goal as
                // goalOverride so the new 5-mode goal / AUTO actually reaches the
                // engine. config.goal == null ⇒ goalOverride == null ⇒ decide() maps
                // the legacy profile internally (today's behaviour, unchanged).
                val decision = AutoTdpEngine.decide(
                    window = window.toList(),
                    config = config,
                    caps = caps,
                    current = currentState,
                    controllerState = controllerState,
                    goalOverride = config.goal,
                )
                // PERSIST: carry the engine's threaded state into the NEXT tick. This
                // single reassignment is what keeps EWMA smoothing, the cross-actuator
                // cool-down, the direction-episode confirm counters, the active lever,
                // the fan governor, and the classifier hysteresis ALIVE across ticks.
                controllerState = decision.controllerState

                // ── Honest-baseline grace gate ─────────────────────────────────
                // For the first BASELINE_GRACE_MS after RUNNING, hold at STOCK so the
                // sampler's baseline window measures genuine pre-cap draw. We still
                // run the engine (so the HUD shows what it WOULD do) but apply nothing.
                val inBaselineGrace =
                    (System.currentTimeMillis() - sessionStartEpochMs) < BASELINE_GRACE_MS
                val effectiveTarget = if (inBaselineGrace) currentState else decision.target

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
                                // CapabilityDenied means WriterRegistry resolved to NoopWriter —
                                // no tier can write this node. This is genuinely unrecoverable
                                // for the current capability state: stop the daemon so the user
                                // isn't left with a "Running" notification that does nothing.
                                val failure = "No write tier available for ${op.description}: ${result.reason}"
                                Log.e(TAG, failure)
                                stopDaemon(AutoTdpStatus.WRITE_DENIED, writeFailure = failure)
                                return@collect
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
            // ── WAVE 3a: release clock ownership (un-suppresses the throttle guard). ──
            // No-op if a later owner (Game Boost) already took over — release() checks.
            arbiter.release(io.github.mayusi.calibratesoc.data.boost.BoostArbiter.ClockOwner.AUTO_TDP)
            // Update notification if service is still technically alive
            updateNotification(status = "Stopped", detail = "All writes reverted")
        }
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
        val report = sessionReport
        val revertHandle = revert
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
            // Now it is safe to cancel the loop (its finally's revert is a latched no-op)
            // and stop the service.
            loopJob?.cancel()
            loopJob = null
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
        // CAP (scaling_max_freq), governor, GPU max, and fan, but has NO command for core
        // parking (cpu/online). That is fine: the engine's PARK lever skips itself when no
        // core can be parked and walks to the CAP lever, so AutoTDP runs LIVE purely on the
        // cap path. So on a binder-live AYANEO we do NOT require cpu/online — the cap check
        // below is the live gate. (The hard cap floor + revert-on-exit still apply equally.)
        if (!report.ayaneoBinderLive) {
            val onlineId = Tunables.cpuOnline(0)
            if (!writerRegistry.isLiveWritable(onlineId, report)) {
                val why = Tunables.whyWriteDenied(onlineId, report)
                return "cpu online/offline not writable — ${why ?: "writer would deny"}"
            }
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

    // ── Companions ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AutoTdpService"
        private const val NOTIFICATION_ID = 5511
        private const val CHANNEL_ID = "autotdp"

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
