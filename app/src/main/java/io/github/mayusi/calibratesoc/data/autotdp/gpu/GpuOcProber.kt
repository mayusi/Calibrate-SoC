package io.github.mayusi.calibratesoc.data.autotdp.gpu

import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * UNIT 3 (ADAPTIVE MODE) — the BEYOND-STOCK GPU-OC accept-probe.
 *
 * Answers ONE question honestly: *does this kernel actually honour a beyond-stock devfreq
 * `max_freq` write, and does the GPU clock really rise into the new headroom under load?*
 * Adaptive mode may only resolve [io.github.mayusi.calibratesoc.data.autotdp.adaptive
 * .GpuOcTier.BEYOND_STOCK] when this probe returns [GpuOcVerdict.Accepted].
 *
 * ## Why a live probe (and not a static table)
 *
 * The OPP table can advertise frequencies above the stock ceiling that the kernel will
 * silently clamp, or honour-on-paper but never schedule (governor capped, thermal capped,
 * AVS-limited). Only a controlled-load observation of `cur_freq` proves a real overclock.
 * So we WRITE the raised cap, READ IT BACK (catches the clamp), then run a BOUNDED ~2 s
 * GPU load and watch the clock (catches the no-effect case). Anything short of "clock
 * observed above the stock ceiling" is reported honestly as Unsupported / Rejected /
 * Ineffective — never Accepted.
 *
 * ## Safety is LAW
 *
 *  - **No beyond-stock write is ever left stuck.** The raised `max_freq` is restored to the
 *    stock ceiling on EVERY exit path — Unsupported(after-write is impossible), Rejected,
 *    Ineffective, Accepted, AND any exception. The coordinator re-applies a beyond-stock cap
 *    deliberately afterwards; the probe leaves the node at stock.
 *  - **The load generator is bounded and tracked.** The ~2 s load runs through [LoadGen],
 *    which the prober ALWAYS stops in `finally` — there is no path (including exception or
 *    cancellation) that orphans a synthetic load. (The user's device has been cooked twice
 *    by orphaned load; this is non-negotiable.)
 *  - **All I/O is behind injected interfaces** ([SysfsReader], [SysfsWriter], [LoadGen]) so
 *    the orchestration is testable with fakes, and the *decision* logic is the pure,
 *    free-function [classifyProbe].
 *
 * ## Caching
 *
 * [probe] returns the verdict; it does NOT persist it. Unit 4/5 caches the verdict
 * per-device and re-probes on a kernel/build change (the kernel's clamp behaviour can
 * change across firmware), so a stale Accepted is never trusted after an OTA.
 */
class GpuOcProber {

    /**
     * Reads a single sysfs node as a trimmed string, or null when unreadable. Mirrors the
     * honesty contract of [io.github.mayusi.calibratesoc.data.capability.PrivilegedSysfsReader]
     * (null = genuinely unreadable, never a fabricated value). The real implementation reads
     * `available_frequencies` / `cur_freq` / the `max_freq` readback via the privileged cat.
     */
    fun interface SysfsReader {
        /** @return the trimmed node contents, or null when the node is unreadable. */
        suspend fun read(path: String): String?
    }

    /**
     * Writes a single sysfs node, routing through the app's guarded [io.github.mayusi
     * .calibratesoc.data.tunables.TunableWriter] in production so the snapshot-then-write
     * invariant holds. Returns true when the privilege layer ACCEPTED the command — this is
     * NOT proof the value landed (the prober always reads back to confirm), it only gates
     * whether a readback is worth attempting.
     */
    fun interface SysfsWriter {
        /** @return true when the write was accepted by the privilege layer. */
        suspend fun write(path: String, valueHz: Long): Boolean
    }

    /**
     * A BOUNDED, tracked GPU load. [run] applies sustained GPU load for `durationMs` and
     * returns when it is finished; [stop] is the hard guarantee the load is torn down (no
     * orphan) and is called by the prober in `finally` on every path. The real
     * implementation wraps [io.github.mayusi.calibratesoc.data.benchmark.GpuTriangleStorm]
     * (its `runStress` / `run` render on a background EGL thread that ends with the call,
     * leaving nothing running — [stop] is the belt-and-braces tracked teardown).
     */
    interface LoadGen {
        /** Apply bounded GPU load for [durationMs]. Returns when the load window ends. */
        suspend fun run(durationMs: Long)

        /**
         * Hard stop / teardown of any running load. MUST be safe to call when nothing is
         * running, and MUST leave no synthetic load alive. Called by the prober in
         * `finally` on EVERY exit path (success, every verdict, and exception).
         */
        fun stop()
    }

