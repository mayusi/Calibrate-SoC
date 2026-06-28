package io.github.mayusi.calibratesoc.data.autotdp.gpu

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.Direction
import io.github.mayusi.calibratesoc.data.autotdp.GoalParams
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptiveSetpoints
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.DdrBias
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuBand
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuOcTier
import org.junit.Test

/**
 * UNIT 2 (ADAPTIVE MODE) — the GPU band controller, mirroring the CPU band tests.
 *
 * Deterministic, device-free replay against the PURE [GpuBandController]. Covers the band
 * semantics (above high → loosen / below low → tighten / inside → hold), the fast-down /
 * slow-up asymmetry, the GPU FLOOR INVARIANT (the SAFETY LAW), load-blind HOLD, the
 * adaptive cadence hint, real-OPP snapping, and the effective-ceiling (OC) param.
 */
class GpuBandControllerTest {

    // Odin-style GPU devfreq OPP ladder (Hz), ascending. Stock ceiling = 1100 MHz.
    private val gpuOpps = listOf(
        160_000_000L, 305_000_000L, 414_000_000L, 525_000_000L, 587_000_000L,
        650_000_000L, 720_000_000L, 800_000_000L, 900_000_000L, 1_100_000_000L,
    )

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(844_000, 1_536_000, 2_803_000),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = 160_000_000L,
        gpuDevfreqCeilHz = 1_100_000_000L,
        gpuDevfreqStepsHz = gpuOpps,
        gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    // BALANCED-style adaptive setpoints: GPU band 63-85, soft die 88°C, floor fraction
    // 0.30 → floor target 330 MHz → first OPP ≥ 330 = 414 MHz (the floor-invariant OPP).
    private fun setpoints(
        bandLow: Int = 63,
        bandHigh: Int = 85,
        floorFraction: Float = 0.30f,
        softTempC: Int = 88,
        ocTier: GpuOcTier = GpuOcTier.OFF,
    ) = AdaptiveSetpoints(
        cpuGoal = GoalProfile.BALANCED_SMART,
        cpuGoalParams = GoalParams.DEFAULT,
        gpuBand = GpuBand(low = bandLow, high = bandHigh),
        gpuFloorFraction = floorFraction,
        gpuOcTier = ocTier,
        gpuSoftTempC = softTempC,
        ddrBias = DdrBias.NORMAL,
    )

    /** Calm thermal signals — never trips the FAST regime or warming cadence. */
    private val calmSignals = GpuSignals(smoothedDieTempC = 50, dTempSlopeCPerS = 0.0, coolingMaxState = 0)

    private val FLOOR_OPP = 414_000_000L
    private val CEIL_OPP = 1_100_000_000L

    private fun decide(
        gpuBusyPct: Int?,
        state: GpuControllerState,
        signals: GpuSignals = calmSignals,
        sp: AdaptiveSetpoints = setpoints(),
        effectiveCeilHz: Long? = caps.gpuDevfreqCeilHz,
    ) = GpuBandController.decide(
        gpuBusyPct = gpuBusyPct,
        signals = signals,
        setpoints = sp,
        state = state,
        caps = caps,
        effectiveCeilHz = effectiveCeilHz,
    )

    /** Drive the same busy% repeatedly through the controller, threading carried state. */
    private fun replay(
        busy: Int?,
        ticks: Int,
        start: GpuControllerState = GpuControllerState.INITIAL,
        signals: GpuSignals = calmSignals,
        sp: AdaptiveSetpoints = setpoints(),
        effectiveCeilHz: Long? = caps.gpuDevfreqCeilHz,
    ): Pair<GpuDecision, GpuControllerState> {
        var state = start
        var decision = decide(busy, state, signals, sp, effectiveCeilHz)
        repeat(ticks) {
            decision = decide(busy, state, signals, sp, effectiveCeilHz)
            state = decision.second
        }
        return decision
    }

    // ── BAND SEMANTICS ────────────────────────────────────────────────────────────

    @Test
    fun aboveHigh_loosens_oneStep_confirmGated() {
        // Busy 95% > high 85 → LOOSEN. Loosen confirms over 1 tick, so it acts on the
        // first confirming tick (mirrors the CPU controller's LOOSEN_CONFIRM_TICKS=1).
        // From stock the max defaults to the floor OPP (414 MHz); one OPP up = 525 MHz.
        val first = decide(95, GpuControllerState.INITIAL)
        assertThat(first.first.targetGpuDevfreqMaxHz).isEqualTo(525_000_000L)
        assertThat(first.second.lastActedDirection).isEqualTo(Direction.LOOSEN)
        // One notch only this tick.
        assertThat(first.second.gpuDevfreqMaxHz).isEqualTo(525_000_000L)
    }

