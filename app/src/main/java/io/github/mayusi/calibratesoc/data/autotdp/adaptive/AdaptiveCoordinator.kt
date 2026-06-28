package io.github.mayusi.calibratesoc.data.autotdp.adaptive

import io.github.mayusi.calibratesoc.data.autotdp.AdaptiveRunConfig
import io.github.mayusi.calibratesoc.data.autotdp.ControllerState
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.autotdp.TdpDecision
import io.github.mayusi.calibratesoc.data.autotdp.TdpState
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuControllerState
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuDecision
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuOcThermalGuard
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuOcVerdict

/**
 * UNIT 5 (ADAPTIVE MODE) — the COORDINATOR: composes the CPU engine's decision, the GPU
 * band controller's decision, and (when beyond-stock OC is armed) the GPU-OC thermal
 * guard into ONE [TdpState] per tick, plus the merged controller states + a combined
 * reason for the live readout.
 *
 * ## What it composes (per tick)
 *
 *  1. **Setpoints** — [resolveSetpoints] runs [AdaptivePolicy.resolve] on the effective
 *     [AdaptiveConfig] (intent + tier + consent + cached probe verdict). The policy is the
 *     ONLY place beyond-stock is gated: tier == BEYOND_STOCK ⇒ resolved only when
 *     `consent && verdict == Accepted`; otherwise the resolved tier clamps to
 *     WITHIN_VENDOR/OFF and the GPU governs within the STOCK ceiling.
 *  2. **CPU side** — the daemon calls the EXISTING [io.github.mayusi.calibratesoc.data
 *     .autotdp.AutoTdpEngine.decide] with `goalOverride = setpoints.cpuGoal` and
 *     `goalParams = setpoints.cpuGoalParams`; its [TdpDecision] (cap-floor, uclamp/park,
 *     thermal pre-empt + 105 °C kill — ALL enforced INSIDE `decide`) is handed here.
 *  3. **GPU side** — the daemon calls the EXISTING `GpuBandController.decide` with the
 *     effective ceiling from [effectiveCeilHz] (stock by default, the OC ceiling only
 *     when beyond-stock is genuinely armed); its [GpuDecision] is handed here.
 *  4. **OC thermal guard** — when beyond-stock is armed, the daemon evaluates
 *     [GpuOcThermalGuard] every tick and passes the result via [GpuOcGuardOutcome]. The
 *     guard's `newMaxHz` is an UPWARD CLAMP on the GPU max: the composed GPU max can never
 *     exceed it, so a soft back-off / hard disarm / slope arm always wins downward. Once
 *     the guard latches off, the GPU governs within stock for the rest of the hot episode.
 *  5. **DDR bias** — [AdaptiveSetpoints.ddrBias] is carried in the combined reason for the
 *     readout. [TdpState] has no DDR/bus-devfreq actuator today and the engine's
 *     snapshot-then-write revert path only covers the fields it models, so wiring a bus
 *     devfreq write here would add an un-reverted actuator — OUT OF SCOPE for this surgical
 *     unit. Tracked as a follow-up (a real `busDevfreqMinHz` field on [TdpState] + its
 *     [io.github.mayusi.calibratesoc.data.autotdp.TdpStateTransition] delta + writer tier).
 *     See [composeDdrNote]. The bias is surfaced honestly; it is not silently dropped.
 *  6. **Cadence** — `nextTickHintMs = min(cpu hint, gpu hint)`, floored at [MIN_TICK_MS].
 *
 * ## Precedence (LAW — the coordinator never bypasses a safety invariant)
 *
 *  - The 40 % CPU cap floor, the per-cluster park/uclamp mutual-exclusion, the thermal
 *    pre-empt and the 105 °C NonCancellable kill all live INSIDE `decide()`; the GPU floor
 *    invariant lives INSIDE `GpuBandController`. The coordinator MERGES their outputs — it
 *    never recomputes or relaxes them.
 *  - Beyond-stock OC can only ever push the GPU max UPWARD when armed, and the OC thermal
 *    guard can override that DOWNWARD on heat (soft step / hard disarm / slope arm). The
 *    guard's clamp is applied last so heat always wins.
 *  - One composed [TdpState] → one revert path. Folding the GPU devfreq min/max (and the
 *    beyond-stock max) into the single [TdpState] means the daemon's existing
 *    snapshot-then-write [io.github.mayusi.calibratesoc.data.tunables.TunableWriter] revert
 *    restores CPU + GPU + OC to stock on every exit — no separate GPU revert needed.
 *
 * ## Mutual exclusion
 *
 * When Adaptive is the active mode it is the UNIFIED driver: the daemon suspends the legacy
 * single-goal / GameBoost / ThrottleGuard one-shots (the BoostArbiter clock-ownership claim
 * the daemon already makes handles GameBoost + the throttle guard; the legacy goal path is
 * simply not taken on the adaptive branch). The coordinator assumes it is the sole driver.
 *
 * ## Purity
 *
 * [compose] / [resolveSetpoints] / [effectiveCeilHz] are PURE (no Android, I/O, or time).
 * The probe + the synthetic load are I/O and live in [io.github.mayusi.calibratesoc.data
 * .autotdp.gpu.GpuOcProber], run ONCE at engage off the tick thread — never per tick here.
 */
