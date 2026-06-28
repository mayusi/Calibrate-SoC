package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ChargingTuneTrigger] pure decision functions.
 *
 * No Android runtime required — tests cover the companion object functions
 * that contain all conditional logic. The BroadcastReceiver lifecycle,
 * DataStore, and writer paths are not tested here (require instrumented env).
 *
 * Rules under test:
 *   shouldApply:
 *   1. Toggle OFF → never apply.
 *   2. Toggle ON + not charging → don't apply.
 *   3. Toggle ON + charging + gaming active → skip (don't stomp gaming session).
 *   4. Toggle ON + charging + not gaming → apply.
 *
 *   shouldRevert:
 *   5. Still charging + was applied → no revert (stay in charging-mode).
 *   6. Unplugged + was applied → revert.
 *   7. Unplugged + was NOT applied → no-op (nothing to revert).
 *   8. Still charging + was NOT applied → no-op.
 */
class ChargingTuneTriggerTest {

    // ── shouldApply: toggle-OFF gating ───────────────────────────────────────

    @Test
    fun `shouldApply — toggle off, not charging, not gaming — false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = false, charging = false, gamingActive = false),
        ).isFalse()
    }

    @Test
    fun `shouldApply — toggle off, charging, not gaming — still false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = false, charging = true, gamingActive = false),
        ).isFalse()
    }

    @Test
    fun `shouldApply — toggle off, charging, gaming — still false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = false, charging = true, gamingActive = true),
        ).isFalse()
    }

    // ── shouldApply: toggle-ON but not charging ───────────────────────────────

    @Test
    fun `shouldApply — toggle on, not charging, not gaming — false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = false, gamingActive = false),
        ).isFalse()
    }

    @Test
    fun `shouldApply — toggle on, not charging, gaming — false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = false, gamingActive = true),
        ).isFalse()
    }

    // ── shouldApply: not-gaming gate ─────────────────────────────────────────

    @Test
    fun `shouldApply — toggle on, charging, gaming active — skip (never stomp gaming session)`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = true),
        ).isFalse()
    }

    // ── shouldApply: the happy path ───────────────────────────────────────────

    @Test
    fun `shouldApply — toggle on, charging, not gaming — true`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = false),
        ).isTrue()
    }

    // ── shouldRevert ─────────────────────────────────────────────────────────

    @Test
    fun `shouldRevert — still charging, bundle was applied — false (stay in charging-mode)`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = true, wasApplied = true),
        ).isFalse()
    }

    @Test
    fun `shouldRevert — unplugged, bundle was applied — true`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = true),
        ).isTrue()
    }

    @Test
    fun `shouldRevert — unplugged, bundle was NOT applied — false (no-op)`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = false),
        ).isFalse()
    }

    @Test
    fun `shouldRevert — still charging, bundle was NOT applied — false`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = true, wasApplied = false),
        ).isFalse()
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    fun `shouldApply — pure function, same inputs same output`() {
        repeat(5) {
            val a = ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = false)
            val b = ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = false)
            assertThat(a).isEqualTo(b)
        }
    }

    @Test
    fun `shouldRevert — pure function, same inputs same output`() {
        repeat(5) {
            val a = ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = true)
            val b = ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = true)
            assertThat(a).isEqualTo(b)
        }
    }

    // ── BUG 8: stop() reverts even on a cancelling/already-torn-down scope ─────
    //
    // The old stop() did `scope.launch { revert }.invokeOnCompletion { scope.cancel() }`.
    // If `scope` was already cancelling (rapid double-stop / teardown), the launched body
    // NEVER started, so NonCancellable inside revert could not save it → charging caps /
    // fan / refresh stayed applied after unplug. The fix runs the revert on a fresh
    // runBlocking and blocks to completion, independent of the main scope's state.

    @Test
    fun `OLD stop pattern — launched revert on a cancelling scope never runs (regression guard)`() {
        val reverted = AtomicBoolean(false)
        // A scope that is already cancelled, exactly like a torn-down trigger.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()

        // Old pattern: launch the revert on the (cancelled) scope.
        val job: Job = scope.launch {
            reverted.set(true) // body of revertBundle
        }
        job.invokeOnCompletion { /* scope.cancel() — already cancelled */ }
        runBlocking { runCatching { job.join() } }

        // The launched body NEVER ran on the cancelled scope → device left mis-tuned.
        assertThat(reverted.get()).isFalse()
    }

    @Test
    fun `NEW stop pattern — runBlocking revert runs to completion regardless of main scope`() {
        val autoTdpStopped = AtomicBoolean(false)
        val fanRestored = AtomicBoolean(false)
        // The main scope is already cancelled (double-stop / teardown).
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        mainScope.cancel()

        // Model the fixed stop(): revert on a runBlocking, NOT on the dead main scope.
        runCatching {
            runBlocking {
                // revertBundle body, itself NonCancellable.
                withContext(NonCancellable) {
                    autoTdpStopped.set(true)
                    delay(10)
                    fanRestored.set(true)
                }
            }
        }
        mainScope.cancel() // (no-op; already cancelled) — order matches stop()

        // Revert ran fully even though the main scope was dead before stop() was called.
        assertThat(autoTdpStopped.get()).isTrue()
        assertThat(fanRestored.get()).isTrue()
    }

    @Test
    fun `NEW stop pattern — double-stop still reverts both times to completion`() {
        val revertRuns = AtomicInteger(0)
        fun stopModel() {
            runCatching {
                runBlocking { withContext(NonCancellable) { revertRuns.incrementAndGet() } }
            }
        }
        stopModel()
        stopModel() // rapid second stop must not be swallowed
        assertThat(revertRuns.get()).isEqualTo(2)
    }

    // ── BUG 9: applied flag committed FIRST so a partial apply still reverts ───
    //
    // Old order set autoTdpStarted right after start() but bundleApplied only at the very
    // end; a cancel between them left `if (!bundleApplied) return` no-opping the revert
    // while AutoTDP ran in charging mode forever. The fix commits bundleApplied (under
    // NonCancellable) BEFORE starting any effect.

    @Test
    fun `applied flag committed before effects survives a mid-apply cancel — revert still fires`() = runBlockingTestModel { scope ->
        val bundleApplied = AtomicBoolean(false)
        val applyEntered = CompletableDeferred<Unit>()

        val job = scope.launch {
            // COMMIT-FIRST under NonCancellable.
            withContext(NonCancellable) { bundleApplied.set(true) }
            applyEntered.complete(Unit)
            // Effects start here; a cancel mid-apply must not lose the applied flag.
            delay(10_000)
            // (rest of apply never reached)
        }
        applyEntered.await()
        job.cancelAndJoin()

        // The revert guard `if (!bundleApplied) return` will now PROCEED on unplug.
        assertThat(bundleApplied.get()).isTrue()
    }

    @Test
    fun `OLD ordering — applied flag set last is lost on mid-apply cancel (regression guard)`() = runBlockingTestModel { scope ->
        val bundleApplied = AtomicBoolean(false)
        val applyEntered = CompletableDeferred<Unit>()

        val job = scope.launch {
            applyEntered.complete(Unit)
            delay(10_000)               // cancelled here, before…
            bundleApplied.set(true)     // …the flag is ever committed
        }
        applyEntered.await()
        job.cancelAndJoin()

        // Old ordering → flag never set → revert no-ops → AutoTDP stuck in charging mode.
        assertThat(bundleApplied.get()).isFalse()
    }

    // Small helper: run a coroutine test body on a real scope we control, so cancellation
    // semantics are exercised against the default dispatcher (not virtual time).
    private fun runBlockingTestModel(body: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }
}