    /**
     * Run the 4-step accept-probe. PURE decision logic lives in [classifyProbe]; this method
     * is only the I/O orchestration + the always-restore / always-stop cleanup.
     *
     * Steps:
     *  1. Read the OPP list (`available_frequencies`, falling back to [TdpCaps.gpuDevfreqStepsHz]).
     *     ocTarget = max(OPP). If ocTarget <= stock ceiling → [GpuOcVerdict.Unsupported]
     *     (no headroom; force WITHIN_VENDOR). No write happened, nothing to restore.
     *  2. Write `max_freq` = ocTarget, read it back. If readback != ocTarget →
     *     [GpuOcVerdict.Rejected] (kernel clamped). Restore stock ceiling.
     *  3. Under a BOUNDED ~2 s [LoadGen] load, sample `cur_freq` repeatedly; track the max
     *     observed clock. classify: clock ever > stock ceil → [GpuOcVerdict.Accepted];
     *     never → [GpuOcVerdict.Ineffective].
     *  4. ALWAYS restore the stock `max_freq` and ALWAYS [LoadGen.stop] — including on
     *     exception. The verdict is RETURNED (caching is Unit 4/5's job).
     *
     * @return the honest [GpuOcVerdict]; the GPU `max_freq` node is left at the stock
     *         ceiling regardless of outcome.
     */
    suspend fun probe(
        caps: TdpCaps,
        reader: SysfsReader,
        writer: SysfsWriter,
        loadGen: LoadGen,
    ): GpuOcVerdict {
        val ceilHz = caps.gpuDevfreqCeilHz
        val rootPath = caps.gpuRootPath
        // Without a known stock ceiling OR a GPU node path there is no beyond-stock lever to
        // probe at all — honest Unsupported, no write attempted.
        if (ceilHz == null || ceilHz <= 0L || rootPath.isNullOrBlank()) {
            return GpuOcVerdict.Unsupported
        }

        val maxFreqPath = devfreqMaxFreqPath(rootPath)

        // ── Step 1: discover the OC target from the OPP list ───────────────────────────
        val ocTargetHz = readOcTargetHz(reader, rootPath, caps)
        // No OPP above the stock ceiling ⇒ no headroom ⇒ Unsupported. Nothing was written,
        // so there is nothing to restore.
        if (ocTargetHz == null || ocTargetHz <= ceilHz) {
            return GpuOcVerdict.Unsupported
        }

        // From here on a beyond-stock write MAY happen → guarantee restore + load-stop on
        // EVERY path (each verdict and any exception) via the finally block.
        try {
            // ── Step 2: write the raised cap, then read it back ────────────────────────
            val accepted = writer.write(maxFreqPath, ocTargetHz)
            // The privilege layer REFUSED the raise outright → honest Rejected. We must NOT
            // route this through classifyProbe: a writer that records-but-refuses could leave
            // a readback that coincidentally matches, which would misclassify a refusal as
            // Ineffective. A refused beyond-stock write is, by definition, Rejected — the
            // kernel/privilege layer is pinning us at (effectively) the stock ceiling.
            if (!accepted) {
                return GpuOcVerdict.Rejected(clampedTo = ceilHz)
            }
            val readbackHz = reader.read(maxFreqPath)?.trim()?.toLongOrNull()
            // A null readback (unreadable) is treated as "did not confirm the write" → the
            // honest classification is Rejected(clampedTo = ceil): we could not prove the
            // beyond-stock cap landed, so we must NOT proceed to claim anything beyond stock.
            // A non-null readback that differs from what we wrote means the kernel clamped it.
            if (readbackHz == null || readbackHz != ocTargetHz) {
                return classifyProbe(
                    written = ocTargetHz,
                    readback = readbackHz ?: ceilHz,
                    ceilHz = ceilHz,
                    maxObservedHz = 0L,
                )
            }

            // ── Step 3: bounded controlled load, watch the clock ───────────────────────
            val maxObservedHz = runLoadAndSampleMaxClock(reader, loadGen, rootPath)

            // ── Step 4 (classify): readback matched → Accepted vs Ineffective ──────────
            return classifyProbe(
                written = ocTargetHz,
                readback = readbackHz,
                ceilHz = ceilHz,
                maxObservedHz = maxObservedHz,
            )
        } finally {
            // SAFETY (LAW): no beyond-stock write left stuck, no synthetic load orphaned.
            // Restore the stock ceiling and hard-stop the load on EVERY exit — including the
            // exception path. Both are best-effort and must not mask the original outcome.
            try {
                loadGen.stop()
            } catch (_: Throwable) { /* teardown is best-effort; never rethrow from finally */ }
            try {
                writer.write(maxFreqPath, ceilHz)
            } catch (_: Throwable) { /* restore is best-effort; the boot-revert journal backstops */ }
        }
    }

    /**
     * Resolve the beyond-stock OC target = the highest OPP the kernel advertises. Prefers a
     * live read of `available_frequencies` (the authoritative current table) and falls back
     * to the probed [TdpCaps.gpuDevfreqStepsHz] when the node is unreadable. Returns null
     * when neither yields a usable frequency.
     */
    private suspend fun readOcTargetHz(
        reader: SysfsReader,
        rootPath: String,
        caps: TdpCaps,
    ): Long? {
        val raw = reader.read(availableFrequenciesPath(rootPath))
        val fromNode = raw
            ?.split(WHITESPACE)
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.filter { it > 0L }
            ?.maxOrNull()
        if (fromNode != null) return fromNode
        // Fallback to the probed OPP steps captured in caps (honest: same table the
        // capability probe already read). Null when that is empty too.
        return caps.gpuDevfreqStepsHz.filter { it > 0L }.maxOrNull()
    }