object AdaptiveCoordinator {

    /** Hard minimum re-eval cadence (ms) — the 2 Hz battery-safety floor (mirrors the engine). */
    const val MIN_TICK_MS = 500

    /**
     * The resolved Adaptive run configuration the coordinator consumes — the value the VM
     * exposes and the daemon caches at engage. PURE.
     *
     * @property intent             the normalized user intent (weights) the policy maps.
     * @property gpuOcTier          the user's chosen OC tier (consent already gated in the VM).
     * @property beyondStockConsent the raw consent flag (the second gate; the policy reads it).
     * @property probeVerdict       the cached beyond-stock probe verdict, or null when never
     *                              probed. Beyond-stock resolves ONLY when this is
     *                              [GpuOcVerdict.Accepted] AND the tier is BEYOND_STOCK AND
     *                              consent is granted.
     */
    data class AdaptiveConfig(
        val intent: AdaptiveIntent,
        val gpuOcTier: GpuOcTier,
        val beyondStockConsent: Boolean,
        val probeVerdict: GpuOcVerdict? = null,
    )

    /**
     * The OC thermal-guard result the daemon evaluated this tick, threaded in so [compose]
     * stays pure. Null when beyond-stock is NOT armed this session (no guard runs).
     *
     * @property newMaxHz        the guard's cap target — an UPWARD CLAMP on the GPU max.
     * @property disarmedToStock true when the guard slammed the cap to (or below) stock.
     */
    data class GpuOcGuardOutcome(
        val newMaxHz: Long,
        val disarmedToStock: Boolean,
    ) {
        companion object {
            /** Build from a raw [GpuOcThermalGuard.GpuOcGuardDecision]. */
            fun from(decision: GpuOcThermalGuard.GpuOcGuardDecision): GpuOcGuardOutcome =
                GpuOcGuardOutcome(
                    newMaxHz = decision.newMaxHz,
                    disarmedToStock = decision.disarmedToStock,
                )
        }
    }

    /**
     * The composed per-tick output: one [TdpState] + the merged controller states + a
     * combined reason. The daemon applies [target] via the SAME TunableWriter and reverts
     * via the SAME revert handle; it persists [cpuControllerState] + [gpuControllerState]
     * into the next tick.
     *
     * @property target             the single composed kernel-target state (CPU ⊕ GPU ⊕ OC).
     * @property cpuControllerState  the engine's carried [ControllerState] to thread forward.
     * @property gpuControllerState  the GPU band controller's carried state to thread forward.
     * @property nextTickHintMs      min(cpu hint, gpu hint), floored at [MIN_TICK_MS].
     * @property reason              a combined human-readable reason for the live readout.
     */
    data class Composed(
        val target: TdpState,
        val cpuControllerState: ControllerState,
        val gpuControllerState: GpuControllerState,
        val nextTickHintMs: Int,
        val reason: String,
    )

    /**
     * Rebuild the pure [AdaptiveConfig] from the serializable [AdaptiveRunConfig] carrier
     * (the form that survives the start-intent extras). Parses the cached verdict record
     * back into a [GpuOcVerdict]; an unparseable/absent record degrades to null (never
     * beyond-stock without a real Accepted verdict).
     */
    fun fromRunConfig(run: AdaptiveRunConfig): AdaptiveConfig =
        AdaptiveConfig(
            intent = AdaptiveIntent(
                wPerformance = run.wPerformance,
                wBattery = run.wBattery,
                wStability = run.wStability,
                wThermalHeadroom = run.wThermalHeadroom,
            ),
            gpuOcTier = GpuOcTier.entries.getOrElse(run.gpuOcTierOrdinal) { GpuOcTier.OFF },
            beyondStockConsent = run.beyondStockConsent,
            probeVerdict = parseVerdict(run.probeVerdictRecord),
        )

