package io.github.mayusi.calibratesoc.data.boost

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.Tunables

/**
 * PURE composer for the **Game Boost** brute "max performance" write bundle.
 *
 * Distinct from AutoTDP. Where AutoTDP *optimises* (it caps clocks to save heat
 * and battery while keeping FPS playable), Game Boost *brute-pins everything to
 * the ceiling* for raw FPS. It is time-boxed and thermally guarded, and every
 * write routes through [io.github.mayusi.calibratesoc.data.tunables.TunableWriter]
 * so it is snapshotted + readback-verified + reverted on stop.
 *
 * This object is a pure function of the [CapabilityReport]: it never touches the
 * filesystem or Android. The service feeds it a report and gets back an ordered
 * list of [BoostWriteOp]; the service then performs each write through TunableWriter
 * (which journals the previous value for revert). Keeping it pure means the
 * bundle composition is unit-testable without a device.
 *
 * ## What it pins (Odin 3 / SD8Gen3 facts, all writable-root)
 *
 *  1. **Every CPU policy** `scaling_max_freq` → that policy's top OPP (ceiling).
 *  2. **Every CPU policy** `scaling_min_freq` → a high floor (the [minFloorFraction]
 *     of the ceiling, snapped to a real OPP step) so the cluster never dips. cpu0's
 *     policy IS pinned (raising its clock is safe; cpu0 is only sacred for *offline*,
 *     which Game Boost never does — boost keeps every core online).
 *  3. **Every CPU policy** governor → `performance` (when the policy lists it).
 *  4. **GPU** `max_pwrlevel` = 0 (fastest Adreno level).
 *  5. **GPU** `min_pwrlevel` = 0 (pin the floor high too — no down-level).
 *  6. **GPU devfreq** `min_freq` = `max_freq` = devfreq ceiling (pin high for frame
 *     consistency). Only when a real devfreq table was probed.
 *  7. **GPU** governor → `performance` when the device lists it (else left alone).
 *  8. **GPU** `idle_timer` → a large value ([GPU_IDLE_TIMER_MS]) so the GPU does not
 *     clock-gate mid-frame. We deliberately do **not** write `force_clk_on=1` — that
 *     pins the rail on indefinitely and cooks the device; a large idle_timer gives the
 *     same "don't gate during the session" behaviour without the thermal runaway.
 *  9. **DDR/LLCC devfreq** `min_freq` = `max_freq` (frame-consistency) for each probed
 *     bus/DDR devfreq device.
 * 10. **fan_mode** → Sport ([FAN_MODE_SPORT]) for max cooling while boosted — ONLY when
 *     a controllable vendor fan key is supplied. Honest skip otherwise.
 *
 * ## Honesty contract
 *
 *  - A node that is NOT present on this firmware produces NO write op. We never
 *    fabricate a path. The probe is the source of truth — empty list → no op.
 *  - cpu0 is never offlined (Game Boost never parks cores at all).
 *  - GPU thermal `throttling` is NEVER disabled (that risks hardware damage; the
 *    thermal cap + kernel trip stay armed). Game Boost trades battery + heat for FPS,
 *    NOT safety.
 *  - The fan key is supplied by the caller (resolved from the device adapter), exactly
 *    like AutoTDP resolves it — this object does not invent a Settings key.
 */
object GameBoostBundle {

    /**
     * One pin write. Mirrors [io.github.mayusi.calibratesoc.data.autotdp.TdpStateTransition.WriteOp]
     * so the service applies it through the identical TunableWriter path.
     */
    data class BoostWriteOp(
        val id: TunableId,
        val value: String,
        val description: String,
    )

