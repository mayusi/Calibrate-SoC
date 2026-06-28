package io.github.mayusi.calibratesoc.data.autotdp.gpu

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.gpu.GpuOcThermalGuard.GpuOcGuardState
import org.junit.Test

/**
 * UNIT 3 (ADAPTIVE MODE) — proof tests for the §6.3 beyond-stock OC thermal guard.
 *
 * PURE JVM. Asserts the three escalating responses, the 8 °C-below-kill hard wall, and the
 * session latch (a latched-off guard can NEVER re-raise within the hot episode).
 */
class GpuOcThermalGuardTest {

    private val ceilHz = 1_100_000_000L                                 // stock ceiling
    private val opp1 = 1_200_000_000L
    private val opp2 = 1_300_000_000L                                   // top beyond-stock OPP
    private val steps = listOf(160_000_000L, 550_000_000L, ceilHz, opp1, opp2)
    private val softC = 88
    private val killC = 105                                             // global kill
    private val hardWallC = killC - GpuOcThermalGuard.OC_DISARM_MARGIN_C // 97

    /** Thin wrapper over [GpuOcThermalGuard.evaluate] fixing the device-shaped constants. */
    private fun decide(
        gpuTempC: Int?,
        slope: Double? = null,
        currentMaxHz: Long = opp2,
        state: GpuOcGuardState = GpuOcGuardState(),
    ) = GpuOcThermalGuard.evaluate(
        gpuTempC = gpuTempC,
        dTempSlopeCPerS = slope,
        currentMaxHz = currentMaxHz,
        ceilHz = ceilHz,
        steps = steps,
        gpuSoftTempC = softC,
        killC = killC,
        state = state,
    )

    // ─── Hold (no wall, no arm) ──────────────────────────────────────────────────────

    @Test
    fun belowSoft_holdsCurrentCap_noChange() {
        val d = decide(gpuTempC = softC - 5)
        assertThat(d.newMaxHz).isEqualTo(opp2)
        assertThat(d.steppedDown).isFalse()
        assertThat(d.disarmedToStock).isFalse()
        assertThat(d.state.ocLatchedOff).isFalse()
    }

    // ─── 1. SOFT back-off: step down exactly one OPP ─────────────────────────────────

    @Test
    fun atSoft_stepsDownExactlyOneOpp() {
        // currentMax = opp2 (top). One OPP down = opp1.
        val d = decide(gpuTempC = softC, currentMaxHz = opp2)
        assertThat(d.newMaxHz).isEqualTo(opp1)
        assertThat(d.steppedDown).isTrue()
        assertThat(d.disarmedToStock).isFalse()      // opp1 still above stock
        assertThat(d.state.ocLatchedOff).isFalse()   // soft NEVER latches
    }

