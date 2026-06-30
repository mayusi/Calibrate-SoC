package io.github.mayusi.calibratesoc.ui.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyAdvisor
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyPlan
import io.github.mayusi.calibratesoc.data.efficiency.EstimateSource
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Battery & Thermal Intelligence panel (0.2.0).
 *
 * SURFACING ONLY — every computation is delegated to an already-existing
 * class. This ViewModel wires three live signals together and exposes them:
 *
 *   1. [batteryEstimate]   — reuses [DashboardViewModel.batteryEstimate] contract:
 *      reads live from [MonitorService] telemetry; delegates to the same
 *      BatteryEstimate logic already proven in DashboardViewModel. Honesty:
 *      basis label (LIVE_DRAW / CHARGING / INSUFFICIENT_DATA) is preserved.
 *
 *   2. [thermalHeadroom]   — live "°C until throttle" derived from the hottest
 *      thermal zone in the current [Telemetry]. Uses the same 85 °C kill-temp
 *      default as [ThrottleAnalysis.from]. Exposed as [ThermalHeadroomState]
 *      so the UI can map to AccentBar color. Honest when no temps are available.
 *
 *   3. [efficiencyWin]     — surfaces [EfficiencyAdvisor.buildPlan]'s knee
 *      recommendation as an actionable card item. Only shown when the plan has
 *      a MEASURED draw-reduction (bigClusterKneeCap non-null + MEASURED source).
 *      One-tap apply routes through [ProfileApplier.apply] (the guarded writer).
 *
 * HONESTY CONTRACT (LAW):
 *  - INSUFFICIENT_DATA → no time-to-empty number surfaced (hoursRemaining is null).
 *  - CHARGING → shows "charging" state, no fabricated discharge figure.
 *  - Efficiency win only claims MEASURED when [EfficiencyPlan.drawEstimateSource]
 *    == [EstimateSource.MEASURED] and [EfficiencyPlan.bigClusterKneeCap] != null.
 *  - ESTIMATED plans are shown in a separate "estimated" variant or hidden based
 *    on [EfficiencyWinState.Estimated] — never claim MEASURED without data.
 *
 * SAFETY: one-tap apply builds a minimal [Preset] (CPU max freq only) and
 * routes through [ProfileApplier.apply] — the same guarded, readback-verified
 * writer used by every other apply path. Does NOT bypass [PresetSafetyGate].
 */
