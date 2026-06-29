package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.tunables.Tunables

/**
 * PURE convergence logic for the AutoTDP apply loop — extracted from [AutoTdpService] so
 * the FINDING 1 (stuck-spin) fix is unit-testable without an Android runtime, a binder, or
 * a live daemon (matches the [TdpStateTransition] / [PServerWriter.classifyReadback]
 * pattern of testing the pure decision in isolation).
 *
 * ── THE BUG THIS FIXES ────────────────────────────────────────────────────────────────
 * The apply loop only advanced `currentState` to the engine's `effectiveTarget` when EVERY
 * write op succeeded. A write that PHYSICALLY LANDED but read back at a different value
 * (kernel snapped the OPP off-tolerance, or a vendor daemon re-clamped scaling_max_freq)
 * is classified [io.github.mayusi.calibratesoc.data.tunables.WriteResult.Rejected] — not
 * fatal, so the daemon keeps running, but `currentState` never advanced. Next tick the
 * engine recomputed the SAME delta against the SAME stale `currentState`, re-emitted the
 * SAME failing write, and span forever: "Running" with a cap that never moves.
 *
 * ── THE FIX ───────────────────────────────────────────────────────────────────────────
 * On a verification-mismatch Rejected we CONVERGE: advance the controller's per-node state
 * to the kernel's ACTUAL readback (its truth), so next tick's delta is computed from
 * reality and the loop settles. We distinguish two shapes and treat them very differently,
 * which is the HONESTY core:
 *
 *  - [RejectKind.CONVERGE] — the kernel ACCEPTED a DIFFERENT VALID value (readback moved
 *    away from the prior value). That readback IS the new truth; converge to it. This is
 *    NOT a claim of success — the op stays Rejected, the tick stays partial — it only stops
 *    us re-fighting a write the kernel already answered with a legitimate alternative.
 *
 *  - [RejectKind.HONEST_FAIL] — the write had ZERO effect (readback == the prior value) or
 *    there is no numeric readback at all. We NEVER converge here: adopting the unchanged
 *    stock value as if we wanted it would silently treat a failed write as success. Instead
 *    the node is held at its prior applied value and counted toward the stuck backstop.
 */
internal object TdpConvergence {

    /** How a verification-mismatch [io.github.mayusi.calibratesoc.data.tunables.WriteResult.Rejected] should be handled. */
    enum class RejectKind {
        /** Kernel chose a DIFFERENT VALID value — converge the controller to the readback. */
        CONVERGE,

        /** Write had ZERO effect (readback == prior) or no numeric readback — honest failure. */
        HONEST_FAIL,
    }

    /**
     * Classify a Rejected write from its parsed readback + prior values.
     *
     * [readbackValue] is the numeric value the node holds NOW (null when the readback was
     * non-numeric or unparseable); [previousValue] is what it held BEFORE the write (null
     * when the pre-read failed or was non-numeric).
     *
     * CONVERGE iff we have a numeric readback AND it is NOT equal to the prior value — i.e.
     * the node demonstrably MOVED to a new value the kernel accepted. Everything else
     * (no readback, or readback == prior = no-effect) is an HONEST_FAIL.
     */
    fun classifyReject(readbackValue: Long?, previousValue: Long?): RejectKind =
        if (readbackValue != null && (previousValue == null || readbackValue != previousValue)) {
            RejectKind.CONVERGE
        } else {
            RejectKind.HONEST_FAIL
        }

    /**
     * ADVANCE-TO-ACTUAL: return a copy of [state] with the [op]'s node field set to the
     * value the node actually holds — [readback] — or null when this op's node has no numeric
     * frequency field the daemon tracks (GPU pwrlevel, uclamp, online toggle, fan, governor)
     * and so nothing to advance. Used for BOTH the landed-Success path (readback = the value
     * that landed) and the converge-on-Rejected path (readback = the kernel's snapped-to
     * value). Either way the accumulator ends up holding the node's REAL value, so next tick's
     * delta is computed from reality and the engine stops re-emitting the same write.
     *
     * Mapping is by sysfs target path against the nodes the live delta writes, derived from
     * the same [bigPolicyId] / [gpuRootPath] used to build the ops:
     *  - big scaling_max_freq → [TdpState.bigClusterCapKhz] (kHz)
     *  - big scaling_min_freq → [TdpState.bigClusterMinKhz] (kHz)
     *  - GPU devfreq/max_freq → [TdpState.gpuDevfreqMaxHz]  (Hz)
     *  - GPU devfreq/min_freq → [TdpState.gpuDevfreqMinHz]  (Hz)
     */
    fun convergeToReadback(
        state: TdpState,
        op: TdpStateTransition.WriteOp,
        readback: Long,
        bigPolicyId: Int,
        gpuRootPath: String?,
    ): TdpState? {
        val target = op.id.target
        return when {
            target == Tunables.cpuMaxFreq(bigPolicyId).target ->
                state.copy(bigClusterCapKhz = readback.toInt())
            target == Tunables.cpuMinFreq(bigPolicyId).target ->
                state.copy(bigClusterMinKhz = readback.toInt())
            gpuRootPath != null && target == Tunables.gpuMaxFreq(gpuRootPath).target ->
                state.copy(gpuDevfreqMaxHz = readback)
            gpuRootPath != null && target == Tunables.gpuMinFreq(gpuRootPath).target ->
                state.copy(gpuDevfreqMinHz = readback)
            else -> null
        }
    }
}
