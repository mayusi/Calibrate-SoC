package io.github.mayusi.calibratesoc.data.autotdp

/**
 * Provenance of the *measured* fields in an [AutoTdpEffect].
 *
 * Mirrors `EstimateSource` in EfficiencyAdvisor.kt deliberately: the honesty
 * contract is identical — the UI MUST label a [MEASURED] number differently
 * from an [ESTIMATED] one and NEVER present an estimate as a measurement.
 *
 * NOTE: this labels the power/temp/fps measurement provenance ONLY. The DERIVED
 * fields ([AutoTdpEffect.capDeltaKhz], [AutoTdpEffect.parkedPrimeCores], etc.)
 * are exact applied-config facts and are populated regardless of source.
 */
enum class EffectSource {
    /** Backed by a completed on-device A/B draw probe ([SavingsResult.enoughData]). */
    MEASURED,

    /**
     * No completed probe yet. Only the DERIVED fields (cap delta, parked cores,
     * GPU floor) are trustworthy; the power/temp/fps fields are null and MUST be
     * hidden by the UI.
     */
    ESTIMATED,
}

/**
 * The "proof-of-effect" bundle for AutoTDP — what the tuner is actually DOING to
 * the device right now, split honestly into two tiers:
 *
 *  DERIVED (always populated when a state is applied):
 *    [bigCapKhz], [stockBigCeilingKhz], [parkedPrimeCores], [gpuFloorLevel],
 *    [capDeltaKhz] — exact facts read from the applied [TdpState] + [TdpCaps].
 *    No measurement required; these describe the configuration change itself.
 *
 *  MEASURED (populated ONLY when a completed probe backs them):
 *    [powerSavedMw], [powerSavedPct] — gated on [SavingsResult.enoughData].
 *    [tempDeltaC], [fpsDelta]        — set from a measured A/B probe window pair;
 *                                       null (HIDE) when no measured baseline.
 *    [sessionEnergySavedMilliWh]     — integrated off the measured power saving
 *                                       over the session; null if no measured base.
 *
 * HONESTY INVARIANTS (law — see the AutoTDP proof-of-effect spec):
 *  - Power/temp/fps fields are null unless a completed probe backs them.
 *  - [effectSource] is [EffectSource.MEASURED] only when [SavingsResult.enoughData].
 *  - [fpsDelta] must NEVER be derived from a non-real (refresh-rate fallback) FPS.
 *
 * @property bigCapKhz                  Applied big-cluster scaling_max_freq cap (kHz). Null = uncapped.
 * @property stockBigCeilingKhz         The device's stock big-cluster ceiling (top OPP, kHz). Null = unknown.
 * @property parkedPrimeCores           Prime-core indices currently offlined by AutoTDP.
 * @property gpuFloorLevel              Applied Adreno power-level floor. Null = unconstrained.
 * @property capDeltaKhz                stockBigCeilingKhz − bigCapKhz; null when either is null.
 * @property powerSavedMw               Measured mean draw saving (mW). MEASURED-only; null otherwise.
 * @property powerSavedPct              Measured draw saving as a percent. MEASURED-only; null otherwise.
 * @property tempDeltaC                 Measured baseline−tuned temp delta (°C, positive = cooler). Null = no measured base.
 * @property fpsDelta                   Measured tuned−baseline FPS delta. Null unless backed by REAL FPS in both windows.
 * @property effectSource               Provenance of the measured fields.
 * @property sessionEnergySavedMilliWh  Integrated energy saved this session (mWh). Null if no measured base.
 */
data class AutoTdpEffect(
    // ── DERIVED (always populated) ────────────────────────────────────────────
    val bigCapKhz: Int?,
    val stockBigCeilingKhz: Int?,
    val parkedPrimeCores: Set<Int>,
    val gpuFloorLevel: Int?,
    val capDeltaKhz: Int?,
    // ── MEASURED (probe-backed only) ──────────────────────────────────────────
    val powerSavedMw: Long?,
    val powerSavedPct: Double?,
    val tempDeltaC: Float?,
    val fpsDelta: Int?,
    val effectSource: EffectSource,
    val sessionEnergySavedMilliWh: Double?,
) {
    companion object {
        /**
         * Pure factory: build the effect from the applied state + caps + the
         * latest measured [savings] + how long the session has been running.
         *
         * DERIVED fields are always computed from [appliedState] / [caps].
         *
         * MEASURED fields ([powerSavedMw], [powerSavedPct], [sessionEnergySavedMilliWh])
         * are populated ONLY when [savings] is non-null AND [SavingsResult.enoughData]
         * — otherwise they are null and [effectSource] is [EffectSource.ESTIMATED].
         *
         * [tempDeltaC] and [fpsDelta] are NOT derivable from [savings] (which only
         * carries draw). This factory always sets them to null; the daemon overrides
         * them via [AutoTdpEffect.copy] when a temp/fps-bearing probe lands. This
         * keeps the honesty contract: no measured baseline → null → UI hides them.
         *
         * @param appliedState    The [TdpState] the daemon last wrote (null = nothing applied).
         * @param caps            Device envelope — supplies the stock big ceiling (top OPP).
         * @param savings         Latest measured draw delta, or null before the first probe.
         * @param sessionElapsedMs Milliseconds since the session reached RUNNING (>= 0).
         */
        fun from(
            appliedState: TdpState?,
            caps: TdpCaps?,
            savings: SavingsResult?,
            sessionElapsedMs: Long,
        ): AutoTdpEffect {
            val bigCapKhz = appliedState?.bigClusterCapKhz
            // Stock ceiling = the device's top big-cluster OPP step (highest available freq).
            val stockBigCeilingKhz = caps?.bigClusterOppStepsKhz?.maxOrNull()

            // capDelta = ceiling − cap; only meaningful when BOTH are known.
            val capDeltaKhz =
                if (bigCapKhz != null && stockBigCeilingKhz != null) {
                    stockBigCeilingKhz - bigCapKhz
                } else {
                    null
                }

            val measured = savings != null && savings.enoughData
            val powerSavedMw = if (measured) savings!!.deltaMw else null
            val powerSavedPct = if (measured) savings!!.deltaPct else null

            // Session energy integration: mWh = mW * hours.
            // Only when we have a measured power saving AND a real elapsed window.
            // Uses the measured mean saving as a steady-state approximation over the
            // session (the honest framing is "at the measured saving rate, this is
            // roughly the energy saved so far").
            val sessionEnergySavedMilliWh: Double? =
                if (measured && sessionElapsedMs > 0L) {
                    val hours = sessionElapsedMs.toDouble() / 3_600_000.0
                    savings!!.deltaMw.toDouble() * hours
                } else {
                    null
                }

            return AutoTdpEffect(
                bigCapKhz = bigCapKhz,
                stockBigCeilingKhz = stockBigCeilingKhz,
                parkedPrimeCores = appliedState?.parkedPrimeCores ?: emptySet(),
                gpuFloorLevel = appliedState?.gpuFloorLevel,
                capDeltaKhz = capDeltaKhz,
                powerSavedMw = powerSavedMw,
                powerSavedPct = powerSavedPct,
                // temp/fps require a measured A/B window pair — not in SavingsResult.
                tempDeltaC = null,
                fpsDelta = null,
                effectSource = if (measured) EffectSource.MEASURED else EffectSource.ESTIMATED,
                sessionEnergySavedMilliWh = sessionEnergySavedMilliWh,
            )
        }
    }
}
