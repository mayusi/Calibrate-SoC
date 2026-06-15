package io.github.mayusi.calibratesoc.data.autotdp

import android.util.Log
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
            val result = measure()
            lastResult = result
            controller.updateSavings(result)
            onResult?.invoke(result)
        }
    }

    fun cancel() {
        samplerJob?.cancel()
        samplerJob = null
    }

    // ── Core measurement ──────────────────────────────────────────────────────

    private suspend fun measure(): SavingsResult {
        Log.i(TAG, "Starting baseline sample (${SAMPLE_DURATION_S}s)")
        val baselineMw = collectDrawSamples()
        Log.i(TAG, "Baseline: ${baselineMw.size} samples, mean=${baselineMw.average().toLong()} mW")

        // Wait for daemon to be RUNNING (or give up after WAIT_FOR_DAEMON_MS).
        Log.i(TAG, "Waiting for AutoTDP daemon to be RUNNING…")
        val daemonStarted = waitForDaemonRunning()
        if (!daemonStarted) {
            Log.w(TAG, "Daemon not running after ${WAIT_FOR_DAEMON_MS}ms — returning not-enough-data")
            return AutoTdpSavings.computeSavings(emptyList(), emptyList())
        }

        // Small settling delay so the daemon's first writes have taken effect.
        delay(SETTLE_DELAY_MS)

        Log.i(TAG, "Starting tuned sample (${SAMPLE_DURATION_S}s)")
        val tunedMw = collectDrawSamples()
        Log.i(TAG, "Tuned: ${tunedMw.size} samples, mean=${tunedMw.average().toLong()} mW")

        return AutoTdpSavings.computeSavings(baselineMw, tunedMw)
    }

    /**
     * Collects [batteryDrawMilliW] samples for approximately [SAMPLE_DURATION_S] seconds.
     * Returns a list of valid (>0) mW readings. The list may be shorter if the
     * daemon is killed or the scope is cancelled.
     */
    private suspend fun collectDrawSamples(): List<Long> {
        val sampleCount = (SAMPLE_DURATION_S * 1000L / MonitorService.DEFAULT_INTERVAL_MS).toInt()
            .coerceAtLeast(AutoTdpSavings.MIN_SAMPLES_FOR_REPORT + 1)

        return monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS)
            .take(sampleCount)
            .toList()
            .mapNotNull { it.batteryDrawMilliW }
            .filter { it > 0 }
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