    /**
     * Build the full brute-max bundle for [report].
     *
     * @param report     Live capability report (the probe is the source of truth).
     * @param fanModeKey The vendor Settings.System fan key (e.g. "fan_mode"), or null
     *                   when this device has no controllable fan key — in which case the
     *                   fan op is honestly skipped. Resolved by the service from the
     *                   device adapter, exactly like AutoTDP.
     * @param setFanSport When true AND [fanModeKey] is non-null, emit a fan_mode=Sport
     *                    op. Defaults true (config-controlled by the service).
     */
    fun build(
        report: CapabilityReport,
        fanModeKey: String?,
        setFanSport: Boolean = true,
    ): List<BoostWriteOp> {
        val ops = mutableListOf<BoostWriteOp>()

        // ── 1+2+3. Every CPU policy: max=ceiling, min=high-floor, governor=performance ──
        for (policy in report.cpuPolicies) {
            val opps = policy.availableFreqsKhz.sorted()
            val ceiling = opps.lastOrNull() ?: continue // no OPP table → skip this policy
            // 1. Pin max to the ceiling.
            ops += BoostWriteOp(
                id = Tunables.cpuMaxFreq(policy.policyId),
                value = ceiling.toString(),
                description = "BOOST cpu policy${policy.policyId} max → ${ceiling / 1000} MHz (ceiling)",
            )
            // 2. Raise the floor to a high OPP step so the cluster never dips.
            val floor = highFloorStep(opps, ceiling)
            ops += BoostWriteOp(
                id = Tunables.cpuMinFreq(policy.policyId),
                value = floor.toString(),
                description = "BOOST cpu policy${policy.policyId} min floor → ${floor / 1000} MHz",
            )
            // 3. performance governor where available (honest skip otherwise).
            if (policy.availableGovernors.any { it.equals(PERFORMANCE_GOVERNOR, ignoreCase = true) }) {
                ops += BoostWriteOp(
                    id = Tunables.cpuGovernor(policy.policyId),
                    value = PERFORMANCE_GOVERNOR,
                    description = "BOOST cpu policy${policy.policyId} governor → performance",
                )
            }
        }

        // ── 4-8. GPU pins (Adreno) ─────────────────────────────────────────────────
        val gpu = report.gpu
        if (gpu != null) {
            val root = gpu.rootPath

            // 4+5. Adreno power levels: pin BOTH min and max to 0 (fastest level).
            // Only when this is an Adreno GPU exposing pwrlevels (powerLevelRange non-null
            // or adrenoExtras present). On Mali these nodes don't exist → honest skip.
            val adrenoExtras = report.adrenoExtras
            val hasPwrLevels = gpu.family == GpuFamily.ADRENO &&
                (gpu.powerLevelRange != null || adrenoExtras != null)
            if (hasPwrLevels) {
                ops += BoostWriteOp(
                    id = Tunables.adrenoMaxPowerLevel(root),
                    value = ADRENO_FASTEST_LEVEL.toString(),
                    description = "BOOST GPU max_pwrlevel → 0 (fastest)",
                )
                ops += BoostWriteOp(
                    id = KernelTunables.adrenoMinPowerLevel(root),
                    value = ADRENO_FASTEST_LEVEL.toString(),
                    description = "BOOST GPU min_pwrlevel → 0 (pin floor high)",
                )
            }

            // 6. GPU devfreq min=max=ceiling — pin high for frame consistency.
            // Only when a real devfreq OPP table (or a sane min<max) was probed.
            val gpuCeilHz: Long? = when {
                gpu.availableFreqsHz.isNotEmpty() -> gpu.availableFreqsHz.max()
                gpu.currentMaxHz > 0L -> gpu.currentMaxHz
                else -> null
            }
            if (gpuCeilHz != null && gpuCeilHz > 0L) {
                // Order matters: raise max first, then pull min up to it, so min never
                // momentarily exceeds a not-yet-raised max (kernel EINVAL guard).
                ops += BoostWriteOp(
                    id = Tunables.gpuMaxFreq(root),
                    value = gpuCeilHz.toString(),
                    description = "BOOST GPU devfreq max → ${gpuCeilHz / 1_000_000} MHz (ceiling)",
                )
                ops += BoostWriteOp(
                    id = Tunables.gpuMinFreq(root),
                    value = gpuCeilHz.toString(),
                    description = "BOOST GPU devfreq min → ${gpuCeilHz / 1_000_000} MHz (pin high)",
                )
            }

            // 7. GPU governor → performance when listed (honest skip otherwise).
            if (gpu.availableGovernors.any { it.equals(PERFORMANCE_GOVERNOR, ignoreCase = true) }) {
                ops += BoostWriteOp(
                    id = Tunables.gpuGovernor(root),
                    value = PERFORMANCE_GOVERNOR,
                    description = "BOOST GPU governor → performance",
                )
            }

            // 8. Large idle_timer so the GPU does not clock-gate mid-session.
            // We write this only when the probe confirmed the node exists (idleTimerMs
            // is non-null in adrenoExtras). NEVER force_clk_on=1 — that cooks the rail.
            if (gpu.family == GpuFamily.ADRENO && adrenoExtras?.idleTimerMs != null) {
                ops += BoostWriteOp(
                    id = KernelTunables.gpuIdleTimer(root),
                    value = GPU_IDLE_TIMER_MS.toString(),
                    description = "BOOST GPU idle_timer → $GPU_IDLE_TIMER_MS ms (don't clock-gate)",
                )
            }
        }

        // ── 9. DDR / LLCC bus devfreq: min=max=ceiling for frame consistency ───────
        for (dev in report.devfreqDevices) {
            val ceil = dev.maxFreqHz
            if (ceil <= 0L) continue // no usable ceiling → honest skip
            // max first, then min (same EINVAL ordering guard as the GPU above).
            ops += BoostWriteOp(
                id = KernelTunables.devfreqMaxFreq(dev.deviceName),
                value = ceil.toString(),
                description = "BOOST devfreq ${dev.deviceName} max → ${ceil / 1_000_000} MHz",
            )
            ops += BoostWriteOp(
                id = KernelTunables.devfreqMinFreq(dev.deviceName),
                value = ceil.toString(),
                description = "BOOST devfreq ${dev.deviceName} min → ${ceil / 1_000_000} MHz (pin high)",
            )
        }

        // ── 10. fan_mode = Sport for max cooling (honest skip when no key) ─────────
        if (setFanSport && fanModeKey != null) {
            ops += BoostWriteOp(
                id = Tunables.settingsSystemKey(fanModeKey),
                value = FAN_MODE_SPORT.toString(),
                description = "BOOST fan_mode → Sport ($FAN_MODE_SPORT)",
            )
        }

        return ops
    }

