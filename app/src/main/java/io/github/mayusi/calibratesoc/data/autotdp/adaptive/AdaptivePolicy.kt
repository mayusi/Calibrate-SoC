package io.github.mayusi.calibratesoc.data.autotdp.adaptive

import io.github.mayusi.calibratesoc.data.autotdp.GoalParams
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import kotlin.math.roundToInt

/**
 * UNIT 1 (ADAPTIVE MODE) — the PURE policy: maps an [AdaptiveIntent] onto a concrete
 * [AdaptiveSetpoints]. No I/O, no Android, no time — a deterministic function of the
 * weights (plus caps/opt-in/probe for GPU-OC tier gating only). Fully unit-testable.
 *
 * The mapping below is the approved design (§3.3). Every coefficient is LAW — Units
 * 2/4/5 and the unit tests assert the exact resolved numbers for each preset. Do not
 * "round" or "improve" the constants here without re-approving the design.
 *
 * ## CPU-goal mapping note (read this)
 *
 * The design names FIVE cpu-goal tiers (MAX_FPS-class / PERFORMANCE-"mostly stable"-class
 * / BALANCED_SMART / EFFICIENCY-class / MAX_BATTERY-class). The real [GoalProfile] enum
 * exposes only FOUR magnitude-distinct curated bands — there is NO separate
 * "PERFORMANCE" profile sitting between MAX_FPS and BALANCED_SMART. We therefore map to
 * the CLOSEST existing profiles:
 *
 *   design tier                         → real GoalProfile        (note)
 *   ──────────────────────────────────────────────────────────────────────────────────
 *   MAX_FPS-class      (perfMinusBatt ≥ .40) → MAX_FPS
 *   PERFORMANCE-class  (≥ .15)               → MAX_FPS             (closest existing*)
 *   BALANCED           (> -.15)              → BALANCED_SMART      (the default)
 *   EFFICIENCY-class   (> -.40)              → COOL_QUIET          (smooth+cool, no watts cap)
 *   MAX_BATTERY-class  (else)                → BATTERY_SAVER       (strongest tighten + cap)
 *
 *   *No distinct PERFORMANCE band exists. The two perf tiers both resolve to MAX_FPS at
 *    the profile level; what actually separates them is the STABILITY one-notch shift
 *    (below) plus the continuous GPU band / floor / OC-tier / DDR outputs, which differ
 *    sharply between them. Flagged in the unit report.
 *
 * ## Stability one-notch shift
 *
 * When wStability ≥ 0.30 the user is asking for steadier frames, so we nudge the cpu
 * goal ONE notch toward the wider-band / slower-loosen profile (MAX_FPS → BALANCED_SMART
 * → COOL_QUIET → BATTERY_SAVER), capped at BATTERY_SAVER. Band WIDTH is also widened by
 * stability independently (see gpuBandWidth). Stability never shifts past the battery end.
 */
object AdaptivePolicy {

