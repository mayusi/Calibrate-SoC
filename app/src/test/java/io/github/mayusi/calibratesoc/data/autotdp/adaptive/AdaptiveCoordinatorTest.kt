package io.github.mayusi.calibratesoc.data.autotdp.adaptive

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.AdaptiveRunConfig
import io.github.mayusi.calibratesoc.data.autotdp.ControllerState
import io.github.mayusi.calibratesoc.data.autotdp.GoalParams
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.HoldReason
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.autotdp.TdpDecision
import io.github.mayusi.calibratesoc.data.autotdp.TdpState
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuControllerState
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuDecision
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuOcVerdict
import org.junit.Test

/**
 * UNIT 5 (ADAPTIVE MODE) — the COORDINATOR composition + gating tests.
 *
 * Device-free, deterministic. Verifies the four LAWS the coordinator must hold:
 *  1. [compose] merges the CPU decision's fields AND the GPU decision's fields into ONE
 *     [TdpState] (both present).
 *  2. Beyond-stock OC only arms when tier == BEYOND_STOCK && consent && verdict == Accepted;
 *     otherwise the effective ceiling clamps to the STOCK ceiling.
 *  3. The OC thermal guard, when it disarms to stock, overrides the GPU max DOWNWARD in the
 *     composed flow (heat always wins).
 *  4. The serializable [AdaptiveRunConfig] round-trips back into the pure coordinator config.
 */
class AdaptiveCoordinatorTest {

    // Odin-style GPU devfreq OPP ladder (Hz), ascending. Stock ceiling = 1100 MHz; the table
    // advertises one beyond-stock OPP (1300 MHz) the probe could discover.
    private val gpuOpps = listOf(
        160_000_000L, 305_000_000L, 414_000_000L, 525_000_000L, 587_000_000L,
        650_000_000L, 720_000_000L, 800_000_000L, 900_000_000L, 1_100_000_000L,
        1_300_000_000L,
    )

    private val stockCeil = 1_100_000_000L
    private val ocReached = 1_300_000_000L

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(844_000, 1_536_000, 2_803_000),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = 160_000_000L,
        gpuDevfreqCeilHz = stockCeil,
        gpuDevfreqStepsHz = gpuOpps,
        gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    private fun setpoints(
        ocTier: GpuOcTier = GpuOcTier.OFF,
        softTempC: Int = 88,
        ddr: DdrBias = DdrBias.NORMAL,
    ) = AdaptiveSetpoints(
        cpuGoal = GoalProfile.BALANCED_SMART,
        cpuGoalParams = GoalParams.DEFAULT,
        gpuBand = GpuBand(low = 63, high = 85),
        gpuFloorFraction = 0.30f,
        gpuOcTier = ocTier,
        gpuSoftTempC = softTempC,
        ddrBias = ddr,
    )

    /** A CPU decision carrying a big-cluster cap + uclamp hint (the "CPU side" of the merge). */
    private fun cpuDecision(
        capKhz: Int? = 1_536_000,
        uclamp: Int? = 512,
        hint: Int? = 1000,
    ): TdpDecision = TdpDecision(
        target = TdpState(bigClusterCapKhz = capKhz, uclampTopAppMin = uclamp),
        reason = "CPU band hold",
        holdReason = HoldReason.IDLE_HOLDING,
        controllerState = ControllerState.INITIAL,
        resolvedGoal = GoalProfile.BALANCED_SMART,
        nextTickHintMs = hint,
    )

    /** A GPU decision carrying a devfreq min/max window (the "GPU side" of the merge). */
    private fun gpuDecision(
        minHz: Long? = 720_000_000L,
        maxHz: Long? = 900_000_000L,
        hint: Int? = 500,
    ): GpuDecision = GpuDecision(
        targetGpuLevel = null,
        targetGpuDevfreqMinHz = minHz,
        targetGpuDevfreqMaxHz = maxHz,
        nextTickHintMs = hint,
        reason = "loosen GPU → 900 MHz",
    )

    // ── 1. compose() merges CPU + GPU into ONE TdpState ──────────────────────────────────