    /**
     * Choose a high min-freq floor: the highest OPP step that is at or below
     * [minFloorFraction] of the ceiling. This keeps a strong floor (no deep dips)
     * while staying strictly below the ceiling so we never write min > max, and it is
     * always a REAL OPP step (never a fabricated kHz the kernel would clamp).
     *
     * Falls back to the second-highest step when the fraction lands on the ceiling
     * itself, and to the ceiling only when the table has a single entry.
     */
    private fun highFloorStep(sortedOpps: List<Int>, ceiling: Int): Int {
        if (sortedOpps.size <= 1) return ceiling
        val target = (ceiling * MIN_FLOOR_FRACTION).toInt()
        // Highest OPP step <= target, but strictly below the ceiling.
        val belowCeil = sortedOpps.filter { it < ceiling }
        val candidate = belowCeil.lastOrNull { it <= target }
        return candidate ?: belowCeil.last() // no step <= target → next-below-ceiling step
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    /** Adreno fastest power level. Lower index = faster; 0 is the ceiling. */
    const val ADRENO_FASTEST_LEVEL = 0

    /** performance governor name (CPU + GPU). */
    const val PERFORMANCE_GOVERNOR = "performance"

    /**
     * Min-freq floor as a fraction of the ceiling. 0.80 keeps the cluster within
     * 20% of the top — a strong "never dip" floor for raw FPS without literally
     * pinning min == max (which some kernels reject as a degenerate range).
     */
    const val MIN_FLOOR_FRACTION = 0.80f

    /**
     * GPU idle_timer (ms) during a boost session. Large enough that the GPU never
     * clock-gates within a frame's worth of idle, but NOT infinite — this is the
     * honest alternative to force_clk_on=1 (which pins the rail on permanently and
     * cooks the device). 10 000 ms = the GPU stays clocked for 10 s of idle, which
     * never happens mid-game, then gates normally if the session ends.
     */
    const val GPU_IDLE_TIMER_MS = 10_000

    /**
     * AYN fan preset for Sport (max cooling). Discrete AYN presets are
     * {Quiet=0, Smart=4, Sport=5}; Sport spins the fan hardest, which is what a
     * brute-max boost wants. Matches the device probe facts.
     */
    const val FAN_MODE_SPORT = 5
}
