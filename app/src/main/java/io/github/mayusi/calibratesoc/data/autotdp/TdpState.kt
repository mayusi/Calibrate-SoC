package io.github.mayusi.calibratesoc.data.autotdp

/**
 * Desired power state expressed as a set of kernel-tunable deltas.
 *
 * This is a DESIRED state, not a diff — the daemon always writes the full
 * target, relying on [TunableWriter]'s snapshot-then-write to handle revert.
 *
 * Fields:
 *  [parkedPrimeCores]   — set of prime-cluster CPU indices to offline. cpu0
 *                         MUST NEVER appear here (enforced by [AutoTdpEngine]).
 *  [bigClusterCapKhz]   — scaling_max_freq cap for the big (gold/prime combined)
 *                         cluster policy, in kHz. Null = don't touch the cap.
 *  [gpuFloorLevel]      — Adreno max_pwrlevel floor (lower index = faster GPU).
 *                         Null = don't constrain GPU. When AutoTDP is GPU-bound
 *                         it keeps this low (permissive) to prioritise the GPU.
 *  [governorOverrides]  — policyId → governor string for any policies the engine
 *                         wants to switch (e.g. "conservative" on little cluster).
 *                         Empty map = leave governors unchanged.
 */
data class TdpState(
    val parkedPrimeCores: Set<Int> = emptySet(),
    val bigClusterCapKhz: Int? = null,
    val gpuFloorLevel: Int? = null,
    val governorOverrides: Map<Int, String> = emptyMap(),
) {
    companion object {
        /**
         * The "do nothing" baseline — no cores parked, no caps, no governor overrides.
         * The daemon writes STOCK on stop to revert all AutoTDP changes.
         */
        val STOCK = TdpState()
    }
}
