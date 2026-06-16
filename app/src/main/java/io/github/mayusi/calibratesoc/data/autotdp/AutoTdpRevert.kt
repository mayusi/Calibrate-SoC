package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GUARDRAIL 2 — revert-on-EVERY-exit, under [NonCancellable].
 *
 * DEFECT B was sticky bad state: [TunableWriter.revertAll] WAS wired into the daemon's
 * `finally`, but every stop path reached it via `loopJob.cancel()`, and the revert
 * writes route through [io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter]
 * which wraps each write in `withContext(Dispatchers.IO)`. On an already-cancelled job
 * that `withContext` throws [kotlinx.coroutines.CancellationException] BEFORE the write
 * runs, so the revert was silently skipped. The 384 MHz cap stayed pinned until a reboot
 * (only [io.github.mayusi.calibratesoc.data.tunables.BootRevertReceiver] actually reverted).
 *
 * This helper makes the revert SURVIVE cancellation: it runs under
 * `NonCancellable + Dispatchers.IO`, so the inner per-write `withContext(Dispatchers.IO)`
 * inherits a non-cancelled [kotlinx.coroutines.Job] and the writes actually land. It is
 * IDEMPOTENT (an [AtomicBoolean] latch) so the many stop paths — stopDaemon, onDestroy,
 * onTaskRemoved, the runDaemon `finally` — can all call it without double-writing.
 *
 * The revert restores the JOURNALED ORIGINALS (stock kernel values), not any intermediate
 * tuned state — [TunableWriter.revertAll] walks the snapshot journal in reverse and writes
 * each `previousValue` back. So even a thermal-kill or battery-kill exit hands control
 * cleanly back to the kernel governor at STOCK, which is the safe state.
 *
 * PURE except for the (injected) [TunableWriter]: no Android Service, no Context, no
 * android.util.Log — so the NonCancellable-survives-cancellation behaviour is a plain
 * JVM unit test. The caller (the service) logs the returned summary.
 */
class AutoTdpRevert(
    private val tunableWriter: TunableWriter,
) {
    /** Latched true the first time a revert actually runs, so it is done at most once. */
    private val reverted = AtomicBoolean(false)

    /**
     * Revert all journaled writes to their stock originals, exactly once.
     *
     * Runs under `NonCancellable + Dispatchers.IO` so it completes even when the caller's
     * coroutine/job is already cancelled (the stopDaemon → loopJob.cancel() path). Safe to
     * call from any number of exit paths; subsequent calls after the first are no-ops.
     *
     * @return the [TunableWriter.RevertSummary] of the run, or null when a prior call
     *         already performed the revert (idempotent no-op).
     */
    suspend fun revertNow(report: CapabilityReport): TunableWriter.RevertSummary? {
        if (!reverted.compareAndSet(false, true)) {
            return null
        }
        return withContext(NonCancellable + Dispatchers.IO) {
            tunableWriter.revertAll(report)
        }
    }
}
