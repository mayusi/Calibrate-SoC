package io.github.mayusi.calibratesoc.data.monitor

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.MonitorService.Companion.SHARE_STOP_TIMEOUT_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/**
 * PERF-1 regression coverage for the single-shared-telemetry win.
 *
 * [MonitorService] now routes every DEFAULT_INTERVAL_MS subscriber through ONE
 * process-shared hot flow ([MonitorService.sharedDefaultTelemetry]) built with
 * `shareIn(scope, WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)`. Before
 * the fix, each of the ~11 default-interval callers spawned its own cold polling
 * loop reading every sysfs node every second — 4-5 duplicate loops with several
 * screens open.
 *
 * The samplers MonitorService composes are all Android/sysfs-backed, so we can't
 * instantiate the real service in a pure-JVM test. Instead we pin the exact
 * `shareIn` configuration the fix depends on over an instrumented upstream and
 * assert the load-bearing invariants:
 *
 *  1. N simultaneous collectors share ONE upstream activation (the polling loop
 *     runs once, not once-per-collector).
 *  2. All collectors observe the same emitted samples.
 *  3. The upstream stops after the last subscriber leaves (`WhileSubscribed`),
 *     so a backgrounded app polls nothing.
 *  4. The shared constant the fix relies on is the documented 5 s grace window.
 *
 * The `shareIn` upstream and the collectors run on [backgroundScope] — the scope
 * `runTest` provides for never-completing coroutines — so they don't trip the
 * uncompleted-coroutine check at the end of the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedTelemetryFlowTest {

    /** Mirrors `MonitorService.sharedDefaultTelemetry`'s shareIn parameters. */
    private fun <T> shareLikeMonitorService(scope: CoroutineScope, upstream: Flow<T>) =
        upstream.shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARE_STOP_TIMEOUT_MS),
            replay = 1,
        )

    @Test
    fun `multiple collectors share ONE upstream polling loop`() = runTest {
        val upstreamActivations = AtomicInteger(0)
        val ticks = AtomicInteger(0)

        // A cold flow that records how many independent loops are started (each
        // collect of a COLD flow runs the block; a SHARED flow runs it once).
        val cold = flow {
            upstreamActivations.incrementAndGet()
            while (true) {
                emit(ticks.getAndIncrement())
                delay(MonitorService.DEFAULT_INTERVAL_MS)
            }
        }

        val shared = shareLikeMonitorService(backgroundScope, cold)

        val a = mutableListOf<Int>()
        val b = mutableListOf<Int>()
        val c = mutableListOf<Int>()
        val jobA = backgroundScope.launch { shared.collect { a.add(it) } }
        val jobB = backgroundScope.launch { shared.collect { b.add(it) } }
        val jobC = backgroundScope.launch { shared.collect { c.add(it) } }

        // Advance virtual time over several ticks so all three collectors run.
        advanceTimeBy(MonitorService.DEFAULT_INTERVAL_MS * 4)
        runCurrent()

        jobA.cancel(); jobB.cancel(); jobC.cancel()

        // THE perf invariant: 3 collectors, ONE upstream loop (not 3).
        assertThat(upstreamActivations.get()).isEqualTo(1)
        // All three observed real samples...
        assertThat(a).isNotEmpty()
        assertThat(b).isNotEmpty()
        assertThat(c).isNotEmpty()
        // ...and they observed the SAME stream (same first sample via replay/fan-out).
        assertThat(a.first()).isEqualTo(b.first())
        assertThat(b.first()).isEqualTo(c.first())
    }

    @Test
    fun `upstream stops after the last subscriber leaves`() = runTest {
        val active = AtomicInteger(0)
        val cold = flow {
            active.incrementAndGet()
            try {
                while (true) {
                    emit(0)
                    delay(MonitorService.DEFAULT_INTERVAL_MS)
                }
            } finally {
                active.decrementAndGet()
            }
        }
        val shared = shareLikeMonitorService(backgroundScope, cold)

        val job = backgroundScope.launch { shared.collect { } }
        advanceTimeBy(MonitorService.DEFAULT_INTERVAL_MS * 2)
        runCurrent()
        assertThat(active.get()).isEqualTo(1) // upstream running while subscribed

        job.cancel()
        // Past the WhileSubscribed grace window the upstream must stop.
        advanceTimeBy(SHARE_STOP_TIMEOUT_MS + 1_000L)
        runCurrent()
        assertThat(active.get()).isEqualTo(0) // backgrounded app polls nothing
    }

    @Test
    fun `a late subscriber that joins within the grace window reuses the running loop`() = runTest {
        val activations = AtomicInteger(0)
        val cold = flow {
            activations.incrementAndGet()
            while (true) {
                emit(0)
                delay(MonitorService.DEFAULT_INTERVAL_MS)
            }
        }
        val shared = shareLikeMonitorService(backgroundScope, cold)

        val first = backgroundScope.launch { shared.collect { } }
        advanceTimeBy(MonitorService.DEFAULT_INTERVAL_MS * 2)
        runCurrent()
        first.cancel()
        // Re-subscribe BEFORE the 5 s grace window expires.
        advanceTimeBy(SHARE_STOP_TIMEOUT_MS / 2)
        runCurrent()
        val second = backgroundScope.launch { shared.collect { } }
        advanceTimeBy(MonitorService.DEFAULT_INTERVAL_MS * 2)
        runCurrent()
        second.cancel()

        // Only ONE upstream activation across the churn — the loop was reused.
        assertThat(activations.get()).isEqualTo(1)
    }

    @Test
    fun `share stop timeout is the documented 5 second grace window`() {
        assertThat(SHARE_STOP_TIMEOUT_MS).isEqualTo(5_000L)
    }
}