    /**
     * Resolve a normalized-or-raw [intent] into control-ready [AdaptiveSetpoints].
     *
     * The intent is normalized first (so callers may pass raw slider weights). [caps] is
     * accepted for signature stability and future tier snapping but is NOT consulted for
     * the weight-driven outputs — only the GPU-OC tier gating reads opt-in + probe. Unit
     * 5 does the device-specific snapping against [caps].
     *
     * @param intent                the four-weight user intent (normalized internally).
     * @param caps                  the device envelope (reserved for snapping; pure here).
     * @param userOptInBeyondStock  user explicitly enabled beyond-stock GPU OC.
     * @param probePassed           the GPU-OC stability probe passed on this device.
     */
    fun resolve(
        intent: AdaptiveIntent,
        caps: TdpCaps,
        userOptInBeyondStock: Boolean,
        probePassed: Boolean,
    ): AdaptiveSetpoints {
        val n = intent.normalized()
        val wPerf = n.wPerformance
        val wBatt = n.wBattery
        val wStab = n.wStability
        val wThermal = n.wThermalHeadroom

        // ── CPU goal: magnitude axis = perf minus battery, in -0.6..+0.6 ────────────
        val perfMinusBatt = wPerf - wBatt
        val baseGoal: GoalProfile = when {
            perfMinusBatt >= 0.40f -> GoalProfile.MAX_FPS            // MAX_FPS-class
            perfMinusBatt >= 0.15f -> GoalProfile.MAX_FPS            // PERFORMANCE-class (closest)
            perfMinusBatt > -0.15f -> GoalProfile.BALANCED_SMART     // BALANCED (the default)
            perfMinusBatt > -0.40f -> GoalProfile.COOL_QUIET         // EFFICIENCY-class
            else                    -> GoalProfile.BATTERY_SAVER     // MAX_BATTERY-class
        }
        // Stability ≥ 0.30 → nudge ONE notch toward the wider-band / slower-loosen goal.
        val cpuGoal = if (wStab >= 0.30f) baseGoal.shiftedTowardStability() else baseGoal

        // ── CPU temp ceiling: tighter as thermal headroom matters more ──────────────
        // 95 at wThermal=0 → 75 at wThermal=1; clamped [70..95]. Only ever TIGHTENS.
        val cpuTempCeilingC = (95f - 20f * wThermal).roundToInt().coerceIn(70, 95)
        val cpuGoalParams = GoalParams.DEFAULT.copy(
            tempCeilingC = GoalParams.clampTempCeiling(cpuTempCeilingC),
        )

        // ── GPU band: center slides with perf−batt, width widens with stability ─────
        val gpuBandCenter = (55f + 30f * (wPerf - wBatt)).roundToInt()
        val gpuBandWidth = (16f + 14f * wStab).roundToInt()
        val gpuLow = (gpuBandCenter - gpuBandWidth / 2).coerceIn(20, 80)
        val gpuHigh = (gpuBandCenter + gpuBandWidth / 2).coerceIn(30, 95)
        val gpuBand = GpuBand(low = gpuLow, high = gpuHigh)

        // ── GPU floor fraction: more perf → higher minimum clock ────────────────────
        val gpuFloorFraction = (0.15f + 0.55f * wPerf).coerceIn(0.15f, 0.90f)

        // ── GPU OC tier: beyond-stock gated by perf AND opt-in AND probe ────────────
        val gpuOcTier = when {
            wPerf >= 0.55f && userOptInBeyondStock && probePassed -> GpuOcTier.BEYOND_STOCK
            wPerf >= 0.40f -> GpuOcTier.WITHIN_VENDOR
            else -> GpuOcTier.OFF
        }

        // ── GPU soft die-temp: tighter as thermal headroom matters more ─────────────
        val gpuSoftTempC = (92f - 17f * wThermal).roundToInt()

        // ── DDR bias: perf-lean high, battery-lean low, else vendor default ─────────
        val ddrBias = when {
            wPerf >= 0.45f -> DdrBias.HIGH
            wBatt >= 0.45f -> DdrBias.LOW
            else -> DdrBias.NORMAL
        }

        return AdaptiveSetpoints(
            cpuGoal = cpuGoal,
            cpuGoalParams = cpuGoalParams,
            gpuBand = gpuBand,
            gpuFloorFraction = gpuFloorFraction,
            gpuOcTier = gpuOcTier,
            gpuSoftTempC = gpuSoftTempC,
            ddrBias = ddrBias,
        )
    }

    /**
     * Move one notch down the magnitude ladder toward the wider-band / slower-loosen
     * (steadier) profile, capped at the battery end. Used by the stability shift.
     *
     *   MAX_FPS → BALANCED_SMART → COOL_QUIET → BATTERY_SAVER (→ BATTERY_SAVER)
     *
     * Any goal not on this ladder (e.g. AUTO or the objective modes) is left unchanged —
     * adaptive only ever produces the four curated bands above, so this is exhaustive in
     * practice and a safe identity for the rest.
     */
    private fun GoalProfile.shiftedTowardStability(): GoalProfile = when (this) {
        GoalProfile.MAX_FPS -> GoalProfile.BALANCED_SMART
        GoalProfile.BALANCED_SMART -> GoalProfile.COOL_QUIET
        GoalProfile.COOL_QUIET -> GoalProfile.BATTERY_SAVER
        GoalProfile.BATTERY_SAVER -> GoalProfile.BATTERY_SAVER
        else -> this
    }
}
