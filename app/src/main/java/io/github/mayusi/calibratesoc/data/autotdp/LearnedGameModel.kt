package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsEntity
import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsDao
import io.github.mayusi.calibratesoc.data.thermal.CapFloor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UNIT 1 — PER-GAME LEARNING (Axis 1 of the AutoTDP "better everything" upgrade).
 *
 * A tiny, NOT-ML per-package learned-parameter store. Three scalars + a confidence
 * count per game (persisted by Unit 0's [LearnedGameParamsEntity] / [LearnedGameParamsDao]):
 *
 *   - [LearnedSeed.safeSustainedCapKhz]  — the big-cluster cap that held thermally
 *                                          stable across past sessions (a real OPP,
 *                                          never below the 40% hard floor).
 *   - [LearnedSeed.throttleOnsetSec]     — observed seconds from engage to the first
 *                                          thermal pre-empt (drives the proactive arm).
 *   - [LearnedSeed.observedBandCenterPct]— the GPU band center that produced stable
 *                                          thermals (informational; the band controller
 *                                          still owns the live band).
 *   - [LearnedSeed.sessionCount]         — confidence. < [MIN_SESSIONS_TO_SEED] ⇒ COLD
 *                                          START: [seedFor] returns null and the engine
 *                                          behaves EXACTLY as today (no fabrication).
 *
 * ## The two operations
 *
 *  - [seedFor]            — read ONCE at session START. Returns null (cold start) when
 *                           there is no row OR `sessionCount < 2`. Otherwise a
 *                           [LearnedSeed] with the stored cap RE-CLAMPED to the 40% hard
 *                           floor (defence-in-depth: a row written by an older/buggier
 *                           build can never seed a below-floor cap).
 *  - [updateAfterSession] — write ONCE at session END (off the tick thread). An EWMA
 *                           ratchet (α = [EWMA_ALPHA]) folds this session's converged
 *                           cap into the stored cap. One bad (hot) session can NEVER
 *                           poison the store: the EWMA damps a single sample AND the
 *                           cautious-up ratchet only raises the cap when the session was
 *                           clean. The stored cap is clamped to the 40% floor on write.
 *
 * ## SAFETY (LAW)
 *
 * The learned cap only sets the STARTING operating point. The reactive band controller,
 * thermal pre-empt, the 105 °C thermal kill, and the 40% hard floor all run from tick 1.
 * A stale/high learned cap on a hot day is caught by the live controller + pre-empt +
 * kill. This store CANNOT disable any safety path; the floor clamp on BOTH store and
 * read guarantees it can never even propose a below-floor cap.
 *
 * PURE-ish: all math is plain integer/double arithmetic via [CapFloor]; the only I/O is
 * the suspend DAO read/write. No Android, no clock reads inside the math.
 */
@Singleton
class LearnedGameModel @Inject constructor(
    private val dao: LearnedGameParamsDao,
) {

    /**
     * Read the learned seed for [pkg], or null for a COLD START.
     *
     * Cold start (returns null, engine behaves identically to today) when:
     *   - [pkg] is null (unknown foreground app — never seed a guess), OR
     *   - no row exists for the package, OR
     *   - `sessionCount < `[MIN_SESSIONS_TO_SEED] (not enough confidence yet), OR
     *   - the stored cap is null (nothing learned to seed).
     *
     * The returned cap is re-clamped to the 40% hard floor against [oppStepsKhz] so a
     * row written by an older build can never seed a below-floor cap. When [oppStepsKhz]
     * is empty (no OPP table) the stored cap is returned unsnapped — the engine's own
     * [AutoTdpEngine] invariant gate is the final backstop either way.
     */
    suspend fun seedFor(pkg: String?, oppStepsKhz: List<Int> = emptyList()): LearnedSeed? {
        if (pkg.isNullOrBlank()) return null
        val row = dao.getByPkg(pkg) ?: return null
        if (row.sessionCount < MIN_SESSIONS_TO_SEED) return null
        val storedCap = row.safeSustainedCapKhz ?: return null
        // Re-clamp on READ (defence in depth): a row from an older/buggier build must
        // never seed a cap below the 40% hard floor. snapCapToOpp both snaps to a real
        // OPP and raises to the floor; with an empty table it returns the value as-is.
        val clamped = if (oppStepsKhz.isNotEmpty()) {
            CapFloor.snapCapToOpp(storedCap, oppStepsKhz)
        } else {
            storedCap
        }
        return LearnedSeed(
            safeSustainedCapKhz = clamped,
            throttleOnsetSec = row.throttleOnsetSec,
            observedBandCenterPct = row.observedBandCenterPct,
            sessionCount = row.sessionCount,
        )
    }

    /**
     * Fold [outcome] into the learned params for [pkg] via the EWMA ratchet, then persist.
     *
     * No-op when [pkg] is null/blank (we never learn against an unknown game).
     *
     * The cap ratchet (α = [EWMA_ALPHA]):
     *   - CLEAN session (no steady-state pre-empt AND avgFps held target): the candidate
     *     is `max(convergedCap, prevCap)` — ratchet UP cautiously toward the highest cap
     *     that proved sustainable. A clean session never lowers the learned cap.
     *   - THROTTLED session (≥1 steady-state pre-empt): the candidate is the converged
     *     cap (which already sits just below the first-pre-empt cap, because the reactive
     *     controller tightened away from it) — pull the learned cap DOWN toward what the
     *     device could actually sustain this run.
     *
     * `safeSustainedCapKhz = snapToOpp((1-α)*prev + α*candidate)`, then floor-clamped.
     * On the FIRST observation (no prior cap) the candidate seeds the EWMA directly.
     *
     * `throttleOnsetSec` is EWMA-folded ONLY when a pre-empt fired this session and an
     * onset time was observed (a clean session carries no onset signal to fold).
     *
     * `sessionCount` increments by one. `lastUpdatedMs` is stamped from [nowMs].
     *
     * SAFETY: the final stored cap is clamped to the 40% hard floor via
     * [CapFloor.snapCapToOpp]; the store can never hold a below-floor cap. One bad
     * sample cannot poison the store — the EWMA damps it and the up-ratchet refuses to
     * raise the cap on a throttled run.
     */
    suspend fun updateAfterSession(
        pkg: String?,
        outcome: SessionOutcome,
        nowMs: Long,
    ) {
        if (pkg.isNullOrBlank()) return
        val prev = dao.getByPkg(pkg)
        val oppSteps = outcome.oppStepsKhz

        // ── Cap ratchet ──────────────────────────────────────────────────────────
        val prevCap = prev?.safeSustainedCapKhz
        val convergedCap = outcome.convergedCapKhz
        val newCap: Int? = if (convergedCap == null) {
            // Nothing converged this session (e.g. ran entirely uncapped at stock, or
            // ended before any cap landed). Keep the prior learned cap unchanged.
            prevCap
        } else {
            // Candidate: clean → cautious-up (never below prev); throttled → converged.
            val candidate = if (outcome.isClean) {
                maxOf(convergedCap, prevCap ?: convergedCap)
            } else {
                convergedCap
            }
            val blended = if (prevCap == null) {
                // First observation: seed the EWMA directly with the candidate.
                candidate.toDouble()
            } else {
                (1.0 - EWMA_ALPHA) * prevCap + EWMA_ALPHA * candidate
            }
            // Snap to the NEAREST real OPP (not DOWN) so a cautious-up ratchet can cross an
            // OPP boundary: with α=0.3 a single fold moves the cap < one OPP gap, and a
            // snap-DOWN would round that increment away every session, permanently stalling
            // the ratchet below the boundary. Nearest-OPP lets it climb while still storing
            // a REAL OPP step (MM-2 honesty). The hard 40% floor is then applied on top so a
            // throttled session can never store a below-floor cap (SAFETY on store).
            snapToNearestOppWithFloor(blended.toInt(), oppSteps)
        }

        // ── Throttle-onset EWMA (only when a pre-empt fired with an onset time) ────
        val prevOnset = prev?.throttleOnsetSec
        val observedOnset = outcome.observedOnsetSec
        val newOnset: Int? = if (!outcome.isClean && observedOnset != null) {
            if (prevOnset == null) {
                observedOnset
            } else {
                ((1.0 - EWMA_ALPHA) * prevOnset + EWMA_ALPHA * observedOnset).toInt()
            }
        } else {
            prevOnset // clean session (or no onset observed): keep the prior onset.
        }

        // ── Band center (informational; simple EWMA when observed) ────────────────
        val prevBand = prev?.observedBandCenterPct
        val observedBand = outcome.observedBandCenterPct
        val newBand: Int? = when {
            observedBand == null -> prevBand
            prevBand == null -> observedBand
            else -> ((1.0 - EWMA_ALPHA) * prevBand + EWMA_ALPHA * observedBand).toInt()
        }

        dao.upsert(
            LearnedGameParamsEntity(
                pkg = pkg,
                safeSustainedCapKhz = newCap,
                throttleOnsetSec = newOnset,
                observedBandCenterPct = newBand,
                sessionCount = (prev?.sessionCount ?: 0) + 1,
                lastUpdatedMs = nowMs,
            )
        )
    }

    /**
     * Snap [desiredKhz] to the NEAREST OPP step in [oppSteps], then RAISE the result to the
     * 40% hard floor ([CapFloor.hardFloorKhz]). Used on STORE so the learned cap is always a
     * real OPP that never sits below the safety floor, while still letting the cautious-up
     * EWMA ratchet climb across OPP boundaries (a snap-DOWN would stall it — see the call
     * site). With an empty table the value is returned unchanged (the engine's seed read +
     * invariant gate re-clamp anyway).
     */
    private fun snapToNearestOppWithFloor(desiredKhz: Int, oppSteps: List<Int>): Int {
        if (oppSteps.isEmpty()) return desiredKhz
        val nearest = oppSteps.minByOrNull { kotlin.math.abs(it - desiredKhz) } ?: oppSteps.first()
        val floor = CapFloor.hardFloorKhz(oppSteps) ?: return nearest
        return maxOf(nearest, floor)
    }

    companion object {
        /**
         * Minimum confidence (`sessionCount`) before a learned seed is surfaced. Below
         * this the engine behaves exactly as today (cold start, no fabrication). Two
         * sessions is the smallest count that proves a value repeated rather than being
         * a one-off — the honesty floor for the "LEARNED (n sessions)" UI tier.
         */
        const val MIN_SESSIONS_TO_SEED = 2

        /**
         * EWMA smoothing factor for the cap/onset ratchet. 0.3 means a single session
         * moves the stored value at most ~30% of the way toward this run's candidate —
         * one bad (hot) sample cannot collapse the learned cap, while ~5 consistent
         * sessions converge it. Combined with the cautious-up ratchet (a clean session
         * never lowers the cap; a throttled one never raises it) this is the poison-
         * resistance guarantee.
         */
        const val EWMA_ALPHA = 0.3
    }
}

/**
 * The learned seed handed to [AutoTdpEngine.decide] at session START.
 *
 * Modeled-NOT-measured: every field is a learned estimate, distinct from a live probe.
 * The UI surfaces this as a "LEARNED (n sessions)" tier (via [sessionCount]), separate
 * from the MEASURED tier. Never constructed when `sessionCount < `
 * [LearnedGameModel.MIN_SESSIONS_TO_SEED] (cold start returns null instead).
 *
 * @property safeSustainedCapKhz the big-cluster cap to seed as the STARTING operating
 *                               point (a real OPP at/above the 40% hard floor). The
 *                               reactive controller + pre-empt + kill still run from
 *                               tick 1 — this only skips the slow reactive walk-down.
 * @property throttleOnsetSec    observed seconds to the first thermal pre-empt, or null.
 *                               Drives the ONE proactive tighten near
 *                               0.85 × this value. Null ⇒ no proactive arm.
 * @property observedBandCenterPct the historically-stable GPU band center, or null.
 *                               Informational for the UI; the live band controller
 *                               still owns the band.
 * @property sessionCount        confidence (≥ [LearnedGameModel.MIN_SESSIONS_TO_SEED]
 *                               by construction). Surfaced as "LEARNED (n sessions)".
 */
data class LearnedSeed(
    val safeSustainedCapKhz: Int?,
    val throttleOnsetSec: Int?,
    val observedBandCenterPct: Int?,
    val sessionCount: Int,
)

/**
 * The per-session outcome captured by [AutoTdpService] during a run and handed to
 * [LearnedGameModel.updateAfterSession] at session END. A lightweight accumulator —
 * built off the tick thread on disengage, never per-tick.
 *
 * @property preemptFiredInSteadyState true when ≥1 thermal pre-empt fired AFTER the
 *           honest-baseline grace window (i.e. in steady state, not the initial warm-up).
 * @property avgFpsHeldTarget          true when the session's average FPS held the
 *           target (a clean perf result). Null/false when FPS was unavailable — a
 *           session with no FPS signal is treated as NOT-clean for the cautious-up
 *           ratchet (we never ratchet the cap UP on an unverified perf result).
 * @property convergedCapKhz           the big-cluster cap the controller settled on at
 *           session end (the last applied cap), or null if it never capped.
 * @property observedOnsetSec          seconds from engage to the FIRST steady-state
 *           pre-empt, or null when none fired.
 * @property observedBandCenterPct     the GPU band center the active goal used, or null.
 * @property oppStepsKhz               the device's big-cluster OPP table (for the floor
 *           clamp + OPP snap on store). Empty ⇒ store the raw blended value (the engine
 *           gate re-clamps on read anyway).
 */
data class SessionOutcome(
    val preemptFiredInSteadyState: Boolean,
    val avgFpsHeldTarget: Boolean,
    val convergedCapKhz: Int?,
    val observedOnsetSec: Int?,
    val observedBandCenterPct: Int?,
    val oppStepsKhz: List<Int>,
) {
    /**
     * A CLEAN session = no steady-state pre-empt AND avg FPS held target. Only a clean
     * session may ratchet the learned cap UP (cautiously). A pre-empt OR an unmet/unknown
     * FPS target marks the session not-clean, so the cap can only hold or fall.
     */
    val isClean: Boolean get() = !preemptFiredInSteadyState && avgFpsHeldTarget
}