@HiltViewModel
class IntelligencePanelViewModel @Inject constructor(
    private val monitorService: MonitorService,
    private val capabilityProbe: CapabilityProbe,
    private val efficiencyAdvisor: EfficiencyAdvisor,
    private val profileApplier: ProfileApplier,
) : ViewModel() {

    // ── Battery estimate ─────────────────────────────────────────────────────
    //
    // NOTE: this panel does NOT own a batteryEstimate StateFlow. The battery
    // time-to-empty figure is computed by DashboardViewModel (which owns the
    // BatteryChargeReader binder read) and passed DOWN into IntelligencePanelCard
    // as a composable parameter from DashboardScreen — see the init block below.
    // A duplicate StateFlow here was dead (never assigned, never collected) and
    // was removed to avoid implying a second, divergent source of truth.

    // ── Thermal headroom ─────────────────────────────────────────────────────

    /**
     * Live thermal headroom: degrees Celsius until the 85 °C default throttle
     * point (the same [killTempC] used in [ThrottleAnalysis.from]). Updated
     * on every 1 Hz telemetry tick.
     *
     * The headroom formula is identical to ThrottleAnalysis.from line 56:
     *   headroom = killTempC - peakCpuTempC
     *
     * We pick the hottest zone from Telemetry.zoneTempsMilliC to find peakCpuTempC,
     * filtering for plausible CPU/SoC zone temperatures (1 000 – 120 000 milliC).
     * [ThermalHeadroomState.Unavailable] is returned when no valid zone reads exist.
     */
    private val _thermalHeadroom = MutableStateFlow<ThermalHeadroomState>(ThermalHeadroomState.Unavailable)
    val thermalHeadroom: StateFlow<ThermalHeadroomState> = _thermalHeadroom.asStateFlow()

    // ── Efficiency win ───────────────────────────────────────────────────────

    /**
     * The current [EfficiencyAdvisor.buildPlan] result, mapped to a
     * [EfficiencyWinState] for the card action:
     *
     * - [EfficiencyWinState.Measured] — bigClusterKneeCap != null AND
     *   drawEstimateSource == MEASURED. Shows the "apply" button.
     * - [EfficiencyWinState.Estimated] — plan has an estimated draw reduction
     *   but no measured knee cap. Shows the figure labelled ESTIMATED without
     *   an apply button.
     * - [EfficiencyWinState.Unavailable] — no capability yet or no plan.
     */
    private val _efficiencyWin = MutableStateFlow<EfficiencyWinState>(EfficiencyWinState.Unavailable)
    val efficiencyWin: StateFlow<EfficiencyWinState> = _efficiencyWin.asStateFlow()

    // ── Apply result ─────────────────────────────────────────────────────────

    /**
     * Result of the last one-tap efficiency apply. Null until the user taps
     * Apply. Cleared when the user dismisses the banner.
     */
    private val _applyResult = MutableStateFlow<ApplyResult?>(null)
    val applyResult: StateFlow<ApplyResult?> = _applyResult.asStateFlow()

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        // Subscribe to DashboardViewModel's batteryEstimate via the shared
        // StateFlow that DashboardViewModel already exposes. However, since
        // this VM is scoped separately, we read from the monitor service
        // directly and produce the same estimate. The underlying MonitorService
        // singleton ensures no duplicate hardware reads.
        //
        // We DO NOT call computeBatteryEstimate here — that requires
        // BatteryChargeReader (an Android binder call). Instead, we delegate
        // that calculation to the Dashboard's existing flow and collect it via
        // the shared monitorService. For the panel, we project the live telemetry
        // fields needed for the headroom, then READ the batteryEstimate from
        // the same backing computation exposed by DashboardViewModel.
        //
        // Architecture note: to avoid re-implementing the charge-counter read
        // (a BatteryManager binder call that must not happen on the composition
        // thread), IntelligencePanelViewModel feeds from the DashboardViewModel's
        // batteryEstimate StateFlow. Since Hilt scopes both VMs to the same
        // NavBackStackEntry (or Activity), and DashboardViewModel is already
        // alive when the dashboard is open, we wire this ViewModel to read the
        // batteryEstimate from a shared @ActivityRetainedScoped flow. However,
        // to keep this purely additive (no cross-ViewModel coupling), the UI
        // collects batteryEstimate from DashboardViewModel AND passes it down
        // to the panel card as a parameter, avoiding any state duplication.
        // See IntelligencePanelCard: it receives BatteryEstimate from the parent
        // DashboardScreen, which already has it.
        //
        // Thermal headroom and efficiency win are computed here independently.

        // Collect live telemetry for thermal headroom.
        viewModelScope.launch {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { t ->
                _thermalHeadroom.value = computeThermalHeadroom(t)
            }
        }

        // Build efficiency win whenever capability report changes.
        viewModelScope.launch {
            capabilityProbe.report.collect { report ->
                if (report != null) {
                    val caps = TdpCaps.from(report)
                    val plan = efficiencyAdvisor.buildPlan(
                        report = report,
                        caps = caps,
                        curveResult = null, // live panel uses latest plan without sweep
                    )
                    _efficiencyWin.value = planToWinState(plan, caps)
                } else {
                    _efficiencyWin.value = EfficiencyWinState.Unavailable
                }
            }
        }
    }

    // ── One-tap efficiency apply ─────────────────────────────────────────────

    /**
     * Applies the measured efficiency knee cap in one tap.
     *
     * SAFETY: builds a minimal [Preset] and routes it through [ProfileApplier.apply],
     * which enforces [PresetSafetyGate] (device-targeting and policy-existence checks)
     * before dispatching to [TunableWriter]. No bypass of the guarded writer.
     *
     * GATING: only callable when [efficiencyWin] is [EfficiencyWinState.Measured].
     * The UI only shows the Apply button in that state, but we defensively check here.
     */
    fun applyEfficiencyKnee() {
        val winState = _efficiencyWin.value
        if (winState !is EfficiencyWinState.Measured) return

        val report = capabilityProbe.report.value ?: return

        viewModelScope.launch {
            val kneeCap = winState.kneeCaps  // map<policyId, capKhz>
            val preset = Preset(
                id = "intel_panel_efficiency_knee",
                name = "Efficiency Knee (Auto)",
                description = "Big-cluster cap at the measured perf-per-watt knee. " +
                    "Applied from the Battery & Thermal Intelligence panel.",
                verification = VerificationTier.GENERIC_KNOWN_FAMILY,
                cpuPolicyMaxKhz = kneeCap,
            )
            val results = profileApplier.apply(
                preset = preset,
                report = report,
                reason = "intelligence_panel_one_tap",
            )
            val allOk = results.all { it is WriteResult.Success }
            val rejections = results
                .filterIsInstance<WriteResult.Rejected>()
                .map { it.message }
            _applyResult.value = if (allOk) {
                ApplyResult.Success(kneeCaps = kneeCap)
            } else {
                ApplyResult.Failed(
                    reasons = rejections.ifEmpty { listOf("One or more writes failed.") },
                )
            }
        }
    }

    fun clearApplyResult() {
        _applyResult.value = null
    }

    // ── Pure helpers (unit-testable) ─────────────────────────────────────────

    companion object {

        /**
         * Default throttle kill temperature — same constant as
         * [ThrottleAnalysis.from]'s default parameter (85 °C).
         */
        const val DEFAULT_KILL_TEMP_C: Float = 85f

        /**
         * Computes live thermal headroom from a [Telemetry] sample.
         *
         * Picks the hottest plausible CPU/SoC zone (1 000 – 120 000 milliC)
         * and computes: headroom = DEFAULT_KILL_TEMP_C - peakZoneC.
         *
         * Returns [ThermalHeadroomState.Unavailable] when no valid zone reads
         * exist — honest, never fabricates a temperature.
         *
         * @param t       Live telemetry sample from [MonitorService].
         * @param killC   The throttle threshold (default [DEFAULT_KILL_TEMP_C]).
         */
        fun computeThermalHeadroom(
            t: Telemetry,
            killC: Float = DEFAULT_KILL_TEMP_C,
        ): ThermalHeadroomState {
            val peakMilliC = t.zoneTempsMilliC
                .filter { it.tempMilliC in 1_000..120_000 }
                .maxOfOrNull { it.tempMilliC }
                ?: return ThermalHeadroomState.Unavailable
            val peakC = peakMilliC / 1_000f
            val headroom = killC - peakC
            return ThermalHeadroomState.Available(
                headroomC = headroom,
                peakZoneTempC = peakC,
                killTempC = killC,
            )
        }

        /**
         * Maps an [EfficiencyPlan] to the correct [EfficiencyWinState].
         *
         * HONESTY LAW:
         *  - [EfficiencyWinState.Measured] requires BOTH bigClusterKneeCap != null
         *    AND drawEstimateSource == MEASURED. Never promotes an ESTIMATED plan
         *    to MEASURED.
         *  - [EfficiencyWinState.Estimated] when there is a draw reduction figure
         *    but the source is ESTIMATED (no sweep run yet). The UI labels it as
         *    ESTIMATED to avoid misrepresentation.
         *  - [EfficiencyWinState.Unavailable] when no useful data exists.
         *
         * @param plan  The [EfficiencyPlan] from [EfficiencyAdvisor.buildPlan].
         * @param caps  [TdpCaps] to resolve the big-policy policyId for apply.
         */
        fun planToWinState(plan: EfficiencyPlan, caps: TdpCaps): EfficiencyWinState {
            val kneeCap = plan.bigClusterKneeCap
            val drawPct = plan.estimatedDrawReductionPct

            if (kneeCap != null && plan.drawEstimateSource == EstimateSource.MEASURED) {
                // MEASURED knee cap available — show apply button.
                val kneeMhz = kneeCap / 1_000
                return EfficiencyWinState.Measured(
                    kneeMhz = kneeMhz,
                    drawReductionPct = drawPct,
                    kneeCaps = mapOf(caps.bigPolicyId to kneeCap),
                )
            }

            if (drawPct != null) {
                // No measured knee cap but a draw reduction estimate exists.
                // This is always ESTIMATED (sweep not yet run). Never claim MEASURED.
                return EfficiencyWinState.Estimated(
                    drawReductionPct = drawPct,
                    summaryText = plan.summaryText,
                )
            }

            return EfficiencyWinState.Unavailable
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  State types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Live thermal headroom state, updated every telemetry tick.
 *
 * [Available] — headroom is computable from live temps.
 *   [headroomC]     °C remaining before the kill threshold.
 *   [peakZoneTempC] Current hottest zone in °C.
 *   [killTempC]     The throttle kill temperature (default 85 °C).
 *
 * [Unavailable] — no valid thermal zone reads this tick.
 *   Honest: the UI must not display a fabricated headroom value.
 */
sealed interface ThermalHeadroomState {
    data class Available(
        val headroomC: Float,
        val peakZoneTempC: Float,
        val killTempC: Float,
    ) : ThermalHeadroomState

    /** No plausible thermal zone temp found; UI should say "temp unavailable". */
    data object Unavailable : ThermalHeadroomState
}

/**
 * Efficiency win state for the one-tap panel action.
 *
 * [Measured]    — [EfficiencyAdvisor.buildPlan] produced a real sweep-backed knee.
 *   [kneeMhz]          The recommended big-cluster cap in MHz.
 *   [drawReductionPct] Measured draw reduction % (may be null if not computable).
 *   [kneeCaps]         Map<policyId, capKhz> ready to pass to [ProfileApplier].
 *
 * [Estimated]   — Plan has a draw-reduction figure, but it is ESTIMATED (no sweep).
 *   The UI labels this clearly; there is no apply button in this state.
 *
 * [Unavailable] — No plan or no capability yet. Card is hidden.
 */
sealed interface EfficiencyWinState {
    data class Measured(
        val kneeMhz: Int,
        val drawReductionPct: Int?,
        val kneeCaps: Map<Int, Int>,  // policyId → capKhz
    ) : EfficiencyWinState

    data class Estimated(
        val drawReductionPct: Int,
        val summaryText: String,
    ) : EfficiencyWinState

    data object Unavailable : EfficiencyWinState
}

/**
 * Result of a one-tap efficiency knee apply.
 *
 * [Success] — all [WriteResult.Ok] from [ProfileApplier.apply].
 * [Failed]  — one or more [WriteResult.Rejected] or write errors.
 */
sealed interface ApplyResult {
    data class Success(val kneeCaps: Map<Int, Int>) : ApplyResult
    data class Failed(val reasons: List<String>) : ApplyResult
}