    @Test
    fun `compose merges CPU cap and GPU devfreq into one state`() {
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(capKhz = 1_536_000, uclamp = 512),
            gpuDecision = gpuDecision(minHz = 720_000_000L, maxHz = 900_000_000L),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = null,
            setpoints = setpoints(),
        )
        // CPU fields survived.
        assertThat(composed.target.bigClusterCapKhz).isEqualTo(1_536_000)
        assertThat(composed.target.uclampTopAppMin).isEqualTo(512)
        // GPU fields folded in.
        assertThat(composed.target.gpuDevfreqMinHz).isEqualTo(720_000_000L)
        assertThat(composed.target.gpuDevfreqMaxHz).isEqualTo(900_000_000L)
        // Cadence = min(cpu 1000, gpu 500), floored at 500.
        assertThat(composed.nextTickHintMs).isEqualTo(500)
        // Reason carries both sides.
        assertThat(composed.reason).contains("CPU:")
        assertThat(composed.reason).contains("GPU:")
    }

    @Test
    fun `compose floors cadence at MIN_TICK_MS`() {
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(hint = 200),
            gpuDecision = gpuDecision(hint = 100),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = null,
            setpoints = setpoints(),
        )
        assertThat(composed.nextTickHintMs).isEqualTo(AdaptiveCoordinator.MIN_TICK_MS)
    }

    @Test
    fun `compose preserves CPU GPU fields on a GPU HOLD tick (null targets dont clear)`() {
        // A GPU HOLD returns all-null targets — the merge must NOT clear the CPU's state.
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(capKhz = 844_000),
            gpuDecision = GpuDecision(null, null, null, 1000, "holding (GPU in band)"),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = null,
            setpoints = setpoints(),
        )
        assertThat(composed.target.bigClusterCapKhz).isEqualTo(844_000)
        assertThat(composed.target.gpuDevfreqMaxHz).isNull()
    }

    @Test
    fun `compose surfaces DDR bias in the reason but applies no DDR actuator`() {
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(),
            gpuDecision = gpuDecision(),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = null,
            setpoints = setpoints(ddr = DdrBias.HIGH),
        )
        assertThat(composed.reason).contains("DDR bias: high")
        // TdpState has no DDR field; nothing about the merge invents one.
    }

    // ── 2. Beyond-stock gating: tier + consent + Accepted ────────────────────────────────

    private fun config(
        tier: GpuOcTier,
        consent: Boolean,
        verdict: GpuOcVerdict?,
    ) = AdaptiveCoordinator.AdaptiveConfig(
        intent = AdaptiveIntent(0.9f, 0.0f, 0.05f, 0.05f), // perf-lean (so policy WOULD allow OC)
        gpuOcTier = tier,
        beyondStockConsent = consent,
        probeVerdict = verdict,
    )

    @Test
    fun `effective ceiling is OC reached only when armed`() {
        val armed = config(GpuOcTier.BEYOND_STOCK, consent = true, verdict = GpuOcVerdict.Accepted(ocReached))
        val sp = AdaptiveCoordinator.resolveSetpoints(armed, caps)
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.BEYOND_STOCK)
        assertThat(AdaptiveCoordinator.beyondStockArmed(armed, sp)).isTrue()
        assertThat(AdaptiveCoordinator.effectiveCeilHz(armed, sp, caps)).isEqualTo(ocReached)
    }

    @Test
    fun `effective ceiling clamps to stock without consent`() {
        val noConsent = config(GpuOcTier.BEYOND_STOCK, consent = false, verdict = GpuOcVerdict.Accepted(ocReached))
        val sp = AdaptiveCoordinator.resolveSetpoints(noConsent, caps)
        // Policy can't reach BEYOND_STOCK without consent.
        assertThat(sp.gpuOcTier).isNotEqualTo(GpuOcTier.BEYOND_STOCK)
        assertThat(AdaptiveCoordinator.beyondStockArmed(noConsent, sp)).isFalse()
        assertThat(AdaptiveCoordinator.effectiveCeilHz(noConsent, sp, caps)).isEqualTo(stockCeil)
    }

    @Test
    fun `effective ceiling clamps to stock when verdict not Accepted`() {
        val rejected = config(GpuOcTier.BEYOND_STOCK, consent = true, verdict = GpuOcVerdict.Rejected(stockCeil))
        val sp = AdaptiveCoordinator.resolveSetpoints(rejected, caps)
        assertThat(sp.gpuOcTier).isNotEqualTo(GpuOcTier.BEYOND_STOCK)
        assertThat(AdaptiveCoordinator.beyondStockArmed(rejected, sp)).isFalse()
        assertThat(AdaptiveCoordinator.effectiveCeilHz(rejected, sp, caps)).isEqualTo(stockCeil)
    }

    @Test
    fun `effective ceiling clamps to stock when never probed`() {
        val unprobed = config(GpuOcTier.BEYOND_STOCK, consent = true, verdict = null)
        val sp = AdaptiveCoordinator.resolveSetpoints(unprobed, caps)
        assertThat(AdaptiveCoordinator.beyondStockArmed(unprobed, sp)).isFalse()
        assertThat(AdaptiveCoordinator.effectiveCeilHz(unprobed, sp, caps)).isEqualTo(stockCeil)
    }

    @Test
    fun `effective ceiling never exceeds the top advertised OPP`() {
        // Accepted reports an absurd reached clock above any OPP — the ceiling is capped to
        // the top advertised OPP, never a frequency the device can't produce.
        val armed = config(
            GpuOcTier.BEYOND_STOCK,
            consent = true,
            verdict = GpuOcVerdict.Accepted(9_999_000_000L),
        )
        val sp = AdaptiveCoordinator.resolveSetpoints(armed, caps)
        assertThat(AdaptiveCoordinator.effectiveCeilHz(armed, sp, caps)).isEqualTo(ocReached)
    }

    // ── 3. OC thermal guard disarm overrides the GPU max DOWNWARD ─────────────────────────

    @Test
    fun `compose clamps GPU max down when the OC guard disarms to stock`() {
        // Band proposed a beyond-stock max (1300 MHz); the guard disarmed to the stock ceiling.
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(),
            gpuDecision = gpuDecision(minHz = 900_000_000L, maxHz = ocReached),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = AdaptiveCoordinator.GpuOcGuardOutcome(newMaxHz = stockCeil, disarmedToStock = true),
            setpoints = setpoints(ocTier = GpuOcTier.BEYOND_STOCK),
        )
        // The composed GPU max is clamped to stock (heat wins), never the beyond-stock value.
        assertThat(composed.target.gpuDevfreqMaxHz).isEqualTo(stockCeil)
        assertThat(composed.reason).contains("OC guard: disarmed to stock")
    }

    @Test
    fun `compose asserts the guard stock cap even on a GPU HOLD tick`() {
        // Band holding the max (null), but the guard disarmed to stock — the downward cap must
        // still be asserted (a hold can't override heat).
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(),
            gpuDecision = GpuDecision(null, null, null, 1000, "holding"),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = AdaptiveCoordinator.GpuOcGuardOutcome(newMaxHz = stockCeil, disarmedToStock = true),
            setpoints = setpoints(ocTier = GpuOcTier.BEYOND_STOCK),
        )
        assertThat(composed.target.gpuDevfreqMaxHz).isEqualTo(stockCeil)
    }

    @Test
    fun `compose honours a clear OC guard (no downward clamp when not disarmed)`() {
        // Guard clear (holding the beyond-stock cap) — the band's max stands.
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(),
            gpuDecision = gpuDecision(minHz = 900_000_000L, maxHz = ocReached),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = AdaptiveCoordinator.GpuOcGuardOutcome(newMaxHz = ocReached, disarmedToStock = false),
            setpoints = setpoints(ocTier = GpuOcTier.BEYOND_STOCK),
        )
        assertThat(composed.target.gpuDevfreqMaxHz).isEqualTo(ocReached)
    }

    @Test
    fun `compose drops the GPU min when the guard pulls max below it`() {
        // Band min (900) would invert against a guard-clamped max (stock 1100 is fine, but
        // use a lower guard cap to force the invert): guard clamps max to 800, below the band's
        // 900 min → the min is dropped to null rather than emitting min > max.
        val composed = AdaptiveCoordinator.compose(
            cpuDecision = cpuDecision(),
            gpuDecision = gpuDecision(minHz = 900_000_000L, maxHz = ocReached),
            gpuState = GpuControllerState.INITIAL,
            ocGuard = AdaptiveCoordinator.GpuOcGuardOutcome(newMaxHz = 800_000_000L, disarmedToStock = true),
            setpoints = setpoints(ocTier = GpuOcTier.BEYOND_STOCK),
        )
        assertThat(composed.target.gpuDevfreqMaxHz).isEqualTo(800_000_000L)
        assertThat(composed.target.gpuDevfreqMinHz).isNull()
    }

    // ── 4. Run-config round-trip + verdict parsing ───────────────────────────────────────

    @Test
    fun `fromRunConfig rebuilds the pure config and parses an Accepted verdict`() {
        val run = AdaptiveRunConfig(
            wPerformance = 0.7f,
            wBattery = 0.1f,
            wStability = 0.1f,
            wThermalHeadroom = 0.1f,
            gpuOcTierOrdinal = GpuOcTier.BEYOND_STOCK.ordinal,
            beyondStockConsent = true,
            probeVerdictRecord = "Accepted:1300000000",
        )
        val cfg = AdaptiveCoordinator.fromRunConfig(run)
        assertThat(cfg.intent.wPerformance).isEqualTo(0.7f)
        assertThat(cfg.gpuOcTier).isEqualTo(GpuOcTier.BEYOND_STOCK)
        assertThat(cfg.beyondStockConsent).isTrue()
        assertThat(cfg.probeVerdict).isEqualTo(GpuOcVerdict.Accepted(1_300_000_000L))
    }

    @Test
    fun `parseVerdict handles all forms and a fingerprint prefix`() {
        assertThat(AdaptiveCoordinator.parseVerdict("Unsupported")).isEqualTo(GpuOcVerdict.Unsupported)
        assertThat(AdaptiveCoordinator.parseVerdict("Ineffective")).isEqualTo(GpuOcVerdict.Ineffective)
        assertThat(AdaptiveCoordinator.parseVerdict("Rejected:1100000000"))
            .isEqualTo(GpuOcVerdict.Rejected(1_100_000_000L))
        assertThat(AdaptiveCoordinator.parseVerdict("fp-abc123|Accepted:1300000000"))
            .isEqualTo(GpuOcVerdict.Accepted(1_300_000_000L))
        assertThat(AdaptiveCoordinator.parseVerdict(null)).isNull()
        assertThat(AdaptiveCoordinator.parseVerdict("garbage")).isNull()
    }
}
