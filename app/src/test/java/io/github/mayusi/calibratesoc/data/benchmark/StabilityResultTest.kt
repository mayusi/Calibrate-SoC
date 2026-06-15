package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StabilityResultTest {

    @Test
    fun `sustained over peak is avg of last 25 percent over max times 100`() {
        // 4 loops: [60, 55, 45, 40]. Last 25% is [40]. 40/60 = 66%
        val loopFps = listOf(60.0, 55.0, 45.0, 40.0)
        assertThat(StabilityResult.compute(loopFps)).isEqualTo(66)
    }

    @Test
    fun `held flat across run is 100 percent`() {
        // All same FPS: avg of last 25% = 60, max = 60 -> 100%
        assertThat(StabilityResult.compute(listOf(60.0, 60.0, 60.0, 60.0))).isEqualTo(100)
    }

    @Test
    fun `sagging run shows drop in sustained metric`() {
        // Start 120, drop to 60 over 8 loops. Last 25% (2 loops at end) = [65, 60]. Avg 62.5 / max 120 ≈ 52%
        val loopFps = listOf(120.0, 115.0, 100.0, 90.0, 80.0, 70.0, 65.0, 60.0)
        assertThat(StabilityResult.compute(loopFps)).isEqualTo(52)
    }

    @Test
    fun `empty list returns null`() {
        // BUG 5: empty = no loops = N/A, not 0.
        assertThat(StabilityResult.compute(emptyList())).isNull()
    }

    @Test
    fun `single loop returns null`() {
        // BUG 5: 1 loop gives no sustained-vs-peak signal — must return null, not 100%.
        assertThat(StabilityResult.compute(listOf(60.0))).isNull()
    }

    @Test
    fun `non-positive max returns null`() {
        // Can't compute ratio when peak is 0.
        assertThat(StabilityResult.compute(listOf(0.0, 0.0))).isNull()
    }

    @Test
    fun `result is clamped to 0 to 100`() {
        val pct = StabilityResult.compute(listOf(120.0, 30.0))
        assertThat(pct).isNotNull()
        assertThat(pct!!).isIn(0..100)
        assertThat(pct).isEqualTo(25)
    }
}
