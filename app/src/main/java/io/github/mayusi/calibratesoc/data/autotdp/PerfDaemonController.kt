package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WAVE 3A — SESSION-LEVEL managed perfd lifecycle for AutoTDP.
 *
 * ## Why this exists
 *
 * The vendor perf daemons (`perfd`, `vendor.perf-hal-1-0/1/2`, `vendor.perfservice`)
 * continuously re-assert the kernel's cpufreq cap, clobbering AutoTDP's writes
 * milliseconds after we make them. Today [TunableWriter] handles this with a
 * PER-WRITE `stop <daemon>` … write … `start <daemon>` sandwich (see
 * `TunableWriter.writeProtocolFor`). That works but THRASHES the daemons — it
 * stops and restarts them on every single cpufreq tick (~1 Hz), and in the
 * restart window between two ticks the daemon is briefly free to fight back.
 *
 * On a PServer-LIVE device we can do better: stop the daemons ONCE when AutoTDP
 * ENGAGES and restart them ONCE when it DISENGAGES — surgical and reversible.
 * For the duration of the session the cap stays ours, with no per-tick restart
 * window for the daemon to clobber through.
 *
 * ## SAFETY — restart on EVERY exit, guaranteed (the 384 MHz-fix discipline)
 *
 * Stopping a vendor perf daemon is a paired operation: if we stop it and the
 * coroutine is then cancelled/crashed before the `start`, the device is left
 * with vendor perf management DISABLED until reboot — exactly the class of
 * sticky-bad-state the 384 MHz collapse taught us to never allow. So this
 * controller mirrors [AutoTdpRevert] EXACTLY:
 *
 *   - [restoreForSession] runs under `NonCancellable + Dispatchers.IO`, so it
 *     completes even when the caller's job is already cancelled (the
 *     stopDaemon → loopJob.cancel() path, onDestroy, onTaskRemoved).
 *   - It is IDEMPOTENT (an [AtomicBoolean] latch): the four AutoTDP exit paths
 *     — stopDaemon, onDestroy, onTaskRemoved, the runDaemon `finally` — can all
 *     call it; only the first does work, the rest are no-ops.
 *   - It only restarts daemons it ACTUALLY stopped (a second latch): if the
 *     stop never ran (not PServer-live, no daemons declared, binder gone), the
 *     restore is a pure no-op — we never `start` a daemon we didn't `stop`.
 *
 * ## Every command goes through the guard
 *
 * All `stop`/`start` commands are issued via [PServerWriter.executeShell] →
 * `PServerWriter.transact` → `PServerCommandGuard`. The guard's allow-list
 * already permits `stop`/`start <perf-daemon>` and ONLY perf daemons
 * (`allowDaemonControl`), so a malformed or non-perf daemon name is denied at
 * the chokepoint — this controller cannot emit anything destructive.
 *
 * ## Suppressing the per-write dance while the session stop is active
 *
 * While the session-level stop is in effect, [TunableWriter]'s per-write
 * `start <daemon>` post-hook would RESTART the daemons mid-session and defeat
 * the whole point. So [stopForSession] sets [TunableWriter.setPerfDaemonsSessionStopped]
 * `true` (suppressing only the daemon stop/start hooks — the chmod-lock stays,
 * as a second line of defence against any daemon we couldn't stop), and
 * [restoreForSession] clears it. The flag is always cleared in the same
 * NonCancellable block that restarts the daemons, so it can never be left set.
 *
 * PURE except for the injected [TunableWriter] / [PServerWriter]: no Android
 * Service, no Context, no android.util.Log — so the NonCancellable-survives-
 * cancellation behaviour is a plain JVM unit test. The caller (the service)
 * logs the outcome.
 *
 * @param daemons the vendor perf daemons to stop/start, sourced from the device
 *   adapter's `perfDaemonsToStopOnWrite`. Empty ⇒ this controller is a no-op
 *   (stock AOSP kernel, no daemon dance needed).
 */
class PerfDaemonController(
    private val pServerWriter: PServerWriter,
    private val tunableWriter: TunableWriter,
    private val daemons: List<String>,
) {
    /** Latched true the first time [stopForSession] actually stops daemons. */
    private val stopped = AtomicBoolean(false)

    /** Latched true the first time [restoreForSession] runs, so restore is once-only. */
    private val restored = AtomicBoolean(false)

    /**
     * Stop the vendor perf daemons for the whole AutoTDP session — ONCE.
     *
     * No-op (returns false) when:
     *   - there are no daemons to manage ([daemons] empty), OR
     *   - [enabled] is false (caller decided the session-level stop doesn't help
     *     on this tier — e.g. NOT pserverSysfsLive, where the per-write dance is
     *     already the right tool), OR
     *   - it has already run this session.
     *
     * Sets [TunableWriter.setPerfDaemonsSessionStopped] true BEFORE issuing the
     * stops, so the very first per-write post-hook cannot restart what we are
     * about to stop. On a failed/circuit-broken `executeShell` we still count
     * the session as "stopped" so [restoreForSession] will issue the `start`
     * (better to over-start a daemon than to leave it stopped).
     *
     * @return true when this call performed the stop; false on a no-op.
     */
    suspend fun stopForSession(enabled: Boolean): Boolean {
        if (!enabled || daemons.isEmpty()) return false
        if (!stopped.compareAndSet(false, true)) return false
        return withContext(Dispatchers.IO) {
            // Suppress the per-write daemon dance for the duration — the session-level
            // stop owns the daemons now; a per-tick `start` would clobber through.
            tunableWriter.setPerfDaemonsSessionStopped(true)
            for (d in daemons) {
                // Guard allow-lists `stop <perf-daemon>`; a null result is a binder
                // blip / circuit-breaker — we proceed (the restore still issues start).
                pServerWriter.executeShell("stop $d")
            }
            true
        }
    }

    /**
     * Restart the vendor perf daemons and clear the per-write suppression — ONCE,
     * under [NonCancellable] so it lands even from an already-cancelled job.
     *
     * Idempotent: subsequent calls are no-ops. Only issues `start` for daemons we
     * actually stopped this session (the [stopped] latch) — we never start a
     * daemon we didn't stop. The suppression flag is ALWAYS cleared here (even when
     * no stop ran), so a stray-set flag can never survive a session.
     *
     * @return true when this call performed the restore (or cleared the flag);
     *   false on the latched no-op.
     */
    suspend fun restoreForSession(): Boolean {
        if (!restored.compareAndSet(false, true)) return false
        return withContext(NonCancellable + Dispatchers.IO) {
            // Always clear the suppression so the per-write dance resumes for the
            // next session — even if we never issued a session-level stop.
            tunableWriter.setPerfDaemonsSessionStopped(false)
            if (stopped.get()) {
                for (d in daemons) {
                    pServerWriter.executeShell("start $d")
                }
            }
            true
        }
    }
}