    /**
     * Parse a cached verdict record string back into a [GpuOcVerdict]. Accepts the bare
     * verdict form ("Accepted:<hz>" / "Rejected:<hz>" / "Ineffective" / "Unsupported") the
     * prefs store emits (the device-fingerprint prefix, if any, is stripped by the caller).
     * Anything unrecognized → null (treated as "never probed" — beyond-stock stays gated).
     */
    fun parseVerdict(record: String?): GpuOcVerdict? {
        if (record.isNullOrBlank()) return null
        val v = record.substringAfterLast('|').trim() // tolerate a "<fingerprint>|<verdict>" form
        return when {
            v == "Unsupported" -> GpuOcVerdict.Unsupported
            v == "Ineffective" -> GpuOcVerdict.Ineffective
            v.startsWith("Accepted:") ->
                v.removePrefix("Accepted:").trim().toLongOrNull()?.let { GpuOcVerdict.Accepted(it) }
            v.startsWith("Rejected:") ->
                v.removePrefix("Rejected:").trim().toLongOrNull()?.let { GpuOcVerdict.Rejected(it) }
            else -> null
        }
    }

    /**
     * Resolve the [AdaptiveSetpoints] for this session/config. The policy gates beyond-stock
     * on tier + consent + an Accepted verdict; everything else is a pure weight mapping.
     */
    fun resolveSetpoints(config: AdaptiveConfig, caps: TdpCaps): AdaptiveSetpoints =
        AdaptivePolicy.resolve(
            intent = config.intent,
            caps = caps,
            userOptInBeyondStock = config.gpuOcTier == GpuOcTier.BEYOND_STOCK &&
                config.beyondStockConsent,
            probePassed = config.probeVerdict is GpuOcVerdict.Accepted,
        )

    /**
     * The effective GPU devfreq ceiling the band controller may govern up to.
     *
     *  - When the resolved [setpoints] tier is BEYOND_STOCK AND the cached [config]
     *    verdict is [GpuOcVerdict.Accepted], the ceiling is the verdict's observed
     *    `reachedHz` (the clock the probe actually saw above stock), capped to the top OC
     *    OPP the caps table knows about — never a number the device can't reach.
     *  - In EVERY other case (tier OFF/WITHIN_VENDOR, no consent, or a non-Accepted
     *    verdict) the ceiling is the STOCK [TdpCaps.gpuDevfreqCeilHz]. The band controller
     *    then governs strictly within stock — beyond-stock is impossible without all gates.
     */
    fun effectiveCeilHz(
        config: AdaptiveConfig,
        setpoints: AdaptiveSetpoints,
        caps: TdpCaps,
    ): Long? {
        val stockCeil = caps.gpuDevfreqCeilHz
        val verdict = config.probeVerdict
        val beyondStockArmed = setpoints.gpuOcTier == GpuOcTier.BEYOND_STOCK &&
            verdict is GpuOcVerdict.Accepted
        if (!beyondStockArmed || stockCeil == null) return stockCeil
        // The OC ceiling = the clock the probe actually OBSERVED above stock, but never
        // beyond the top OPP the caps table advertises (honesty: we never govern to a
        // frequency the device cannot produce). Never below the stock ceiling.
        val topOpp = caps.gpuDevfreqStepsHz.maxOrNull() ?: stockCeil
        val ocReached = (verdict as GpuOcVerdict.Accepted).reachedHz
        return maxOf(stockCeil, minOf(ocReached, topOpp))
    }

    /**
     * Whether beyond-stock OC is genuinely ARMED for this config (all three gates pass).
     * The daemon uses this to decide whether to run [GpuOcThermalGuard] each tick.
     */
    fun beyondStockArmed(config: AdaptiveConfig, setpoints: AdaptiveSetpoints): Boolean =
        setpoints.gpuOcTier == GpuOcTier.BEYOND_STOCK &&
            config.beyondStockConsent &&
            config.probeVerdict is GpuOcVerdict.Accepted