    @Test
    fun aboveSoft_fromMiddleOpp_stepsDownOneMoreOpp() {
        // currentMax = opp1, one OPP down = ceil (stock). disarmedToStock becomes true.
        val d = decide(gpuTempC = softC + 2, currentMaxHz = opp1)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.steppedDown).isTrue()
        assertThat(d.disarmedToStock).isTrue()       // reached stock, but NOT latched
        assertThat(d.state.ocLatchedOff).isFalse()
    }

    @Test
    fun soft_neverStepsBelowStockCeiling() {
        // Already at stock — no beyond-stock headroom to shed → idempotent at ceil.
        val d = decide(gpuTempC = softC, currentMaxHz = ceilHz)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
    }

    @Test
    fun soft_withEmptySteps_fallsBackToStockCeiling() {
        val d = GpuOcThermalGuard.evaluate(
            gpuTempC = softC, dTempSlopeCPerS = null, currentMaxHz = opp2,
            ceilHz = ceilHz, steps = emptyList(), gpuSoftTempC = softC,
            killC = killC, state = GpuOcGuardState(),
        )
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.disarmedToStock).isTrue()
    }

    // ─── 2. HARD disarm at killC − 8: slam to stock + latch ──────────────────────────

    @Test
    fun atHardWall_disarmsToStock_andLatchesOff() {
        val d = decide(gpuTempC = hardWallC)          // 97 °C
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.disarmedToStock).isTrue()
        assertThat(d.steppedDown).isFalse()
        assertThat(d.state.ocLatchedOff).isTrue()     // LATCHED
    }

    @Test
    fun hardWall_isExactlyEightBelowKill() {
        // One degree below the wall → NOT a hard disarm (falls through to soft).
        val justBelow = decide(gpuTempC = hardWallC - 1) // 96 °C, also ≥ soft → soft back-off
        assertThat(justBelow.state.ocLatchedOff).isFalse()
        assertThat(justBelow.disarmedToStock).isFalse() // stepped one OPP, not slammed to stock
        // At the wall → hard disarm + latch.
        assertThat(decide(gpuTempC = hardWallC).state.ocLatchedOff).isTrue()
        assertThat(GpuOcThermalGuard.OC_DISARM_MARGIN_C).isEqualTo(8)
    }

    @Test
    fun hardDisarm_takesPriorityOverSoft_whenBothWouldFire() {
        // Temp is hot enough for both soft and hard — hard wins (latches), never just steps.
        val d = decide(gpuTempC = hardWallC + 3)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.state.ocLatchedOff).isTrue()
        assertThat(d.steppedDown).isFalse()
    }

    // ─── 3. dTemp slope arm: proactive disarm to stock (no latch) ────────────────────

    @Test
    fun fastHeatingSlope_disarmsToStock_withoutLatch() {
        val d = decide(gpuTempC = softC - 10, slope = GpuOcThermalGuard.OC_SLOPE_ARM)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.disarmedToStock).isTrue()
        assertThat(d.state.ocLatchedOff).isFalse()    // slope arm does NOT latch
    }

    @Test
    fun slopeBelowArm_doesNotDisarm() {
        val d = decide(gpuTempC = softC - 10, slope = GpuOcThermalGuard.OC_SLOPE_ARM - 0.5)
        assertThat(d.newMaxHz).isEqualTo(opp2)        // held
        assertThat(d.disarmedToStock).isFalse()
    }

    @Test
    fun nullSlope_disablesSlopeArm_only() {
        // Null slope must not disarm; temp below soft → hold.
        val d = decide(gpuTempC = softC - 10, slope = null)
        assertThat(d.newMaxHz).isEqualTo(opp2)
        assertThat(d.disarmedToStock).isFalse()
    }

    // ─── The latch: once off, NEVER re-raises this episode ───────────────────────────

    @Test
    fun latchedOff_pinsStock_evenWhenCool_andCannotReRaise() {
        val latched = GpuOcGuardState(ocLatchedOff = true)
        // Even stone-cold and asked to hold opp2, a latched guard pins stock.
        val d = decide(gpuTempC = 30, currentMaxHz = opp2, state = latched)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.disarmedToStock).isTrue()
        assertThat(d.state.ocLatchedOff).isTrue()     // stays latched
    }

    @Test
    fun latchedOff_pinsStock_evenWithNoTempSensor() {
        val latched = GpuOcGuardState(ocLatchedOff = true)
        val d = decide(gpuTempC = null, currentMaxHz = opp2, state = latched)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.state.ocLatchedOff).isTrue()
    }

    @Test
    fun latchSurvivesAcrossTicks_onceTripped() {
        // Tick 1: cross the hard wall → latch.
        val t1 = decide(gpuTempC = hardWallC)
        assertThat(t1.state.ocLatchedOff).isTrue()
        // Tick 2: device cooled below soft, coordinator tries to ride opp2 again — guard
        // refuses (latched), pins stock. This is the self-disarm-below-kill guarantee.
        val t2 = decide(gpuTempC = softC - 20, currentMaxHz = opp2, state = t1.state)
        assertThat(t2.newMaxHz).isEqualTo(ceilHz)
        assertThat(t2.state.ocLatchedOff).isTrue()
    }

    // ─── No temp sensor (null), not latched: makes no temp decision ──────────────────

    @Test
    fun nullTemp_notLatched_holdsCurrentCap() {
        val d = decide(gpuTempC = null, currentMaxHz = opp2)
        assertThat(d.newMaxHz).isEqualTo(opp2)
        assertThat(d.disarmedToStock).isFalse()
        assertThat(d.state.ocLatchedOff).isFalse()
    }

    @Test
    fun nullTemp_butFastSlope_stillSlopeArms() {
        // Slope arm does not need a temperature reading.
        val d = decide(gpuTempC = null, slope = GpuOcThermalGuard.OC_SLOPE_ARM + 1.0)
        assertThat(d.newMaxHz).isEqualTo(ceilHz)
        assertThat(d.disarmedToStock).isTrue()
        assertThat(d.state.ocLatchedOff).isFalse()
    }
}
