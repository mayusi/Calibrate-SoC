package io.github.mayusi.calibratesoc.data.autotdp.gpu

import io.github.mayusi.calibratesoc.data.autotdp.Direction

/**
 * UNIT 2 (ADAPTIVE MODE) — the carried state of the GPU band controller, threaded
 * through [GpuDecision].
 *
 * This is the GPU mirror of the CPU controller's `ControllerState`. The
 * [GpuBandController] is PURE: every per-tick memory it needs (the GPU busy% EWMA
 * accumulator, the active direction-episode, the confirm counter, and the GPU clock it
 * is currently riding) lives HERE. Unit 5 persists [GpuDecision.state] and passes it
 * back into the next [GpuBandController.decide] call alongside the CPU controller's
 * state. Nothing in this file is Android, I/O, or a clock read.
 *
 * The GPU clock is governed on the devfreq lever (Hz): the controller hunts the LOWEST
 * GPU devfreq MAX that keeps GPU busy% inside the band. The carried [gpuDevfreqMaxHz]
 * is the operating point being ridden; the pwrlevel mirror ([gpuLevel]) is carried for
 * coarse devices that have no devfreq table. Both are snapped to real OPPs by the
 * controller before they ever land here.
 *
 * @property gpuEwma           carried GPU busy% EWMA accumulator (α=0.4 continuity
 *                             across ticks). Null until the first sample is folded.
 * @property currentDirection  the direction of the in-progress episode, or null when
 *                             holding inside the dead-band.
 * @property confirmTicks      consecutive ticks the controller has been confirming
 *                             [currentDirection] (loosen needs 1; a BAND tighten needs
 *                             2; a FAST thermal tighten needs 0 — acts this tick).
 * @property quietTicks        consecutive non-acting ticks since the last action — the
 *                             cool-down counter (mirrors the CPU controller's gap).
 * @property lastActedDirection the direction of the last APPLIED clock move. Null
 *                             before any action.
 * @property gpuDevfreqMaxHz   the GPU devfreq MAX (Hz) currently being ridden — the
 *                             operating point. Null = stock (no max override applied
 *                             yet); the first loosen/tighten moves off the ceiling.
 * @property gpuDevfreqMinHz   the GPU devfreq MIN (Hz) currently applied — held a fixed
 *                             margin below the max for frame consistency. Null = stock.
 * @property gpuLevel          the Adreno max_pwrlevel floor currently applied (coarse
 *                             mirror for devices with no devfreq table; lower index =
 *                             faster). Null = stock.
 */
data class GpuControllerState(
    val gpuEwma: Double? = null,
    val currentDirection: Direction? = null,
    val confirmTicks: Int = 0,
    val quietTicks: Int = 0,
    val lastActedDirection: Direction? = null,
    val gpuDevfreqMaxHz: Long? = null,
    val gpuDevfreqMinHz: Long? = null,
    val gpuLevel: Int? = null,
) {

    /**
     * Begin/continue confirming a [direction]. When the direction matches the
     * in-progress episode the confirm counter increments; when it flips, the episode
     * resets to a fresh count of 1 AND [quietTicks] resets so the cool-down gap is
     * measured from the moment we start wanting the NEW direction (mirrors the CPU
     * controller's `advanceConfirm`). Pure — returns a new state.
     */
    fun advanceConfirm(direction: Direction): GpuControllerState {
        return if (currentDirection == direction) {
            copy(confirmTicks = confirmTicks + 1)
        } else {
            copy(currentDirection = direction, confirmTicks = 1, quietTicks = 0)
        }
    }

    companion object {
        /** The clean starting state for a fresh adaptive session. */
        val INITIAL = GpuControllerState()
    }
}
