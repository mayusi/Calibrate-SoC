package io.github.mayusi.calibratesoc.data.thermal

/**
 * Shared big-cluster cap-floor + OPP-snap math — the single source of truth for the
 * "never cap a cluster below 40% of its top OPP, and only ever write a REAL OPP step"
 * invariant. Used by both [io.github.mayusi.calibratesoc.data.autotdp.AutoTdpEngine]
 * (via [HARD_FLOOR_FRACTION]) and [ThrottleGuardActuator] so the two clock-cappers
 * apply the SAME hard floor on the SAME `scaling_max_freq` node and can never drift.
 *
 * ## Why this exists (HIGH-2)
 *
 * The predictive throttle guard previously rounded its recommended cap to a 100 MHz
 * boundary and floored at a fixed 300 MHz. That bypassed AutoTDP's 40%-of-top-OPP hard
 * floor on the same node, and could write a kHz value the kernel silently clamps to the
 * nearest OPP — so the guard's `activeCapKhz` belief diverged from what the cluster
 * actually ran. Snapping to a real OPP and clamping at the shared hard floor fixes both:
 * the guard never writes an unachievable value, and never caps below the safe floor.
 *
 * PURE: no Android imports, plain integer kHz arithmetic — a JVM unit test target.
 */
object CapFloor {

    /**
     * The HARD cap floor as a fraction of the cluster's top OPP. The big-cluster cap may
     * NEVER be tightened below this fraction of cpuinfo_max, regardless of caller, goal,
     * classification, or forecast. 40% is the conservative fail-safe AutoTDP uses (enough
     * power reduction to matter, well clear of the unusable bottom that stutters games).
     * Mirrored by AutoTdpEngine.CAP_HARD_FLOOR_FRACTION (same value, single source here).
     */
    const val HARD_FLOOR_FRACTION: Double = 0.40

    /**
     * The lowest OPP step (kHz) the cap may reach for the given ascending OPP [steps]:
     * the first OPP at/above [HARD_FLOOR_FRACTION] of the top OPP, clamped so it is a
     * usable working OPP and never the very top step (which would forbid all capping).
     *
     * @return the hard-floor OPP in kHz, or null for a degenerate table (empty / single
     *         step) where no real floor can be expressed.
     */
    fun hardFloorKhz(steps: List<Int>): Int? {
        if (steps.isEmpty()) return null
        if (steps.size < 2) return steps.first()
        val top = steps.last()
        val threshold = top * HARD_FLOOR_FRACTION
        val idx = steps.indexOfFirst { it >= threshold }
            .coerceIn(0, (steps.lastIndex - 1).coerceAtLeast(0))
        return steps[idx]
    }

    /**
     * Snap an arbitrary desired cap [desiredKhz] DOWN to the nearest REAL OPP step
     * at/below it (so the kernel won't silently clamp up), then RAISE the result to the
     * shared hard floor if it fell below it. The returned value is always one of [steps]
     * (when non-empty) — a value the cluster can actually run.
     *
     * Behaviour:
     *  - empty [steps]: returns [desiredKhz] unchanged (caller has no OPP table to snap to).
     *  - desired at/above the top OPP: returns the top OPP (no cap — caller should treat as
     *    "no cap needed").
     *  - desired below the hard floor: returns the hard-floor OPP (never floors the cluster).
     *  - otherwise: the largest OPP step <= desired, but never below the hard floor.
     */
    fun snapCapToOpp(desiredKhz: Int, steps: List<Int>): Int {
        if (steps.isEmpty()) return desiredKhz
        val sorted = steps.sorted()
        val top = sorted.last()
        if (desiredKhz >= top) return top
        // Largest OPP step at or below the desired value (snap DOWN so no silent clamp-up).
        val snappedDown = sorted.lastOrNull { it <= desiredKhz } ?: sorted.first()
        val floor = hardFloorKhz(sorted) ?: return snappedDown
        return maxOf(snappedDown, floor)
    }
}
