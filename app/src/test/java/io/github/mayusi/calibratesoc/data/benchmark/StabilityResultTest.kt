package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StabilityResultTest {

    @Test
    fun `stability is min over max times 100`() {
        // min 60, max 120 -> 50%
        assertThat(StabilityResult.compute(listOf(120.0, 90.0, 60.0))).isEqualTo(50)
    }

    @Test
    fun `flat curve is 100 percent`() {
        assertThat(StabilityResult.compute(listOf(60.0, 60.0, 60.0))).isEqualTo(100)
    }

    @Test
    fun `empty list is zero`() {
        assertThat(StabilityResult.compute(emptyList())).isEqualTo(0)
    }

    @Test
    fun `non-positive max is zero`() {
        assertThat(StabilityResult.compute(listOf(0.0, 0.0))).isEqualTo(0)
    }

    @Test
    fun `result is clamped to 0 to 100`() {
        val pct = StabilityResult.compute(listOf(30.0, 90.0))
        assertThat(pct).isIn(0..100)
        assertThat(pct).isEqualTo(33)
    }
}
