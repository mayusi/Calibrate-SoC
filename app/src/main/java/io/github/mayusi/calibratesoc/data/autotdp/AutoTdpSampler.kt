package io.github.mayusi.calibratesoc.data.autotdp

import android.util.Log
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures the draw-delta between "AutoTDP OFF" (baseline) and "AutoTDP ON"
 * (tuned) under the same load, using real [batteryDrawMilliW] samples from
 * [MonitorService].
 *
 * Protocol:
 *   1. Collect ~[SAMPLE_DURATION_S] seconds of baseline samples (daemon not yet running).
 *   2. Wait for the daemon to be RUNNING.
 *   3. Collect ~[SAMPLE_DURATION_S] seconds of tuned samples.
 *   4. Call [AutoTdpSavings.computeSavings] — pure math, no Android.
 *   5. Push the [SavingsResult] to [AutoTdpController.updateSavings].
 *
 * This class does NOT start or stop the daemon. It is driven by the UI or by
 * [AutoTdpController] calling [runOnce]. The result is always labeled as
 * "measured on your device, this session" — never fabricated.
 *
 * Unit notes:
 *   - [AutoTdpSavings.computeSavings] is pure and tested separately.
 *   - The sampling loop itself is thin coroutine plumbing — no extractable pure logic.
 *   - We expose [lastResult] as a snapshot for tests that inject a fake MonitorService.
 */
@Singleton
class AutoTdpSampler @Inject constructor(
    private val monitorService: MonitorService,
    private val controller: AutoTdpController,
) {

    private val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplerJob: Job? = null

    /**
     * Real-game FPS source for the probe. Defaults to [RealFpsSupplier.NONE]
     * (always null → [AutoTdpEffect.fpsDelta] stays null and the UI hides it).
     *
     * The data layer has no FPS source of its own (FPS lives in the overlay-layer
     * GameFpsSampler), so this is settable: the HUD can bridge its real-FPS
     * StateFlow in while the overlay is active. HONESTY: the supplier MUST return
     * null for any non-real (refresh-rate fallback) FPS.
     */
    @Volatile
    var realFpsSupplier: RealFpsSupplier = RealFpsSupplier.NONE

    /** Most recent savings result; null before first completed measurement. */
    var lastResult: SavingsResult? = null
        private set

    /**
     * Start a one-shot baseline→tuned measurement cycle.
     * If a cycle is already in progress, it is cancelled and a new one begins.
     *
     * @param onResult  Called on the IO thread when the result is ready.
     *                  The controller is also updated, so this is optional.
     */
    fun runOnce(onResult: ((SavingsResult) -> Unit)? = null) {
        samplerJob?.cancel()
        samplerJob = samplerScope.launch {
            val probe = measure()
            lastResult = probe.savings
            // Push savings + measured temp/fps deltas in one shot so the effect's
            // MEASURED fields land atomically with the savings.
            controller.updateProbeResult(probe)
            onResult?.invoke(probe.savings)
        }
    }

    fun cancel() {
        samplerJob?.cancel()
        samplerJob = null
    }

    // ── Core measurement ──────────────────────────────────────────────────────

    /**
     * Run the full baseline→tuned probe and compute savings + measured temp/fps
     * deltas.
     *
     * BASELINE ORDERING (honest framing): the baseline window is the FIRST
     * [SAMPLE_DURATION_S] seconds after [runOnce] is invoked. When the caller kicks
     * the probe BEFORE the daemon starts writing (the preferred ordering, done in
     * AutoTdpService.start), this is a genuine pre-cap baseline. When the daemon is
     * already RUNNING, the baseline reflects early-session light load — still a real
     * on-device measurement, never fabricated. The UI labels it "measured on your
     * device, this session".
     */
    private suspend fun measure(): ProbeResult {
        Log.i(TAG, "Starting baseline window (${SAMPLE_DURATION_S}s)")
        val baseline = collectWindow()
        Log.i(TAG, "Baseline: ${baseline.drawMilliW.size} draw samples")

        // Wait for daemon to be RUNNING (or give up after WAIT_FOR_DAEMON_MS).
        Log.i(TAG, "Waiting for AutoTDP daemon to be RUNNING…")
        val daemonStarted = waitForDaemonRunning()
        if (!daemonStarted) {
            Log.w(TAG, "Daemon not running after ${WAIT_FOR_DAEMON_MS}ms — returning not-enough-data")
            return ProbeResult(
                savings = AutoTdpSavings.computeSavings(emptyList(), emptyList()),
                tempDeltaC = null,
                fpsDelta = null,
            )
        }

        // Small settling delay so the daemon's first writes have taken effect.
        delay(SETTLE_DELAY_MS)

        Log.i(TAG, "Starting tuned window (${SAMPLE_DURATION_S}s)")
        val tuned = collectWindow()
        Log.i(TAG, "Tuned: ${tuned.drawMilliW.size} draw samples")

        val savings = AutoTdpSavings.computeSavings(baseline.drawMilliW, tuned.drawMilliW)
        // Temp/fps deltas are honest-by-construction: null whenever either window
        // lacked the corresponding measurement.
        val tempDeltaC = AutoTdpProbe.tempDeltaC(baseline, tuned)
        val fpsDelta = AutoTdpProbe.fpsDelta(baseline, tuned)

        return ProbeResult(savings = savings, tempDeltaC = tempDeltaC, fpsDelta = fpsDelta)
    }

    /**
     * Collects telemetry for approximately [SAMPLE_DURATION_S] seconds and reduces
     * it (plus the matching real-FPS readings) into a [ProbeWindow] via the pure
     * [AutoTdpProbe.aggregate]. The window may be shorter if the daemon is killed
     * or the scope is cancelled.
     */
    private suspend fun collectWindow(): ProbeWindow {
        val sampleCount = (SAMPLE_DURATION_S * 1000L / MonitorService.DEFAULT_INTERVAL_MS).toInt()
            .coerceAtLeast(AutoTdpSavings.MIN_SAMPLES_FOR_REPORT + 1)

        val samples = ArrayList<Telemetry>(sampleCount)
        val fpsPerTick = ArrayList<Int?>(sampleCount)
        monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS)
            .take(sampleCount)
            .toList()
            .forEach { sample ->
                samples += sample
                // Sample real FPS once per tick alongside the telemetry. Null when
                // not a genuine measurement (honest hide downstream).
                fpsPerTick += realFpsSupplier.currentRealFps()
            }
        return AutoTdpProbe.aggregate(samples, fpsPerTick)
    }

    /**
     * Waits for [AutoTdpController.state] to reach [AutoTdpStatus.RUNNING].
     * Returns true if it does within [WAIT_FOR_DAEMON_MS], false on timeout.
     */
    private suspend fun waitForDaemonRunning(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_FOR_DAEMON_MS
        while (System.currentTimeMillis() < deadline) {
            val current = controller.state.value
            if (current.status == AutoTdpStatus.RUNNING) return true
            delay(500)
        }
        return controller.state.value.status == AutoTdpStatus.RUNNING
    }

    companion object {
        private const val TAG = "AutoTdpSampler"

        /**
         * Duration to collect samples for each phase (baseline + tuned).
         * 20 s at 1 Hz = 20 samples, well above [AutoTdpSavings.MIN_SAMPLES_FOR_REPORT].
         */
        const val SAMPLE_DURATION_S = 20

        /** How long to wait for the daemon to reach RUNNING after baseline. */
        private const val WAIT_FOR_DAEMON_MS = 10_000L

        /** Settle time after daemon start before tuned sampling begins. */
        private const val SETTLE_DELAY_MS = 2_000L
    }
}
