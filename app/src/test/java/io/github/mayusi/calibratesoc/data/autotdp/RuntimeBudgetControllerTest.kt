package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * UNIT 4 — RuntimeBudgetController: the PURE outer-setpoint math for the three new
 * objective goal modes (TARGET_RUNTIME budget, TARGET_TEMP_CEILING guard,
 * TARGET_FPS_FLOOR anti-tighten, and the shared never-loosen-above clamp).
 *
 * SAFETY contracts asserted here (every outer setpoint can only ever TIGHTEN the cap):
 *  - the runtime ceiling never lands below the 40% hard floor (CapFloor-snapped);
 *  - the never-loosen clamp only ever LOWERS the cap toward the ceiling, never raises;
 *  - the temp guard tightens near the ceiling, relaxes when cool, holds in the dead-band,
 *    and never tightens below the 40% floor;
 *  - the fps block holds the knee (blocks a tighten) only on a REAL sub-floor reading,
 *    and is a complete no-op when no real FPS source exists.
 *
 * HONESTY contracts: the runtime projection carries the PowerModel MEASURED/ESTIMATED
 * confidence; an unreachable target is reported honestly (achievable = false) while still
 * returning the lowest SAFE cap.
 */
class RuntimeBudgetControllerTest {

    // Odin-style big OPP ladder (kHz), ascending. 40% of 2_803_000 ≈ 1_121_200 →
    // first OPP ≥ that = 1_171_000 (index 2) = the hard cap floor.
    private val opps = listOf(
        499_000, 844_000, 1_171_000, 1_536_000,
        1_920_000, 2_323_000, 2_707_000, 2_803_000,
    )
    private val hardFloorKhz = opps.first { it >= (opps.last() * 0.40).toInt() }

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_RUNTIME — the 60s budget loop
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `runtime budget picks the largest OPP whose modelled draw fits the budget`() {
        // Linear reference: top OPP (2_803_000) draws 4000 mW. budgetW = 20Wh / 5h = 4W →
        // budgetMw = 4000. The top OPP draws exactly 4000 → fits → largest fitting = top.
        val b = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = 20.0,
            targetHours = 5.0,
            oppStepsKhz = opps,
            powerModelFit = null,
            referenceCapKhz = opps.last(),
            referenceDrawMilliW = 4_000L,
        )
        assertThat(b.capCeilingKhz).isNotNull()
        // A tighter target should select a LOWER cap than a looser one.
        val tighter = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = 20.0,
            targetHours = 10.0, // half the budget per hour → must cap lower
            oppStepsKhz = opps,
            referenceCapKhz = opps.last(),
            referenceDrawMilliW = 4_000L,
        )
        assertThat(tighter.capCeilingKhz!!).isAtMost(b.capCeilingKhz!!)
    }

    @Test
    fun `runtime ceiling never drops below the 40 percent hard floor even for an absurd target`() {
        // 50h on 5Wh is impossible — even the lowest OPP can't hit it. The ceiling must
        // still be at/above the 40% hard floor (never floors the cluster).
        val b = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = 5.0,
            targetHours = 50.0,
            oppStepsKhz = opps,
            referenceCapKhz = opps.last(),
            referenceDrawMilliW = 4_000L,
        )
        assertThat(b.achievable).isFalse() // honest: not reachable
        assertThat(b.capCeilingKhz).isNotNull()
        assertThat(b.capCeilingKhz!!).isAtLeast(hardFloorKhz)
        assertThat(b.note.lowercase()).contains("not reachable")
    }

    @Test
    fun `runtime budget inherits MEASURED confidence from a fitted model`() {
        // 3+ points on draw ∝ f^2.4 → MEASURED fit.
        val pairs = mapOf(
            1_171_000 to drawAt(1_171_000),
            1_920_000 to drawAt(1_920_000),
            2_803_000 to drawAt(2_803_000),
        )
        val fit = PowerModel.fit(pairs)
        assertThat(fit).isNotNull()
        assertThat(fit!!.confidence).isEqualTo(PowerModel.Confidence.MEASURED)

        val b = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = 18.0,
            targetHours = 4.0,
            oppStepsKhz = opps,
            powerModelFit = fit,
            referenceCapKhz = opps.last(),
            referenceDrawMilliW = drawAt(2_803_000),
        )
        assertThat(b.confidence).isEqualTo(PowerModel.Confidence.MEASURED)
        // Projection is present and modelled (labelled in the note).
        assertThat(b.projectedHours).isNotNull()
        assertThat(b.note.lowercase()).contains("modelled")
    }

    @Test
    fun `runtime budget is ESTIMATED with the linear fallback and stays honest`() {
        val b = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = 18.0,
            targetHours = 4.0,
            oppStepsKhz = opps,
            powerModelFit = null, // no fit → linear fallback
            referenceCapKhz = opps.last(),
            referenceDrawMilliW = 4_000L,
        )
        assertThat(b.confidence).isEqualTo(PowerModel.Confidence.ESTIMATED)
        assertThat(b.note.lowercase()).contains("estimated")
    }

    @Test
    fun `runtime budget returns null ceiling on missing data and fails open`() {
        val b = RuntimeBudgetController.computeRuntimeBudget(
            remainingWh = 0.0, // no energy data
            targetHours = 3.0,
            oppStepsKhz = opps,
        )
        assertThat(b.capCeilingKhz).isNull()
        assertThat(b.projectedHours).isNull()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SHARED CLAMP — never loosen above the ceiling
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `clamp lowers a too-high cap to the ceiling but never raises a tighter cap`() {
        val ceiling = 1_536_000
        // Band controller proposed a HIGHER cap than the ceiling → clamp DOWN to ceiling.
        assertThat(
            RuntimeBudgetController.clampCapToCeiling(2_323_000, ceiling, opps)
        ).isEqualTo(ceiling)
        // Band controller proposed stock (null = top) → ceiling forces a cap.
        assertThat(
            RuntimeBudgetController.clampCapToCeiling(null, ceiling, opps)
        ).isEqualTo(ceiling)
        // Band controller already tighter than the ceiling → NEVER raised back up.
        assertThat(
            RuntimeBudgetController.clampCapToCeiling(1_171_000, ceiling, opps)
        ).isEqualTo(1_171_000)
    }

    @Test
    fun `a ceiling at or above the top OPP is a no-op`() {
        assertThat(
            RuntimeBudgetController.clampCapToCeiling(1_920_000, opps.last(), opps)
        ).isEqualTo(1_920_000)
        assertThat(
            RuntimeBudgetController.clampCapToCeiling(null, opps.last(), opps)
        ).isNull()
    }

    @Test
    fun `null ceiling leaves the band controller cap untouched`() {
        assertThat(RuntimeBudgetController.clampCapToCeiling(1_920_000, null, opps))
            .isEqualTo(1_920_000)
        assertThat(RuntimeBudgetController.clampCapToCeiling(null, null, opps)).isNull()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_TEMP_CEILING — the outer temperature guard
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `temp guard tightens the ceiling one OPP when the die nears the ceiling`() {
        // ceiling 80°C; die at 79 (≥ 80-2) → tighten. Start from stock (null = top).
        val next = RuntimeBudgetController.computeTempCeiling(
            currentCeilingKhz = null,
            smoothedDieC = 79,
            ceilingC = 80,
            oppStepsKhz = opps,
        )
        // From the top OPP it steps down one OPP.
        assertThat(next).isEqualTo(opps[opps.lastIndex - 1])
    }

    @Test
    fun `temp guard relaxes one OPP when the die is comfortably below the ceiling`() {
        // ceiling 80°C; die at 70 (≤ 80-6) → relax. Start mid-ladder.
        val start = opps[3] // 1_536_000
        val next = RuntimeBudgetController.computeTempCeiling(
            currentCeilingKhz = start,
            smoothedDieC = 70,
            ceilingC = 80,
            oppStepsKhz = opps,
        )
        assertThat(next).isEqualTo(opps[4]) // relaxed one OPP up
    }

    @Test
    fun `temp guard holds the ceiling inside the hysteresis dead-band`() {
        // ceiling 80°C; die at 76 → between (80-6=74) and (80-2=78) → HOLD.
        val start = opps[3]
        val next = RuntimeBudgetController.computeTempCeiling(
            currentCeilingKhz = start,
            smoothedDieC = 76,
            ceilingC = 80,
            oppStepsKhz = opps,
        )
        assertThat(next).isEqualTo(start)
    }

    @Test
    fun `temp guard never tightens the ceiling below the 40 percent hard floor`() {
        // Drive many hot ticks; the ceiling must stop at the hard floor, never below.
        var ceiling: Int? = null
        repeat(40) {
            ceiling = RuntimeBudgetController.computeTempCeiling(
                currentCeilingKhz = ceiling,
                smoothedDieC = 99, // always over the ceiling → always tighten
                ceilingC = 80,
                oppStepsKhz = opps,
            )
        }
        assertThat(ceiling).isNotNull()
        assertThat(ceiling!!).isAtLeast(hardFloorKhz)
    }

    @Test
    fun `temp guard holds when the die temperature is unknown`() {
        val start = opps[3]
        val next = RuntimeBudgetController.computeTempCeiling(
            currentCeilingKhz = start,
            smoothedDieC = null,
            ceilingC = 80,
            oppStepsKhz = opps,
        )
        assertThat(next).isEqualTo(start) // no signal → never invent a tighten
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TARGET_FPS_FLOOR — the outer anti-tighten block
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `fps block holds the knee when real fps is below the floor and a tighten was proposed`() {
        // current cap 1_536_000; band controller proposed a tighter 1_171_000; real FPS
        // 50 < floor 60 → BLOCK the tighten: hold the current cap (the knee).
        val applied = RuntimeBudgetController.applyFpsFloorBlock(
            proposedCapKhz = 1_171_000,
            currentCapKhz = 1_536_000,
            realFpsX10 = 500, // 50.0 fps
            isRealFps = true,
            fpsFloor = 60,
            oppStepsKhz = opps,
        )
        assertThat(applied).isEqualTo(1_536_000)
    }

    @Test
    fun `fps block allows the tighten when fps is at or above the floor`() {
        val applied = RuntimeBudgetController.applyFpsFloorBlock(
            proposedCapKhz = 1_171_000,
            currentCapKhz = 1_536_000,
            realFpsX10 = 620, // 62.0 fps ≥ 60
            isRealFps = true,
            fpsFloor = 60,
            oppStepsKhz = opps,
        )
        assertThat(applied).isEqualTo(1_171_000) // tighten allowed
    }

    @Test
    fun `fps block is a no-op when no real fps source exists`() {
        // isRealFps = false → never act on a fabricated number; pass the proposal through.
        val applied = RuntimeBudgetController.applyFpsFloorBlock(
            proposedCapKhz = 1_171_000,
            currentCapKhz = 1_536_000,
            realFpsX10 = 100, // would be < floor, but it is NOT real
            isRealFps = false,
            fpsFloor = 60,
            oppStepsKhz = opps,
        )
        assertThat(applied).isEqualTo(1_171_000)
    }

    @Test
    fun `fps block never raises a cap above the proposal when fps is fine`() {
        // A loosen proposal (higher cap) is always allowed; the block never lowers it.
        val applied = RuntimeBudgetController.applyFpsFloorBlock(
            proposedCapKhz = 2_323_000,
            currentCapKhz = 1_536_000,
            realFpsX10 = 550, // below floor
            isRealFps = true,
            fpsFloor = 60,
            oppStepsKhz = opps,
        )
        // Proposed is HIGHER (looser) than current → not a tighten → allowed unchanged.
        assertThat(applied).isEqualTo(2_323_000)
    }

    // ── Helper: synthetic draw at a cap on a draw ∝ f^2.4 curve (mW) ───────────────
    private fun drawAt(capKhz: Int): Long {
        val ratio = capKhz.toDouble() / opps.last()
        return (4_000.0 * Math.pow(ratio, 2.4)).toLong().coerceAtLeast(1L)
    }
}
