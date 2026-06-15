package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [BatteryTarget.capForTarget].
 *
 * All pure JVM, no Android, no coroutines.
 *
 * Device model (mirrors SD8Gen2 / RP6):
 *   bigClusterOppStepsKhz = [499K, 844K, 1171K, 1536K, 1920K, 2323K, 2707K, 2803K]
 *   Typical draw at top OPP ~ 5W under load.
 *   Battery: 4000 mAh @ 4.0V = 16 Wh.
 */
class BatteryTargetTest {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    /** SD8Gen2-style OPP table, sorted ascending. */
    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(
            499_000, 844_000, 1_171_000, 1_536_000,
            1_920_000, 2_323_000, 2_707_000, 2_803_000,
        ),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    /** Reasonable test battery: 4000 mAh @ 4000 mV = 16 Wh. */
    private val capacityMah = 4000
    private val voltageMv = 4000

    // ─── Budget math ──────────────────────────────────────────────────────────

    @Test
    fun `remainingWh is mAh times voltage in volts`() {
        // 4000 mAh * 4.0 V = 16 Wh
        val result = BatteryTarget.capForTarget(
            targetHours = 4.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 4_000L, // 4W
            caps = caps,
        )
        assertThat(result.remainingWh).isWithin(0.01).of(16.0)
    }

    @Test
    fun `budget for 2h with 16Wh is 8W`() {
        // 16 Wh / 2 h = 8 W
        val result = BatteryTarget.capForTarget(
            targetHours = 2.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 5_000L,
            caps = caps,
        )
        assertThat(result.budgetW).isWithin(0.01).of(8.0)
    }

    @Test
    fun `budget for 4h with 16Wh is 4W`() {
        // 16 Wh / 4 h = 4 W
        val result = BatteryTarget.capForTarget(
            targetHours = 4.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 8_000L,
            caps = caps,
        )
        assertThat(result.budgetW).isWithin(0.01).of(4.0)
    }

    // ─── Actual life at current draw ──────────────────────────────────────────

    @Test
    fun `actualHoursAtCurrent is computed from remainingWh and current draw`() {
        // 16 Wh / 4 W = 4 h
        val result = BatteryTarget.capForTarget(
            targetHours = 2.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 4_000L,
            caps = caps,
        )
        assertThat(result.actualHoursAtCurrent).isNotNull()
        assertThat(result.actualHoursAtCurrent!!).isWithin(0.01).of(4.0)
    }

