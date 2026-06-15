package io.github.mayusi.calibratesoc.ui.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.CurvePoint
import org.junit.Test

/**
 * Unit tests for [AutoTdpViewModel.rankOppPoints] — the pure helper that
 * annotates sweep curve points with a normalised perf-per-watt fraction
 * for the efficiency curve bar chart.
 *
 * No Android runtime, no coroutines, no mocks — pure JVM.
 */
class CurveBarRankingTest {

    // ─── rankOppPoints: empty / trivial ──────────────────────────────────────

    @Test
    fun `rankOppPoints returns empty list for empty input`() {
        val result = AutoTdpViewModel.rankOppPoints(emptyList(), kneeKhz = null)
        assertThat(result).isEmpty()
    }

    @Test
    fun `rankOppPoints returns single entry for single-point curve`() {
        val point = CurvePoint.of(capKhz = 2_000_000, perfScore = 500.0, drawMw = 1_000L)
        val result = AutoTdpViewModel.rankOppPoints(listOf(point), kneeKhz = null)
        assertThat(result).hasSize(1)
        assertThat(result[0].capMhz).isEqualTo(2_000)
        assertThat(result[0].ppwFraction).isWithin(0.001f).of(1.0f)
        assertThat(result[0].isKnee).isFalse()
    }

    // ─── rankOppPoints: normalisation ────────────────────────────────────────

    @Test
    fun `best point gets ppwFraction of 1_0 and others are proportional`() {
        // ppw: 500, 250, 100 → fractions: 1.0, 0.5, 0.2
        val points = listOf(
            CurvePoint.of(capKhz = 3_000_000, perfScore = 1000.0, drawMw = 2_000L), // ppw = 500
            CurvePoint.of(capKhz = 2_000_000, perfScore = 500.0,  drawMw = 2_000L), // ppw = 250
            CurvePoint.of(capKhz = 1_000_000, perfScore = 200.0,  drawMw = 2_000L), // ppw = 100
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = 3_000_000)

        assertThat(result).hasSize(3)
        assertThat(result[0].ppwFraction).isWithin(0.001f).of(1.0f)
        assertThat(result[1].ppwFraction).isWithin(0.001f).of(0.5f)
        assertThat(result[2].ppwFraction).isWithin(0.002f).of(0.2f)
    }

    @Test
    fun `all points with equal perfPerWatt get ppwFraction of 1_0`() {
        val points = listOf(
            CurvePoint.of(capKhz = 2_000_000, perfScore = 1000.0, drawMw = 2_000L), // ppw = 500
            CurvePoint.of(capKhz = 1_000_000, perfScore = 500.0,  drawMw = 1_000L), // ppw = 500
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = null)

        result.forEach { pt ->
            assertThat(pt.ppwFraction).isWithin(0.001f).of(1.0f)
        }
    }

    // ─── rankOppPoints: zero draw (ppw == 0) ─────────────────────────────────

    @Test
    fun `points with zero draw get ppwFraction of 0`() {
        val points = listOf(
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 2_000L), // ppw = 400 (best)
            CurvePoint.of(capKhz = 1_000_000, perfScore = 500.0, drawMw = 0L),     // ppw = 0
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = 2_000_000)

        assertThat(result[0].ppwFraction).isWithin(0.001f).of(1.0f)
        assertThat(result[1].ppwFraction).isWithin(0.001f).of(0.0f)
    }

    @Test
    fun `all zero-draw points produce zero fractions without crash`() {
        val points = listOf(
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 0L),
            CurvePoint.of(capKhz = 1_000_000, perfScore = 500.0, drawMw = 0L),
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = null)
        result.forEach { pt ->
            assertThat(pt.ppwFraction).isWithin(0.001f).of(0.0f)
        }
    }

    // ─── rankOppPoints: knee flagging ────────────────────────────────────────

    @Test
    fun `knee point has isKnee true and others false`() {
        val points = listOf(
            CurvePoint.of(capKhz = 3_000_000, perfScore = 900.0, drawMw = 6_000L), // ppw = 150
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 2_000L), // ppw = 400 ← knee
            CurvePoint.of(capKhz = 1_000_000, perfScore = 400.0, drawMw = 1_500L), // ppw ≈ 267
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = 2_000_000)

        assertThat(result[0].isKnee).isFalse()
        assertThat(result[1].isKnee).isTrue()
        assertThat(result[2].isKnee).isFalse()
    }

    @Test
    fun `no point is knee when kneeKhz is null`() {
        val points = listOf(
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 2_000L),
            CurvePoint.of(capKhz = 1_000_000, perfScore = 400.0, drawMw = 1_500L),
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = null)
        result.forEach { pt ->
            assertThat(pt.isKnee).isFalse()
        }
    }

    @Test
    fun `kneeKhz that does not match any point leaves all isKnee false`() {
        val points = listOf(
            CurvePoint.of(capKhz = 2_000_000, perfScore = 800.0, drawMw = 2_000L),
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = 9_999_999)
        assertThat(result[0].isKnee).isFalse()
    }

    // ─── rankOppPoints: capMhz conversion ────────────────────────────────────

    @Test
    fun `capMhz is kHz divided by 1000`() {
        val points = listOf(
            CurvePoint.of(capKhz = 2_803_000, perfScore = 800.0, drawMw = 2_000L),
            CurvePoint.of(capKhz = 1_536_000, perfScore = 600.0, drawMw = 1_500L),
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = null)
        assertThat(result[0].capMhz).isEqualTo(2_803)
        assertThat(result[1].capMhz).isEqualTo(1_536)
    }

    // ─── rankOppPoints: output order preserved ───────────────────────────────

    @Test
    fun `output order matches input order`() {
        val points = listOf(
            CurvePoint.of(capKhz = 500_000, perfScore = 200.0, drawMw = 500L),
            CurvePoint.of(capKhz = 1_000_000, perfScore = 400.0, drawMw = 900L),
            CurvePoint.of(capKhz = 2_000_000, perfScore = 700.0, drawMw = 2_500L),
        )
        val result = AutoTdpViewModel.rankOppPoints(points, kneeKhz = null)
        assertThat(result.map { it.capMhz }).containsExactly(500, 1_000, 2_000).inOrder()
    }

    // ─── rankOppPoints: drawMw and perfPerWatt passthrough ───────────────────

    @Test
    fun `drawMw and perfPerWatt are copied from CurvePoint`() {
        val point = CurvePoint.of(capKhz = 1_800_000, perfScore = 900.0, drawMw = 3_000L)
        val result = AutoTdpViewModel.rankOppPoints(listOf(point), kneeKhz = null)
        assertThat(result[0].drawMw).isEqualTo(3_000L)
        assertThat(result[0].perfPerWatt).isWithin(0.01).of(300.0) // 900 / 3.0
    }
}