    @Test
    fun belowLow_tightens_oneStep() {
        // Seed an operating point well above the floor so a tighten has room to move down.
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 800_000_000L, gpuEwma = 80.0)
        // Busy 20% < low 63 → TIGHTEN. Calm thermals → BAND regime → 2-tick confirm.
        var d = decide(20, seeded)            // confirm 1
        assertThat(d.first.targetGpuDevfreqMaxHz).isNull()
        d = decide(20, d.second)              // confirm 2 → acts, one OPP down (800 → 720)
        assertThat(d.first.targetGpuDevfreqMaxHz).isEqualTo(720_000_000L)
        assertThat(d.second.lastActedDirection).isEqualTo(Direction.TIGHTEN)
    }

    @Test
    fun insideBand_holds_noMove() {
        // Busy 74% is inside 63-85 → HOLD. No target emitted, ever, across many ticks.
        val d = replay(74, ticks = 6)
        assertThat(d.first.targetGpuDevfreqMaxHz).isNull()
        assertThat(d.first.targetGpuDevfreqMinHz).isNull()
        assertThat(d.first.targetGpuLevel).isNull()
        assertThat(d.second.currentDirection).isNull()
        assertThat(d.first.reason).contains("band")
    }

    // ── FAST-DOWN / SLOW-UP ───────────────────────────────────────────────────────

    @Test
    fun nearSoftTemp_tightensThisTick_noConfirm() {
        // GPU die at 86°C, soft 88 → within 3°C → FAST regime. Below-low busy → tighten
        // acts on the FIRST tick (0 confirm), no 2-tick wait.
        val hot = GpuSignals(smoothedDieTempC = 86, dTempSlopeCPerS = 0.0, coolingMaxState = 0)
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 800_000_000L, gpuEwma = 80.0)
        val d = decide(20, seeded, signals = hot)
        assertThat(d.first.targetGpuDevfreqMaxHz).isNotNull()
        assertThat(d.first.targetGpuDevfreqMaxHz!!).isLessThan(800_000_000L)
        assertThat(d.second.lastActedDirection).isEqualTo(Direction.TIGHTEN)
    }

    @Test
    fun dTempSlopeHigh_tightensThisTick_noConfirm() {
        val heating = GpuSignals(smoothedDieTempC = 60, dTempSlopeCPerS = 2.5, coolingMaxState = 0)
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 800_000_000L, gpuEwma = 80.0)
        val d = decide(20, seeded, signals = heating)
        assertThat(d.first.targetGpuDevfreqMaxHz!!).isLessThan(800_000_000L)
    }

    @Test
    fun coolingStatePositive_tightensThisTick_noConfirm() {
        val throttling = GpuSignals(smoothedDieTempC = 60, dTempSlopeCPerS = 0.0, coolingMaxState = 1)
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 800_000_000L, gpuEwma = 80.0)
        val d = decide(20, seeded, signals = throttling)
        assertThat(d.first.targetGpuDevfreqMaxHz!!).isLessThan(800_000_000L)
    }

    @Test
    fun fastRegime_stepsDownByUpToTwoOpps() {
        // FAST tighten may step DOWN up to 2 OPPs in one tick (fast-down). From 800 MHz
        // (idx 7), 2 OPPs down = 650 MHz (idx 5).
        val throttling = GpuSignals(smoothedDieTempC = 60, dTempSlopeCPerS = 0.0, coolingMaxState = 1)
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 800_000_000L, gpuEwma = 80.0)
        val d = decide(20, seeded, signals = throttling)
        assertThat(d.first.targetGpuDevfreqMaxHz).isEqualTo(650_000_000L)
    }

    @Test
    fun bandTighten_stepsDownByOneOppOnly() {
        // Calm BAND tighten steps DOWN exactly one OPP (800 → 720), never two.
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 800_000_000L, gpuEwma = 80.0)
        var d = decide(20, seeded)   // confirm 1
        d = decide(20, d.second)     // confirm 2 → acts one OPP
        assertThat(d.first.targetGpuDevfreqMaxHz).isEqualTo(720_000_000L)
    }

    // ── GPU FLOOR INVARIANT (THE SAFETY LAW) ──────────────────────────────────────

    @Test
    fun tighten_neverGoesBelowFloor_invariant() {
        // Drive hard, sustained tighten with FAST regime (fast-down) from the ceiling for
        // many ticks and assert the GPU max NEVER drops below the floor OPP (414 MHz).
        val throttling = GpuSignals(smoothedDieTempC = 90, dTempSlopeCPerS = 5.0, coolingMaxState = 1)
        var state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = CEIL_OPP, gpuEwma = 10.0)
        repeat(40) {
            val d = decide(0, state, signals = throttling) // busy 0 = maximal tighten pressure
            state = d.second
            val max = state.gpuDevfreqMaxHz
            if (max != null) {
                assertThat(max).isAtLeast(FLOOR_OPP)
            }
        }
        // It must have settled AT the floor (never below it), not collapsed to 160 MHz.
        assertThat(state.gpuDevfreqMaxHz).isEqualTo(FLOOR_OPP)
    }

    @Test
    fun tighten_clampsToFloor_whenStartingJustAboveIt() {
        // Operating point one OPP above the floor (525 MHz). A 2-OPP fast-down would land
        // at 305 MHz (below the 414 floor) — assert it clamps to the floor, not below.
        val throttling = GpuSignals(smoothedDieTempC = 90, dTempSlopeCPerS = 5.0, coolingMaxState = 1)
        val seeded = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 525_000_000L, gpuEwma = 10.0)
        val d = decide(0, seeded, signals = throttling)
        assertThat(d.first.targetGpuDevfreqMaxHz).isEqualTo(FLOOR_OPP)
    }

    @Test
    fun floorInvariant_neverBelowLowestStockOpp_evenWithTinyFraction() {
        // A pathologically tiny floor fraction must still never drive the GPU below the
        // lowest stock OPP (160 MHz). With fraction 0.01, the floor target is ~11 MHz,
        // but the LAW pins it to the lowest real OPP.
        val tinyFloor = setpoints(floorFraction = 0.01f)
        val throttling = GpuSignals(smoothedDieTempC = 90, dTempSlopeCPerS = 5.0, coolingMaxState = 1)
        var state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = CEIL_OPP, gpuEwma = 10.0)
        repeat(40) {
            val d = decide(0, state, signals = throttling, sp = tinyFloor)
            state = d.second
            val max = state.gpuDevfreqMaxHz
            if (max != null) assertThat(max).isAtLeast(160_000_000L)
        }
        assertThat(state.gpuDevfreqMaxHz).isAtLeast(160_000_000L)
    }

    // ── LOAD-BLIND SAFE ───────────────────────────────────────────────────────────

    @Test
    fun loadBlind_holds_neverOriginatesLoosen() {
        // No GPU read (null busy%) → HOLD. Even across many blind ticks no target is emitted
        // and no loosen is ever originated.
        var state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 525_000_000L)
        repeat(10) {
            val d = decide(null, state)
            assertThat(d.first.targetGpuDevfreqMaxHz).isNull()
            assertThat(d.first.targetGpuLevel).isNull()
            assertThat(d.second.currentDirection).isNull()
            state = d.second
        }
    }

    @Test
    fun loadBlind_doesNotAccumulateConfirmTowardTighten() {
        // A blind tick must not advance a tighten confirm; the episode is ended each time.
        val d = replay(null, ticks = 5)
        assertThat(d.second.confirmTicks).isEqualTo(0)
        assertThat(d.second.currentDirection).isNull()
    }

    // ── ADAPTIVE CADENCE ──────────────────────────────────────────────────────────

    @Test
    fun nextTickHint_is500_whenWarming() {
        // dTemp slope ≥ 1.0 → warming → 500 ms.
        val warming = GpuSignals(smoothedDieTempC = 60, dTempSlopeCPerS = 1.5, coolingMaxState = 0)
        val d = decide(74, GpuControllerState.INITIAL, signals = warming)
        assertThat(d.first.nextTickHintMs).isEqualTo(500)
    }

    @Test
    fun nextTickHint_is500_whenNearSoft() {
        // GPU die within 5°C of soft (88) → 84°C → warming cadence.
        val nearSoft = GpuSignals(smoothedDieTempC = 84, dTempSlopeCPerS = 0.0, coolingMaxState = 0)
        val d = decide(74, GpuControllerState.INITIAL, signals = nearSoft)
        assertThat(d.first.nextTickHintMs).isEqualTo(500)
    }

    @Test
    fun nextTickHint_is1000_whenCalm() {
        val d = decide(74, GpuControllerState.INITIAL, signals = calmSignals)
        assertThat(d.first.nextTickHintMs).isEqualTo(1000)
    }

    @Test
    fun nextTickHint_neverBelow500_floor() {
        // Even in the hottest regime the hint is floored at 500 ms (2 Hz battery safety).
        val hot = GpuSignals(smoothedDieTempC = 100, dTempSlopeCPerS = 9.0, coolingMaxState = 3)
        val d = decide(74, GpuControllerState.INITIAL, signals = hot)
        assertThat(d.first.nextTickHintMs!!).isAtLeast(500)
    }

    // ── REAL-OPP SNAPPING ─────────────────────────────────────────────────────────

    @Test
    fun loosenTargets_snapToRealOpps() {
        // Every emitted max must be an exact member of the device OPP table.
        var state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = FLOOR_OPP, gpuEwma = 90.0)
        repeat(20) {
            val d = decide(95, state)
            state = d.second
            d.first.targetGpuDevfreqMaxHz?.let { assertThat(gpuOpps).contains(it) }
            d.first.targetGpuDevfreqMinHz?.let { assertThat(gpuOpps).contains(it) }
        }
    }

    @Test
    fun devfreqWindow_minStrictlyBelowMax_andRealOpp() {
        val state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = FLOOR_OPP, gpuEwma = 90.0)
        var d = decide(95, state)        // confirm
        d = decide(95, d.second)          // act
        val min = d.first.targetGpuDevfreqMinHz
        val max = d.first.targetGpuDevfreqMaxHz
        assertThat(min).isNotNull()
        assertThat(max).isNotNull()
        assertThat(min!!).isLessThan(max!!)
        assertThat(gpuOpps).contains(min)
        assertThat(gpuOpps).contains(max)
    }

    // ── EFFECTIVE CEILING (OC) PARAM ──────────────────────────────────────────────

    @Test
    fun respectsStockCeiling_byDefault() {
        // Sustained loosen must never exceed the stock ceiling (1100 MHz) by default.
        var state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = 900_000_000L, gpuEwma = 95.0)
        repeat(20) {
            val d = decide(99, state)
            state = d.second
            state.gpuDevfreqMaxHz?.let { assertThat(it).isAtMost(CEIL_OPP) }
        }
        assertThat(state.gpuDevfreqMaxHz).isEqualTo(CEIL_OPP)
    }

    @Test
    fun governsUpToOcCeiling_whenEffectiveCeilingPassed() {
        // With an OC ceiling + OC OPPs in the table, the band may govern ABOVE the stock
        // ceiling, up to the OC ceiling (but not past it).
        val ocOpps = gpuOpps + listOf(1_250_000_000L)
        val ocCaps = caps.copy(gpuDevfreqStepsHz = ocOpps)
        var state = GpuControllerState.INITIAL.copy(gpuDevfreqMaxHz = CEIL_OPP, gpuEwma = 99.0)
        var last: Long? = null
        repeat(8) {
            val d = GpuBandController.decide(
                gpuBusyPct = 99,
                signals = calmSignals,
                setpoints = setpoints(ocTier = GpuOcTier.BEYOND_STOCK),
                state = state,
                caps = ocCaps,
                effectiveCeilHz = 1_250_000_000L,
            )
            state = d.second
            last = state.gpuDevfreqMaxHz
            last?.let { assertThat(it).isAtMost(1_250_000_000L) }
        }
        // It climbed past the stock ceiling to the OC OPP.
        assertThat(last).isEqualTo(1_250_000_000L)
    }

    // ── PWRLEVEL MIRROR (no devfreq table) ────────────────────────────────────────

    @Test
    fun pwrLevelMirror_usedWhenNoDevfreqTable() {
        // A device with pwrlevels but no devfreq OPP table governs on the pwrlevel floor.
        val coarseCaps = caps.copy(
            gpuDevfreqFloorHz = null,
            gpuDevfreqCeilHz = null,
            gpuDevfreqStepsHz = emptyList(),
        )
        // Below-low busy → tighten → raise level index (slower GPU), clamped to the floor level.
        val seeded = GpuControllerState.INITIAL.copy(gpuLevel = 0, gpuEwma = 80.0)
        var d = GpuBandController.decide(20, calmSignals, setpoints(), seeded, coarseCaps, null)
        d = GpuBandController.decide(20, calmSignals, setpoints(), d.second, coarseCaps, null)
        assertThat(d.first.targetGpuLevel).isNotNull()
        assertThat(d.first.targetGpuLevel!!).isGreaterThan(0)
    }

    // ── EWMA SMOOTHING ────────────────────────────────────────────────────────────

    @Test
    fun ewma_smoothsSpikes_singleSpikeDoesNotImmediatelyLeaveBand() {
        // Inside-band steady state, then ONE 99% spike. α=0.4 → smoothed stays in band,
        // so no loosen is originated from a single noisy sample.
        var state = decide(74, GpuControllerState.INITIAL).second
        repeat(4) { state = decide(74, state).second } // settle EWMA near 74
        val spike = decide(99, state)
        // 0.4*99 + 0.6*74 = 84 → still ≤ high (85) → HOLD, no actuation.
        assertThat(spike.first.targetGpuDevfreqMaxHz).isNull()
        assertThat(spike.second.gpuEwma!!).isLessThan(85.0)
    }
}
