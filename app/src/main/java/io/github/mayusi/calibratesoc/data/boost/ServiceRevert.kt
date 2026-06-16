package io.github.mayusi.calibratesoc.data.boost

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared revert-on-EVERY-exit latch — the cross-service generalisation of AutoTDP's
 * `AutoTdpRevert` (GUARDRAIL 2). Used by [GameBoostService] and
 * [io.github.mayusi.calibratesoc.data.thermal.ThrottleGuardService] so they cannot
 * leave a `scaling_max_freq` cap (Game Boost: the ceiling PIN; Throttle Guard: a
 * pre-emptive cap) pinned until reboot when the user stops/swipes/kills the service.
 *
 * ## The bug this closes (DEFECT B's siblings)
 *
 * Both services wired [TunableWriter.revertAll] into the daemon `finally`, but every
 * stop path reaches that `finally` via `loopJob.cancel()`. The revert writes route
 * through [io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter], which
 * wraps each write in `withContext(Dispatchers.IO)`. On an ALREADY-cancelled job that
 * `withContext` throws [kotlinx.coroutines.CancellationException] BEFORE the write runs,
 * so the revert was silently skipped and the cap stayed pinned (worse heat/drain for
 * Game Boost, which pins clocks to the ceiling).
 *
 * ## The fix
 *
 * [revertNow] runs the revert under `NonCancellable + Dispatchers.IO`, so the inner
 * per-write `withContext(Dispatchers.IO)` inherits a non-cancelled
 * [kotlinx.coroutines.Job] and the writes actually land. It is IDEMPOTENT (an
 * [AtomicBoolean] latch) so the four exit paths — stopDaemon, onDestroy, onTaskRemoved,
 * the runDaemon `finally` — can all call it without double-writing.
 *
 * Restores the JOURNALED ORIGINALS (stock kernel values): [TunableWriter.revertAll]
 * walks the snapshot journal in reverse and writes each `previousValue` back, so even a
 * thermal-trip or time-box exit hands control cleanly back to the kernel governor at
 * STOCK — the safe state.
 *
 * PURE except for the injected [TunableWriter]: no Android Service, no Context, no
 * android.util.Log — so the NonCancellable-survives-cancellation behaviour is a plain
 * JVM unit test. The caller (the service) logs the returned summary.
 *
 * AutoTDP keeps its own `AutoTdpRevert` (identical behaviour) to avoid disturbing its
 * verified 0.1.30/0.1.32 paths; this is the SAME logic, shared by the two other
 * clock-writing services.
 */
class ServiceRevert(
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
