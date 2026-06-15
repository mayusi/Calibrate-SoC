package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure [findKnee] function and [CurvePoint] math.
 *
 * No Android runtime, no coroutines, no mocks — all pure JVM.
 */
class EfficiencyCurveTest {

    // ─── CurvePoint.of math ───────────────────────────────────────────────────

    @Test
    fun `CurvePoint_of computes perfPerWatt correctly`() {
        // perfScore = 1000, drawMw = 2000 (2W) → perfPerWatt = 1000 / 2.0 = 500
        val point = CurvePoint.of(capKhz = 1_800_000, perfScore = 1000.0, drawMw = 2000L)
        assertThat(point.perfPerWatt).isWithin(0.01).of(500.0)
    }

    @Test
    fun `CurvePoint_of returns 0 perfPerWatt when drawMw is 0`() {
        val point = CurvePoint.of(capKhz = 1_800_000, perfScore = 1000.0, drawMw = 0L)
        assertThat(point.perfPerWatt).isEqualTo(0.0)
    }

    // ─── findKnee: empty / single point ──────────────────────────────────────

    @Test
    fun `findKnee returns null for empty list`() {
        assertThat(findKnee(emptyList())).isNull()
    }

    @Test
    fun `findKnee returns null for a single point`() {
        val single = listOf(CurvePoint.of(1_000_000, 500.0, 1000L))
        assertThat(findKnee(single)).isNull()
    }

    @Test
    fun `findKnee returns null when all points have zero draw`() {
        val points = listOf(
            CurvePoint.of(1_000_000, 500.0, 0L),
            CurvePoint.of(2_000_000, 800.0, 0L),
        )
        assertThat(findKnee(points)).isNull()
    }

    @Test
    fun `findKnee returns null when only one point has valid draw`() {
        // Needs at least 2 valid (drawMw > 0) points to identify a knee.
        val points = listOf(
            CurvePoint.of(1_000_000, 500.0, 1500L),  // valid
            CurvePoint.of(2_000_000, 800.0, 0L),      // charging / no data
        )
        assertThat(findKnee(points)).isNull()
    }

    // ─── findKnee: best perf-per-watt wins ───────────────────────────────────

    @Test
    fun `findKnee picks the point with the highest perfPerWatt`() {
        // Mid-cap has best perf-per-watt
        val points = listOf(
            CurvePoint.of(capKhz = 3_000_000, perfScore = 900.0, drawMw = 6_000L), // ppw = 150
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 2_000L), // ppw = 400 ← KNEE
            CurvePoint.of(capKhz = 1_000_000, perfScore = 400.0, drawMw = 1_500L), // ppw = ~267
        )
        val knee = findKnee(points)
        assertThat(knee).isNotNull()
        assertThat(knee!!.capKhz).isEqualTo(2_000_000)
    }

    @Test
    fun `findKnee identifies knee at lowest cap in an always-diminishing curve`() {
        // Lowest cap has best ppw — deep efficiency wins
        val points = listOf(
            CurvePoint.of(capKhz = 2_800_000, perfScore = 900.0, drawMw = 5_000L), // ppw = 180
            CurvePoint.of(capKhz = 1_900_000, perfScore = 700.0, drawMw = 2_000L), // ppw = 350
            CurvePoint.of(capKhz = 1_200_000, perfScore = 500.0, drawMw = 1_000L), // ppw = 500 ← KNEE
        )
        val knee = findKnee(points)
        assertThat(knee!!.capKhz).isEqualTo(1_200_000)
    }

    @Test
    fun `findKnee identifies knee at top cap when it is most efficient`() {
        // High cap has best ppw (unusual but possible — higher freq sometimes more efficient)
        val points = listOf(
            CurvePoint.of(capKhz = 3_000_000, perfScore = 1200.0, drawMw = 2_000L), // ppw = 600 ← KNEE
            CurvePoint.of(capKhz = 2_000_000, perfScore = 500.0, drawMw = 1_800L),  // ppw = ~278
            CurvePoint.of(capKhz = 1_000_000, perfScore = 300.0, drawMw = 1_600L),  // ppw = 187.5
        )
        val knee = findKnee(points)
        assertThat(knee!!.capKhz).isEqualTo(3_000_000)
    }

    @Test
    fun `findKnee on a tie picks the lower-cap (more conservative) point`() {
        // Two points with the SAME perfPerWatt — lower cap wins (more conservative).
        // Both: score / (draw/1000) = 1000 / 2.0 = 500.
        val points = listOf(
            CurvePoint.of(capKhz = 2_000_000, perfScore = 1000.0, drawMw = 2_000L), // ppw = 500
            CurvePoint.of(capKhz = 1_000_000, perfScore = 500.0, drawMw = 1_000L),  // ppw = 500
        )
        val knee = findKnee(points)
        assertThat(knee!!.capKhz).isEqualTo(1_000_000)
    }

    @Test
    fun `findKnee ignores points with zero draw when finding knee`() {
        // One zero-draw point is excluded; remaining 2 valid points form the curve.
        val points = listOf(
            CurvePoint.of(capKhz = 3_000_000, perfScore = 900.0, drawMw = 0L),     // excluded
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 2_000L), // ppw = 400 ← KNEE
            CurvePoint.of(capKhz = 1_000_000, perfScore = 400.0, drawMw = 1_500L), // ppw = ~267
        )
        val knee = findKnee(points)
        assertThat(knee!!.capKhz).isEqualTo(2_000_000)
    }

    @Test
    fun `findKnee works with a large OPP table (8 points)`() {
        // Simulate an 8-step Snapdragon-style OPP table.
        val steps = listOf(
            499_000 to (300L to 200.0),
            844_000 to (500L to 360.0),
            1_171_000 to (700L to 510.0),
            1_536_000 to (900L to 690.0),  // ppw = 690 / 0.9 = ~767 ← expected knee
            1_920_000 to (1300L to 700.0), // ppw = 700 / 1.3 = ~538
            2_323_000 to (1700L to 700.0),
            2_707_000 to (2200L to 750.0),
            2_803_000 to (2500L to 800.0),
        )
        val points = steps.map { (cap, pair) ->
            val (draw, score) = pair
            CurvePoint.of(capKhz = cap, perfScore = score, drawMw = draw)
        }
        val knee = findKnee(points)
        assertThat(knee).isNotNull()
        // The knee at 1_536_000 has ppw ≈ 767 which is highest in this table.
        assertThat(knee!!.capKhz).isEqualTo(1_536_000)
    }

    @Test
    fun `findKnee returns null when two points but both have zero draw`() {
        val points = listOf(
            CurvePoint.of(1_000_000, 400.0, 0L),
            CurvePoint.of(2_000_000, 800.0, 0L),
        )
        assertThat(findKnee(points)).isNull()
    }

    @Test
    fun `findKnee result is always in the input list`() {
        val points = (1..5).map { i ->
            CurvePoint.of(capKhz = i * 500_000, perfScore = i * 100.0, drawMw = (i * 400).toLong())
        }
        val knee = findKnee(points)
        if (knee != null) {
            assertThat(points).contains(knee)
        }
    }
}