    /**
     * Compose the CPU decision, the GPU decision, and (when armed) the OC guard outcome
     * into ONE [TdpState] + merged states + reason. PURE.
     *
     * @param cpuDecision  the [TdpDecision] the engine returned this tick (cap/uclamp/park
     *                     + cap-floor + thermal pre-empt/kill ALREADY enforced inside).
     * @param gpuDecision  the [GpuDecision] the GPU band controller returned this tick
     *                     (GPU floor invariant + fast-down + load-blind HOLD enforced).
     * @param gpuState     the GPU band controller's carried state (already the post-tick
     *                     value the daemon will thread forward).
     * @param ocGuard      the OC thermal-guard outcome when beyond-stock is armed, else
     *                     null. When non-null its `newMaxHz` clamps the GPU max DOWNWARD.
     * @param setpoints    the resolved setpoints (for the DDR-bias reason note).
     */
    fun compose(
        cpuDecision: TdpDecision,
        gpuDecision: GpuDecision,
        gpuState: GpuControllerState,
        ocGuard: GpuOcGuardOutcome?,
        setpoints: AdaptiveSetpoints,
    ): Composed {
        val cpuTarget = cpuDecision.target

        // ── GPU max with the OC thermal guard applied as an UPWARD CLAMP ───────────────
        // The band controller may have proposed a GPU max up to the effective (OC) ceiling.
        // When the guard is active it trims that down on heat: the composed max can never
        // exceed the guard's newMaxHz. A null gpu max means "don't touch the GPU max this
        // tick" (HOLD) — we leave it null UNLESS the guard is forcing a downward cap, in
        // which case we must assert the guard's cap even on a HOLD tick (heat overrides).
        val bandMaxHz = gpuDecision.targetGpuDevfreqMaxHz
        val composedGpuMaxHz: Long? = when {
            ocGuard == null -> bandMaxHz
            bandMaxHz == null -> {
                // Band is holding the GPU max this tick. If the guard disarmed to stock we
                // must still assert that downward cap (it overrides a hold); otherwise honour
                // the hold (the guard is clear and the band chose not to move the max).
                if (ocGuard.disarmedToStock) ocGuard.newMaxHz else null
            }
            // Band proposed a max — clamp it to the guard's ceiling (heat wins downward).
            else -> minOf(bandMaxHz, ocGuard.newMaxHz)
        }

        // The GPU min must never exceed the (possibly guard-clamped) max. The band already
        // holds min strictly below its proposed max; if the guard pulled the max below the
        // band's min we drop the min to null (let the kernel keep its own min) rather than
        // emit an inverted min>max pair. Null min = "don't touch" (readback discipline).
        val bandMinHz = gpuDecision.targetGpuDevfreqMinHz
        val composedGpuMinHz: Long? = when {
            bandMinHz == null -> null
            composedGpuMaxHz == null -> bandMinHz
            bandMinHz < composedGpuMaxHz -> bandMinHz
            else -> null
        }

        // ── Merge CPU ⊕ GPU into one TdpState ──────────────────────────────────────────
        // Start from the CPU decision (it carries cap/min/park/uclamp/governors/fan) and
        // overlay the GPU actuator fields. Null GPU fields preserve the CPU target's "don't
        // touch" so a HOLD tick never spuriously clears a GPU field the CPU side left alone.
        val composed = cpuTarget.copy(
            gpuFloorLevel = gpuDecision.targetGpuLevel ?: cpuTarget.gpuFloorLevel,
            gpuDevfreqMinHz = composedGpuMinHz ?: cpuTarget.gpuDevfreqMinHz,
            gpuDevfreqMaxHz = composedGpuMaxHz ?: cpuTarget.gpuDevfreqMaxHz,
        )

        // ── Cadence: min(cpu, gpu), floored ────────────────────────────────────────────
        val cpuHint = cpuDecision.nextTickHintMs
        val gpuHint = gpuDecision.nextTickHintMs
        val mergedHint = when {
            cpuHint != null && gpuHint != null -> minOf(cpuHint, gpuHint)
            cpuHint != null -> cpuHint
            gpuHint != null -> gpuHint
            else -> MIN_TICK_MS
        }.coerceAtLeast(MIN_TICK_MS)

        return Composed(
            target = composed,
            cpuControllerState = cpuDecision.controllerState,
            gpuControllerState = gpuState,
            nextTickHintMs = mergedHint,
            reason = composeReason(cpuDecision, gpuDecision, ocGuard, setpoints),
        )
    }

    /** Combined human-readable reason for the live readout: CPU · GPU · DDR (· OC guard). */
    private fun composeReason(
        cpuDecision: TdpDecision,
        gpuDecision: GpuDecision,
        ocGuard: GpuOcGuardOutcome?,
        setpoints: AdaptiveSetpoints,
    ): String {
        val sb = StringBuilder()
        sb.append("CPU: ").append(cpuDecision.reason)
        sb.append(" · GPU: ").append(gpuDecision.reason)
        sb.append(composeDdrNote(setpoints))
        if (ocGuard != null && ocGuard.disarmedToStock) {
            sb.append(" · OC guard: disarmed to stock (heat)")
        }
        return sb.toString()
    }

    /**
     * The DDR-bias readout note. DDR is surfaced (not actuated) — see the class doc: there
     * is no [TdpState] bus-devfreq actuator + revert tier yet, so applying a bus write here
     * would be un-reverted. Tracked as a follow-up; the bias is reported honestly.
     */
    private fun composeDdrNote(setpoints: AdaptiveSetpoints): String = when (setpoints.ddrBias) {
        DdrBias.HIGH -> " · DDR bias: high"
        DdrBias.LOW -> " · DDR bias: low"
        DdrBias.NORMAL -> ""
    }
}
