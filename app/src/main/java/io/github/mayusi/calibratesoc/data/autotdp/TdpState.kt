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
 *
 *  ── Wave 2 actuators ───────────────────────────────────────────────────────
 *  [bigClusterMinKhz]   — scaling_min_freq FLOOR for the big-cluster policy, in
 *                         kHz (the #1 smoothness win: stops down-clock dips that
 *                         stutter). MM-1 LAW: this is always strictly below
 *                         [bigClusterCapKhz]; a tighten that would invert lowers
 *                         the floor in lockstep. MM-2: both min and cap are real
 *                         OPP steps. Null = don't touch the floor.
 *  [gpuDevfreqMinHz]    — GPU devfreq min_freq in Hz (finer than the 0-7
 *                         pwrlevel; min for frame consistency). Always strictly
 *                         below [gpuDevfreqMaxHz] and within the device's probed
 *                         devfreq range. Null = don't touch the devfreq min.
 *  [gpuDevfreqMaxHz]    — GPU devfreq max_freq in Hz (max for battery). Always
 *                         strictly above [gpuDevfreqMinHz]. Null = don't touch.
 *  [uclampTopAppMin]    — cpu.uclamp.min perf-hint on the top-app cgroup slice
 *                         (0-1024), or null. ACT-2 LAW: this and
 *                         [parkedPrimeCores] are mutually exclusive on the same
 *                         cluster per goal — never both. A goal either parks OR
 *                         uclamp-hints, never both.
 *  [fanMode]            — AYN Settings.System `fan_mode` preset (0/1/4/5), or
 *                         null = don't touch the fan. Written via the
 *                         SETTINGS_SYSTEM tier (snapshotted + reverted on stop
 *                         like every other write). Rate-limited + hysteresis-
 *                         gated by the engine's fan governor before it is set.
 */
data class TdpState(
    val parkedPrimeCores: Set<Int> = emptySet(),
    val bigClusterCapKhz: Int? = null,
    val gpuFloorLevel: Int? = null,
    val governorOverrides: Map<Int, String> = emptyMap(),
    // ── Wave 2 actuators (all default absent = "don't touch") ───────────────────
    val bigClusterMinKhz: Int? = null,
    val gpuDevfreqMinHz: Long? = null,
    val gpuDevfreqMaxHz: Long? = null,
    val uclampTopAppMin: Int? = null,
    val fanMode: Int? = null,
) {
    companion object {
        /**
         * The "do nothing" baseline — no cores parked, no caps, no governor overrides,
         * no Wave-2 actuators. The daemon writes STOCK on stop to revert all AutoTDP
         * changes.
         */
        val STOCK = TdpState()
    }
}
