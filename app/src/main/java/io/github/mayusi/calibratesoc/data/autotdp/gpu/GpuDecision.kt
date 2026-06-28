package io.github.mayusi.calibratesoc.data.autotdp.gpu

/**
 * UNIT 2 (ADAPTIVE MODE) — the per-tick output of [GpuBandController.decide].
 *
 * A complete, control-ready description of what the GPU band controller wants this
 * tick: the GPU clock target (as a pwrlevel floor AND/OR a devfreq min/max window —
 * whichever the device exposes), the adaptive re-eval cadence hint, and a human reason.
 * Unit 5 merges these targets into the one composed `TdpState` (alongside the CPU
 * controller's decision) and min()s [nextTickHintMs] with the CPU hint.
 *
 * Every non-null target here is already SNAPPED to a real OPP from the caps OPP table
 * and already CLAMPED to the GPU floor invariant — Unit 5 applies them verbatim. A null
 * target means "don't touch that actuator this tick" (HOLD / unavailable), matching
 * `TdpState`'s "null = leave alone" convention so readback discipline is preserved.
 *
 * @property targetGpuLevel          Adreno max_pwrlevel floor to apply (lower index =
 *                                   faster GPU), or null to leave the pwrlevel alone.
 *                                   Maps to `TdpState.gpuFloorLevel`.
 * @property targetGpuDevfreqMinHz   GPU devfreq min_freq (Hz) to apply, or null to leave
 *                                   it alone. Maps to `TdpState.gpuDevfreqMinHz`. Always
 *                                   strictly below [targetGpuDevfreqMaxHz] and never
 *                                   below the GPU floor invariant.
 * @property targetGpuDevfreqMaxHz   GPU devfreq max_freq (Hz) to apply — the headroom
 *                                   knob the band controller hunts — or null. Maps to
 *                                   `TdpState.gpuDevfreqMaxHz`. Never below the floor
 *                                   invariant, never above the effective ceiling.
 * @property nextTickHintMs          the re-eval cadence (ms) requested for the NEXT tick
 *                                   (warming → 500, calm → 1000), 500 ms floor. Null
 *                                   only on a degenerate no-op. Unit 5 min()s it with
 *                                   the CPU controller's hint.
 * @property reason                  a short human-readable reason (CONFIG/INTENT only —
 *                                   never a measured mW/°C value), e.g. "loosen GPU →
 *                                   720 MHz" or "holding (GPU in band)".
 */
data class GpuDecision(
    val targetGpuLevel: Int?,
    val targetGpuDevfreqMinHz: Long?,
    val targetGpuDevfreqMaxHz: Long?,
    val nextTickHintMs: Int?,
    val reason: String,
)
