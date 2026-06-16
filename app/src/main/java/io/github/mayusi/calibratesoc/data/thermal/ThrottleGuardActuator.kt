package io.github.mayusi.calibratesoc.data.thermal

import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.Tunables

/**
 * PURE actuation logic for the Predictive Throttle Guard.
 *
 * Given a [ThrottleForecast] (from [PredictiveThrottleGuard.predict]), the current
 * suppression state, and the big-cluster policy id, this decides the ONE big-cluster
 * `scaling_max_freq` write the guard should make this tick — apply a pre-emptive cap,
 * revert the cap, or do nothing — and tracks whether a cap is currently active.
 *
 * Keeping it pure makes "applies + reverts the forecast cap" and "stands down when
 * suppressed" unit-testable without an Android service. The service calls
 * [decide] each tick and, when it returns a non-null [GuardAction.write], performs
 * that write through TunableWriter (snapshot + readback-verify + revert-on-stop).
 *
 * ## Revert semantics
 *
 * When the forecast clears (or the guard is suppressed), we revert by writing the
 * big policy's stock ceiling back to `scaling_max_freq`. That stock ceiling is the
 * top OPP step the service captured from the [io.github.mayusi.calibratesoc.data.capability.CapabilityReport]
 * at start. TunableWriter ALSO journals the original value, so on full stop revertAll
 * restores it regardless — this in-loop revert is the *responsive* path (so FPS comes
 * back the moment the device cools) while revertAll is the *guaranteed* backstop.
 */
class ThrottleGuardActuator(
    /** Policy id of the big cluster whose scaling_max_freq we cap. */
    private val bigPolicyId: Int,
    /** Stock big-cluster ceiling (kHz) — written back to revert the cap. */
    private val stockCeilingKhz: Int,
    /**
     * HIGH-2: the big cluster's REAL available OPP steps (kHz), used to (a) snap the
     * recommended cap DOWN to an achievable OPP so the kernel can't silently clamp it
     * (keeping [activeCapKhz] honest) and (b) clamp it at the shared 40%-of-top-OPP hard
     * floor (the SAME floor AutoTDP enforces on this node). Empty ⇒ no snap/floor is
     * applied (degenerate device with no enumerable OPP table) and the raw recommendation
     * is used, still clamped at/below the stock ceiling.
     */
    private val availableFreqsKhz: List<Int> = emptyList(),
) {
    /** The cap currently applied (kHz), or null when at stock (no guard cap). */
    var activeCapKhz: Int? = null
        private set

    /**
     * The write the service should perform this tick, plus the resulting active cap.
     *
     * @param write Non-null when a write is needed (apply a new cap, or revert to
     *              stock). Null when nothing should change (already at the desired
     *              cap / already at stock).
     * @param activeCapKhz The cap that WILL be active after [write] lands (mirrors
     *              [ThrottleGuardActuator.activeCapKhz] post-apply). Null = at stock.
     */
    data class GuardAction(
        val write: Write?,
        val activeCapKhz: Int?,
    ) {
        data class Write(val id: TunableId, val value: String, val description: String)
    }

    /**
     * Decide the action for this tick.
     *
     * @param forecast   The latest forecast.
     * @param suppressed True while AutoTDP / Game Boost owns the clocks — the guard
     *                   must stand down: revert any active cap and apply nothing new.
     */
    fun decide(forecast: ThrottleForecast, suppressed: Boolean): GuardAction {
        // ── Suppressed: revert any active cap, never apply a new one. ──────────────
        if (suppressed) {
            return revertIfCapped()
        }

        // ── Forecast says act: apply (or update) the pre-emptive cap. ──────────────
        if (forecast.actionRequired) {
            val target = forecast.recommendedCapKhz ?: return noChange()
            // HIGH-2: snap the recommendation to a REAL OPP at/below it and raise it to the
            // shared 40%-of-top-OPP hard floor, so we never write a value the kernel will
            // silently clamp (activeCapKhz stays == reality) and never cap below the floor.
            // Then never cap ABOVE the stock ceiling (that's not a cap).
            val snapped = if (availableFreqsKhz.isNotEmpty()) {
                CapFloor.snapCapToOpp(target, availableFreqsKhz)
            } else {
                target
            }
            val clamped = snapped.coerceAtMost(stockCeilingKhz)
            if (clamped >= stockCeilingKhz) {
                // Recommended cap is at/above stock — effectively no cap. Revert if needed.
                return revertIfCapped()
            }
            if (activeCapKhz == clamped) return noChange() // already capped here
            activeCapKhz = clamped
            return GuardAction(
                write = GuardAction.Write(
                    id = Tunables.cpuMaxFreq(bigPolicyId),
                    value = clamped.toString(),
                    description = "ThrottleGuard pre-emptive cap policy$bigPolicyId → ${clamped / 1000} MHz",
                ),
                activeCapKhz = clamped,
            )
        }

        // ── Forecast clear: revert any active cap. ─────────────────────────────────
        return revertIfCapped()
    }

    /** Revert to stock if a cap is currently active; else no change. */
    private fun revertIfCapped(): GuardAction {
        if (activeCapKhz == null) return noChange()
        activeCapKhz = null
        return GuardAction(
            write = GuardAction.Write(
                id = Tunables.cpuMaxFreq(bigPolicyId),
                value = stockCeilingKhz.toString(),
                description = "ThrottleGuard revert cap policy$bigPolicyId → ${stockCeilingKhz / 1000} MHz (stock)",
            ),
            activeCapKhz = null,
        )
    }

    private fun noChange(): GuardAction = GuardAction(write = null, activeCapKhz = activeCapKhz)
}