    /**
     * Run the bounded load and sample `cur_freq`, returning the MAX clock observed (Hz). The
     * load is bounded to [PROBE_LOAD_MS]; a concurrent sampler polls every
     * [SAMPLE_INTERVAL_MS] to catch the peak whenever the governor ramps. The window is
     * bracketed by a guaranteed post-load sample so the observation is deterministic even
     * when the load returns without yielding to the sampler — the post-load read is taken
     * WHILE the load's tail is still warm (before teardown) and is the authoritative
     * "did the clock reach into the new headroom" reading.
     *
     * Implemented with structured concurrency so a failure/cancellation in either child
     * cancels the sibling and the bounded window is never exceeded.
     */
    private suspend fun runLoadAndSampleMaxClock(
        reader: SysfsReader,
        loadGen: LoadGen,
        rootPath: String,
    ): Long = coroutineScope {
        val curFreqPath = curFreqPath(rootPath)
        // Shared across the load and sampler coroutines → atomic, not a closed-over local
        // (a local `var` shared between coroutines is neither safe nor allowed `@Volatile`).
        val maxObserved = AtomicLong(0L)

        suspend fun sampleOnce() {
            val cur = reader.read(curFreqPath)?.trim()?.toLongOrNull()
            if (cur != null) maxObserved.updateAndGet { prev -> if (cur > prev) cur else prev }
        }

        // Concurrent poller — catches the peak mid-window on a real device where the
        // governor ramps over the 2 s. Cancelled the instant the bounded load returns.
        val sampler = launch {
            while (isActive) {
                sampleOnce()
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        try {
            loadGen.run(PROBE_LOAD_MS)
            // Authoritative end-of-window sample taken while the load tail is still warm,
            // BEFORE teardown — guarantees at least one honest reading regardless of how the
            // concurrent sampler was scheduled.
            sampleOnce()
        } finally {
            // The bounded load window ended (or failed) — cancel the sampler so it never
            // outlives the load and the window is never exceeded.
            sampler.cancel()
        }
        maxObserved.get()
    }

    /** Path of the devfreq `max_freq` node under the GPU root. */
    private fun devfreqMaxFreqPath(rootPath: String): String =
        "${rootPath.trimEnd('/')}/devfreq/max_freq"

    /** Path of the devfreq `cur_freq` node under the GPU root. */
    private fun curFreqPath(rootPath: String): String =
        "${rootPath.trimEnd('/')}/devfreq/cur_freq"

    /** Path of the devfreq `available_frequencies` node under the GPU root. */
    private fun availableFrequenciesPath(rootPath: String): String =
        "${rootPath.trimEnd('/')}/devfreq/available_frequencies"

    companion object {
        /** The bounded controlled-load window, ms (~2 s per the design). */
        const val PROBE_LOAD_MS = 2_000L

        /** cur_freq poll interval during the probe load, ms. */
        const val SAMPLE_INTERVAL_MS = 100L

        private val WHITESPACE = Regex("\\s+")

        /**
         * PURE decision function — the testable heart of the probe. Given the value we wrote,
         * the value the kernel read back, the stock ceiling, and the highest clock observed
         * under load, return the honest [GpuOcVerdict]. NO I/O, NO state.
         *
         * Contract (caller guarantees [written] > [ceilHz] before any write — that is the
         * Step-1 Unsupported gate, handled in [probe]; this function still answers correctly
         * if asked with no headroom, returning [GpuOcVerdict.Unsupported]):
         *
         *  - written <= ceil                          → Unsupported  (no real headroom)
         *  - readback != written                      → Rejected(clampedTo = readback)
         *  - readback == written, maxObserved <= ceil → Ineffective (cap honoured, clock never rose)
         *  - readback == written, maxObserved >  ceil → Accepted(reachedHz = maxObserved)
         *
         * HONESTY: Accepted is returned ONLY when the clock was observed strictly above the
         * stock ceiling — a raised cap the clock never touches is Ineffective, never Accepted.
         */
        fun classifyProbe(
            written: Long,
            readback: Long,
            ceilHz: Long,
            maxObservedHz: Long,
        ): GpuOcVerdict = when {
            // No real headroom was ever requested → there is no beyond-stock lever.
            written <= ceilHz -> GpuOcVerdict.Unsupported
            // Kernel clamped the cap: the readback didn't match what we wrote.
            readback != written -> GpuOcVerdict.Rejected(clampedTo = readback)
            // Cap accepted but the clock never rose above stock under load → no real OC.
            maxObservedHz <= ceilHz -> GpuOcVerdict.Ineffective
            // Cap accepted AND the clock was observed above stock → genuine beyond-stock OC.
            else -> GpuOcVerdict.Accepted(reachedHz = maxObservedHz)
        }
    }
}
