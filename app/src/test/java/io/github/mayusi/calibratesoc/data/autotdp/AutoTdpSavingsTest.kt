package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [AutoTdpSavings.computeSavings].
 *
 * Verifies delta computation, percentage calculation, and the
 * [SavingsResult.enoughData] gate that prevents false claims with too few samples.
 */
class AutoTdpSavingsTest {

    // ─── Basic delta computation ────────────────────────────────────────────────

    @Test
    fun `positive delta when AutoTDP draws less than baseline`() {
        val baseline = List(20) { 4_000L }  // 4000 mW without AutoTDP
        val tuned = List(20) { 3_200L }     // 3200 mW with AutoTDP

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.enoughData).isTrue()
        assertThat(result.baselineMw).isEqualTo(4_000L)
        assertThat(result.tunedMw).isEqualTo(3_200L)
        assertThat(result.deltaMw).isEqualTo(800L)   // 4000 - 3200
    }

    @Test
    fun `delta percentage is correct`() {
        // 20% savings: (4000 - 3200) / 4000 * 100 = 20.0
        val baseline = List(20) { 4_000L }
        val tuned = List(20) { 3_200L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.deltaPct).isWithin(0.01).of(20.0)
    }

    @Test
    fun `negative delta when AutoTDP draws more than baseline`() {
        // This can happen on adversarial inputs or measurement noise — must not crash.
        val baseline = List(20) { 3_000L }
        val tuned = List(20) { 3_500L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.enoughData).isTrue()
        assertThat(result.deltaMw).isEqualTo(-500L)  // negative = no savings
        assertThat(result.deltaPct).isLessThan(0.0)
    }

    @Test
    fun `zero delta when draws are identical`() {
        val baseline = List(20) { 3_500L }
        val tuned = List(20) { 3_500L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.deltaMw).isEqualTo(0L)
        assertThat(result.deltaPct).isWithin(0.001).of(0.0)
    }

    @Test
    fun `sample count reflects minimum of baseline and tuned list sizes`() {
        val baseline = List(25) { 4_000L }
        val tuned = List(20) { 3_200L }   // smaller list

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.sampleCount).isEqualTo(20)
    }

    // ─── enoughData gate ───────────────────────────────────────────────────────

    @Test
    fun `enoughData false when fewer than MIN_SAMPLES samples`() {
        val baseline = List(AutoTdpSavings.MIN_SAMPLES_FOR_REPORT - 1) { 4_000L }
        val tuned = List(AutoTdpSavings.MIN_SAMPLES_FOR_REPORT - 1) { 3_200L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.enoughData).isFalse()
    }

    @Test
    fun `enoughData true when exactly MIN_SAMPLES samples`() {
        val baseline = List(AutoTdpSavings.MIN_SAMPLES_FOR_REPORT) { 4_000L }
        val tuned = List(AutoTdpSavings.MIN_SAMPLES_FOR_REPORT) { 3_200L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.enoughData).isTrue()
    }

    @Test
    fun `enoughData false for empty baseline`() {
        val result = AutoTdpSavings.computeSavings(emptyList(), List(20) { 3_200L })
        assertThat(result.enoughData).isFalse()
    }

    @Test
    fun `enoughData false for empty tuned`() {
        val result = AutoTdpSavings.computeSavings(List(20) { 4_000L }, emptyList())
        assertThat(result.enoughData).isFalse()
    }

    @Test
    fun `enoughData false for both lists empty`() {
        val result = AutoTdpSavings.computeSavings(emptyList(), emptyList())
        assertThat(result.enoughData).isFalse()
        assertThat(result.deltaMw).isEqualTo(0L)
    }

    // ─── Null/zero draw values are excluded ────────────────────────────────────

    @Test
    fun `zero mW readings are excluded from average`() {
        // Zero-draws can occur when batteryCurrentUa or Voltage is null.
        // We exclude them from the average (they'd corrupt the mean).
        val baseline = List(15) { 4_000L } + List(5) { 0L }   // 5 zeros (nulls coerced)
        val tuned = List(20) { 3_200L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.enoughData).isTrue()
        // Baseline average should be 4000, not skewed by the zeros.
        assertThat(result.baselineMw).isEqualTo(4_000L)
    }

    // ─── Large delta percentages ───────────────────────────────────────────────

    @Test
    fun `50 pct savings computes correctly`() {
        val baseline = List(20) { 6_000L }
        val tuned = List(20) { 3_000L }

        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        assertThat(result.deltaPct).isWithin(0.01).of(50.0)
        assertThat(result.deltaMw).isEqualTo(3_000L)
    }

    @Test
    fun `zero baseline does not produce divide-by-zero exception`() {
        val baseline = List(20) { 0L }
        val tuned = List(20) { 3_200L }

        // Must not throw.
        val result = AutoTdpSavings.computeSavings(baseline, tuned)

        // deltaPct defaults to 0.0 when baseline is 0.
        assertThat(result.deltaPct).isWithin(0.001).of(0.0)
    }

    // ─── Averaged means across variable-size lists ────────────────────────────

    @Test
    fun `means are computed correctly for non-uniform sample values`() {
        // 10 samples mixing low/high readings around 4000 mW.
        // sum = 3+3+3+5+4*6 = 38 (thousands) → mean 3800 mW.
        // Verifies that every sample counts individually toward the mean (no dedup).
        val baselineValues = listOf(3_000L, 3_000L, 3_000L, 5_000L, 4_000L,
                                    4_000L, 4_000L, 4_000L, 4_000L, 4_000L)
        val tunedValues = List(10) { 3_000L }

        val result = AutoTdpSavings.computeSavings(baselineValues, tunedValues)

        assertThat(result.enoughData).isTrue()
        assertThat(result.baselineMw).isEqualTo(3_800L)  // (3*3+5+4*6)/10 = 38/10 = 3.8k
        assertThat(result.tunedMw).isEqualTo(3_000L)
        assertThat(result.deltaMw).isEqualTo(800L)
    }
}
