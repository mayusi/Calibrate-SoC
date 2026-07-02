package io.github.mayusi.calibratesoc.data.autotdp

import android.util.Log
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AutoTdpRevert"

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
 * ## 0.3.6 — AYANEO perf-mode UNCONDITIONAL stock re-assert
 *
 * [TunableWriter.revertAll] is DELTA-based: it only replays the journal entries THIS
 * process's writes appended. On the AYANEO PERF-MODE lever that is a real hazard, not a
 * cosmetic gap — [AyaneoVendorWriter.read] durably seeds the journal with the stock mode
 * (1), so a normal session that DID write the mode reverts correctly. But a run that made
 * NO perf-mode delta this session (e.g. a prior run already parked the device at mode 0
 * and this run's daemon died — killed/crashed — before ever calling `write()`) has NOTHING
 * in the journal for this tunable, so `revertAll` walks right past it: `ok=0 failed=0`, a
 * true no-op, and the device stays parked at a non-stock vendor mode with no automatic
 * recovery.
 *
 * So on AYANEO, [revertNow] does NOT rely on the delta ledger alone: after `revertAll`
 * runs, it unconditionally re-sends `com_set_performance_mode:1` (stock/balanced) via the
 * SAME [tunableWriter] → [io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry]
 * → [io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter] path
 * every other write uses (no bespoke binder call here). Idempotent: if the vendor
 * `currentMode` is already 1, the readback-verify still succeeds and the write is a
 * harmless no-op at the hardware level. Gated on [CapabilityReport.ayaneoBinderLive] so
 * Odin/RP6/root and non-AYANEO devices are completely unaffected — they have their own
 * (fully delta-correct) revert.
 *
 * PURE except for the (injected) [TunableWriter]: no Android Service, no Context — so the
 * NonCancellable-survives-cancellation behaviour is a plain JVM unit test. The caller (the
 * service) logs the returned summary.
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
            val summary = tunableWriter.revertAll(report)
            // 0.3.6: unconditional AYANEO stock re-assert — see class doc. Runs regardless
            // of whether revertAll found (or lacked) a journaled delta for the perf-mode
            // tunable, so the `ok=0 failed=0` empty-ledger case can never leave the device
            // parked at a non-stock mode.
            if (report.ayaneoBinderLive) {
                reassertAyaneoStockMode(report)
            }
            summary
        }
    }

    /**
     * Unconditionally re-send the AYANEO stock performance mode (1 = balanced) through the
     * normal write path. Best-effort: any failure is logged and swallowed — it must never
     * throw out of the NonCancellable revert block or block the rest of the exit sequence
     * (the daemon still needs to cancel its loop and stop the service after this).
     */
    private suspend fun reassertAyaneoStockMode(report: CapabilityReport) {
        runCatching {
            tunableWriter.write(
                id = Tunables.ayaPerformanceMode(),
                value = AyaneoCommands.PERF_MODE_STOCK.toString(),
                report = report,
                reason = "AutoTDP revert: unconditional AYANEO stock re-assert",
            )
        }.onFailure {
            Log.w(TAG, "reassertAyaneoStockMode: write threw ${it.javaClass.simpleName}: ${it.message}")
        }.onSuccess { result ->
            Log.i(TAG, "reassertAyaneoStockMode: $result")
        }
    }
}