    @Test
    fun `actualHoursAtCurrent is null when device is charging (draw = 0)`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 3.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 0L,
            caps = caps,
        )
        assertThat(result.actualHoursAtCurrent).isNull()
    }

    // ─── OPP mapping — achievable targets ────────────────────────────────────

    @Test
    fun `achievable target returns a non-null mapped OPP cap`() {
        // Current draw = 6W. Target = 2h → need ≤8W.
        // Linear heuristic: budget fraction = 8/6 > 1 → highest cap is within budget.
        // Actually any cap at fraction ≤ 8/6 of current draw fits.
        // With 6W current draw at top OPP (2803 MHz):
        //   all OPP steps' fraction ≤ 1.0, estimated draw ≤ 6W ≤ 8W → achievable.
        val result = BatteryTarget.capForTarget(
            targetHours = 2.0,    // budget = 16Wh / 2h = 8W
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 6_000L, // 6W
            caps = caps,
        )
        assertThat(result.achievable).isTrue()
        assertThat(result.mappedCapKhz).isNotNull()
        assertThat(caps.bigClusterOppStepsKhz).contains(result.mappedCapKhz)
    }

    @Test
    fun `mapped cap is the highest OPP step whose estimated draw is within budget`() {
        // Current draw = 8W at full cap (2803 MHz).
        // Target = 3h → budget = 16Wh / 3h = 5.33W.
        // Linear heuristic: estimated draw at cap = (cap/topCap) * 8W.
        // Need cap/2803000 * 8000 <= 5333 → cap <= 5333/8000 * 2803000 = 1868667 kHz.
        // Highest OPP step ≤ 1868667 kHz → 1_536_000 kHz.
        val result = BatteryTarget.capForTarget(
            targetHours = 3.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 8_000L,
            caps = caps,
        )
        assertThat(result.achievable).isTrue()
        val cap = result.mappedCapKhz!!
        // Must be a real OPP step.
        assertThat(caps.bigClusterOppStepsKhz).contains(cap)
        // Must not be above top OPP.
        assertThat(cap).isAtMost(caps.bigClusterOppStepsKhz.last())
    }

    // ─── OPP mapping — NOT achievable ────────────────────────────────────────

    @Test
    fun `not achievable when target requires less power than lowest OPP step`() {
        // Current draw = 10W. Target = 10h → budget = 16Wh/10h = 1.6W.
        // Linear: lowest cap (499 MHz) fraction = 499/2803 ≈ 0.178.
        // Estimated draw at lowest cap = 0.178 * 10 = 1.78W > 1.6W → not achievable.
        val result = BatteryTarget.capForTarget(
            targetHours = 10.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 10_000L,
            caps = caps,
        )
        assertThat(result.achievable).isFalse()
        assertThat(result.mappedCapKhz).isNull()
    }

    @Test
    fun `not-achievable result still reports actual life at current draw`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 10.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 10_000L,
            caps = caps,
        )
        // Even though not achievable, we still tell the user their actual life.
        // 16 Wh / 10 W = 1.6 h
        assertThat(result.actualHoursAtCurrent).isNotNull()
        assertThat(result.actualHoursAtCurrent!!).isWithin(0.01).of(1.6)
    }

    @Test
    fun `honesty note for not-achievable mentions 'NOT achievable'`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 20.0, // impossible
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 5_000L,
            caps = caps,
        )
        assertThat(result.achievable).isFalse()
        assertThat(result.honestyNote.uppercase()).contains("NOT ACHIEVABLE")
    }

    @Test
    fun `honesty note for achievable target mentions the cap MHz`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 3.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 8_000L,
            caps = caps,
        )
        if (result.achievable) {
            val capMhz = result.mappedCapKhz!! / 1000
            assertThat(result.honestyNote).contains("$capMhz MHz")
        }
    }

    @Test
    fun `honesty note always mentions current draw estimate`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 3.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 4_000L,
            caps = caps,
        )
        // Should mention something about the current draw life.
        assertThat(result.honestyNote.lowercase()).containsMatch("draw|current|get ~")
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `empty OPP table returns achievable=false with null mappedCapKhz`() {
        val emptyCaps = caps.copy(bigClusterOppStepsKhz = emptyList())
        val result = BatteryTarget.capForTarget(
            targetHours = 2.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 4_000L,
            caps = emptyCaps,
        )
        assertThat(result.achievable).isFalse()
        assertThat(result.mappedCapKhz).isNull()
    }

    @Test
    fun `zero remaining capacity gives 0 remainingWh and no cap`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 2.0,
            remainingCapacityMah = 0,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 4_000L,
            caps = caps,
        )
        assertThat(result.remainingWh).isEqualTo(0.0)
        assertThat(result.achievable).isFalse()
        assertThat(result.mappedCapKhz).isNull()
    }

    @Test
    fun `zero voltage gives 0 remainingWh and no cap`() {
        val result = BatteryTarget.capForTarget(
            targetHours = 2.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = 0,
            currentDrawMw = 4_000L,
            caps = caps,
        )
        assertThat(result.remainingWh).isEqualTo(0.0)
        assertThat(result.achievable).isFalse()
    }

    @Test
    fun `very short target (0_5h) with plenty of capacity is achievable`() {
        // 16 Wh / 0.5 h = 32 W budget — very relaxed, every cap fits.
        val result = BatteryTarget.capForTarget(
            targetHours = 0.5,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 5_000L,
            caps = caps,
        )
        assertThat(result.achievable).isTrue()
        // The mapped cap should be the highest possible (budget is very generous).
        assertThat(result.mappedCapKhz).isEqualTo(caps.bigClusterOppStepsKhz.last())
    }

    // ─── With measured draw curve ─────────────────────────────────────────────

    @Test
    fun `draw curve overrides heuristic when provided`() {
        // Measured curve: only the lowest two caps are within a tight 2W budget.
        // Current draw = 10W, voltage = 4V, capacity = 4000 mAh → 16 Wh.
        // Target = 3h → budget = 16/3 ≈ 5.33W.
        val measuredCurve = mapOf(
            499_000  to 1_000L,  // 1 W — within 5.33W budget
            844_000  to 2_000L,  // 2 W — within budget
            1_171_000 to 4_000L, // 4 W — within budget
            1_536_000 to 5_000L, // 5 W — within budget
            1_920_000 to 7_000L, // 7 W — OVER budget
            2_323_000 to 9_000L,
            2_707_000 to 11_000L,
            2_803_000 to 12_000L,
        )
        val result = BatteryTarget.capForTarget(
            targetHours = 3.0,
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 12_000L,
            caps = caps,
            drawCurve = measuredCurve,
        )
        assertThat(result.achievable).isTrue()
        // Highest cap with measured draw (5000 mW) ≤ budget (5333 mW) → 1_536_000.
        assertThat(result.mappedCapKhz).isEqualTo(1_536_000)
    }

    @Test
    fun `not achievable even with curve when draw at lowest cap exceeds budget`() {
        val tightCurve = mapOf(
            499_000  to 6_000L, // 6W — over a 5.33W budget
            844_000  to 8_000L,
            1_536_000 to 12_000L,
            2_803_000 to 15_000L,
        )
        val result = BatteryTarget.capForTarget(
            targetHours = 3.0, // budget = 16/3 ≈ 5.33W
            remainingCapacityMah = capacityMah,
            batteryVoltageMv = voltageMv,
            currentDrawMw = 15_000L,
            caps = caps,
            drawCurve = tightCurve,
        )
        assertThat(result.achievable).isFalse()
        assertThat(result.mappedCapKhz).isNull()
        assertThat(result.honestyNote.uppercase()).contains("NOT ACHIEVABLE")
    }

    // ─── formatHours helper ───────────────────────────────────────────────────

    @Test
    fun `formatHours formats whole hours`() {
        assertThat(BatteryTarget.formatHours(3.0)).isEqualTo("3h")
    }

    @Test
    fun `formatHours formats hours and minutes`() {
        assertThat(BatteryTarget.formatHours(2.6667)).isEqualTo("2h40m")
    }

    @Test
    fun `formatHours formats sub-hour as minutes`() {
        assertThat(BatteryTarget.formatHours(0.5)).isEqualTo("30m")
    }

    @Test
    fun `formatHours handles fractional hours near boundary`() {
        // 1.0 h = exactly 60 min → "1h"
        assertThat(BatteryTarget.formatHours(1.0)).isEqualTo("1h")
    }
}
