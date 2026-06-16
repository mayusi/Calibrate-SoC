package io.github.mayusi.calibratesoc.data.boost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for "which clock-writing mode owns the kernel right
 * now". Three features can write CPU/GPU clocks:
 *
 *  - [ClockOwner.AUTO_TDP]   — the AutoTDP optimiser (caps clocks to save heat).
 *  - [ClockOwner.GAME_BOOST] — the Game Boost brute-max pin.
 *  - (the Predictive Throttle Guard is a *lighter* pre-emptive cap; it is NOT an
 *     exclusive owner — instead it is auto-SUPPRESSED while either of the above is
 *     active, because they already do their own thermal management. See
 *     [throttleGuardSuppressed].)
 *
 * ## Mutual exclusion (AutoTDP ⟷ Game Boost)
 *
 * AutoTDP and Game Boost BOTH write the big-cluster clocks and would fight each
 * other (one caps, the other pins) and corrupt each other's revert journals. They
 * are mutually exclusive: the arbiter holds at most ONE clock owner at a time.
 * Each service, on start, calls [acquire]; the returned [AcquireResult] tells it
 * whether it must first stop the other owner. The services mirror AutoTDP's
 * start/stop and use this to enforce the guard.
 *
 * ## Suppression (Throttle Guard under AutoTDP / Game Boost)
 *
 * The Predictive Throttle Guard is a standalone safety assist. It only acts when
 * NEITHER AutoTDP nor Game Boost owns the clocks ([throttleGuardSuppressed] == false).
 * The moment an exclusive owner acquires, the guard sees [throttleGuardSuppressed]
 * flip to true, reverts its own pre-emptive cap, and idles — so it never fights the
 * owner's thermal management.
 *
 * Thread-safety: methods are synchronised on the instance. The state flows are read
 * by the UI off the main thread; reads are cheap and lock-free via StateFlow.
 */
@Singleton
class BoostArbiter @Inject constructor() {

    /** Who, if anyone, currently owns the CPU/GPU clocks. */
    enum class ClockOwner { NONE, AUTO_TDP, GAME_BOOST }

    private val _owner = MutableStateFlow(ClockOwner.NONE)
    /** Current exclusive clock owner. The throttle guard observes this to suppress. */
    val owner: StateFlow<ClockOwner> = _owner.asStateFlow()

    /**
     * True when an exclusive clock owner (AutoTDP or Game Boost) is active and the
     * Predictive Throttle Guard must therefore stand down. The guard service collects
     * this and reverts + idles whenever it is true.
     */
    val throttleGuardSuppressed: StateFlow<Boolean>
        get() = _suppressed
    private val _suppressed = MutableStateFlow(false)

    /**
     * Result of an [acquire] attempt.
     *
     * @param granted             Always true today (acquisition never hard-fails; the
     *                            new owner replaces the old). Kept explicit so a future
     *                            policy could refuse.
     * @param mustStopPreviousOwner The owner the caller must cleanly stop before it
     *                            begins writing, or [ClockOwner.NONE] when the clocks
     *                            were free. The caller stops that owner's service first
     *                            (mirroring AutoTDP's stop → revertAll) so the two never
     *                            write concurrently.
     */
    data class AcquireResult(
        val granted: Boolean,
        val mustStopPreviousOwner: ClockOwner,
    )

    /**
     * Claim exclusive clock ownership for [newOwner]. If a *different* owner held the
     * clocks, [AcquireResult.mustStopPreviousOwner] names it — the caller stops that
     * service (which reverts its writes) BEFORE applying its own bundle.
     *
     * Calling [acquire] with the owner you already hold is a no-op (returns NONE to
     * stop) — safe to call idempotently on service (re)start.
     */
    @Synchronized
    fun acquire(newOwner: ClockOwner): AcquireResult {
        require(newOwner != ClockOwner.NONE) { "acquire() needs a real owner, not NONE" }
        val previous = _owner.value
        _owner.value = newOwner
        // Any exclusive owner suppresses the throttle guard.
        _suppressed.value = true
        return AcquireResult(
            granted = true,
            mustStopPreviousOwner = if (previous != newOwner) previous else ClockOwner.NONE,
        )
    }

    /**
     * Release clock ownership held by [releasingOwner]. No-op if a *different* owner
     * has since taken over (a late stop from a superseded service must not clear the
     * new owner's claim — this prevents a stale stopDaemon() from un-suppressing the
     * guard while Game Boost is still running).
     */
    @Synchronized
    fun release(releasingOwner: ClockOwner) {
        if (_owner.value == releasingOwner) {
            _owner.value = ClockOwner.NONE
            _suppressed.value = false
        }
    }

    /** True when [candidate] may write clocks right now (it is the owner, or free). */
    @Synchronized
    fun isOwnerOrFree(candidate: ClockOwner): Boolean =
        _owner.value == ClockOwner.NONE || _owner.value == candidate
}
