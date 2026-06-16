package io.github.mayusi.calibratesoc.data.boost

import io.github.mayusi.calibratesoc.data.autotdp.ThermalKillEvaluator
import io.github.mayusi.calibratesoc.data.monitor.Telemetry

/**
 * PURE guard for a Game Boost session: decides, per telemetry tick, whether the
 * session must end and WHY. Two independent triggers:
 *
 *  1. **Time box** — the session auto-reverts after [timeBoxMillis]. This is the
 *     EXPECTED end ([BoostStop.TIME_BOX]).
 *  2. **Thermal trip** — reuses [ThermalKillEvaluator] (the AutoTDP threshold +
 *     debounce + grace-window pattern) so a sustained over-temp reverts the boost
 *     ([BoostStop.THERMAL]). We do NOT modify ThermalKillEvaluator — we instantiate
 *     and reuse it exactly as AutoTDP does.
 *
 * Keeping this pure makes the time-box + thermal-trip revert logic unit-testable
 * without an Android service or a device. The service feeds it `evaluate(now, sample)`
 * each tick and acts on the returned [BoostStop].
 *
 * Thread-safety: call from a single coroutine (the boost loop). Not thread-safe.
 */
class BoostGuard(
    /** Session length before auto-revert, in ms. */
    private val timeBoxMillis: Long,
    /** Epoch ms the session started (BOOSTING transition). */
    private val sessionStartEpochMs: Long,
    /**
     * The thermal evaluator. Injected so a test can supply a lowered-threshold or
     * zero-grace instance. Defaults to the SAME configuration AutoTDP uses (105 °C,
     * 2 consecutive, 3-sample grace). We REUSE it, never reimplement it.
     */
    private val thermalKill: ThermalKillEvaluator = ThermalKillEvaluator(),
) {

    /** Why the boost should stop, or [BoostStop.NONE] to keep boosting. */
    enum class BoostStop { NONE, TIME_BOX, THERMAL }

    /** Decision + the human-readable detail for whichever trigger fired. */
    data class BoostDecision(
        val stop: BoostStop,
        /** Non-null only when [stop] != NONE. */
        val reason: String?,
    ) {
        companion object {
            val CONTINUE = BoostDecision(BoostStop.NONE, null)
        }
    }

    /**
     * Evaluate one tick. THERMAL takes priority over TIME_BOX (a hot device should
     * revert immediately, not wait out the clock). Returns [BoostDecision.CONTINUE]
     * when the session may keep running.
     *
     * @param nowEpochMs Current wall-clock (the service passes System.currentTimeMillis()).
     * @param sample     The telemetry sample for this tick.
     */
    fun evaluate(nowEpochMs: Long, sample: Telemetry): BoostDecision {
        // Thermal first — safety beats the clock.
        val tripReason = thermalKill.evaluate(sample)
        if (tripReason != null) {
            return BoostDecision(BoostStop.THERMAL, tripReason)
        }
        // Time box.
        val elapsed = nowEpochMs - sessionStartEpochMs
        if (elapsed >= timeBoxMillis) {
            val minutes = timeBoxMillis / 60_000L
            return BoostDecision(
                BoostStop.TIME_BOX,
                "Time box reached (${minutes} min) — auto-reverting boost.",
            )
        }
        return BoostDecision.CONTINUE
    }
}
