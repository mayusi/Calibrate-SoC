package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GpuFrameSummaryTest {

    @Test
    fun `percentile interpolates on known ascending array`() {
        val arr = floatArrayOf(10f, 20f, 30f, 40f)
        // p50 of 4 elements: rank = 0.5 * 3 = 1.5 -> between 20 and 30 -> 25
        assertThat(GpuFrameSummary.percentile(arr, 50.0)).isWithin(0.01).of(25.0)
        // p99: rank = 0.99 * 3 = 2.97 -> 30 + 0.97*(40-30) = 39.7
        assertThat(GpuFrameSummary.percentile(arr, 99.0)).isWithin(0.01).of(39.7)
    }

    @Test
    fun `percentile single element returns that element`() {
        assertThat(GpuFrameSummary.percentile(floatArrayOf(42f), 50.0)).isEqualTo(42.0)
    }

    @Test
    fun `p1 low derives from 99th percentile slowest frame`() {
        // Mostly 10ms frames + a few 50ms spikes => p99 near 50ms => p1Low ~20fps.
        val frames = FloatArray(100) { 10f }
        for (i in 95 until 100) frames[i] = 50f
        val s = GpuFrameSummary.from(avgFps = 0.0, frames = frames, downsampleTo = 600)
        assertThat(s.p99FrameMs).isWithin(2.0).of(50.0)
        assertThat(s.p1LowFps).isWithin(2.0).of(20.0)
    }

    @Test
    fun `consistency is 100 for perfectly even frames`() {
        val frames = FloatArray(50) { 10f }
        val s = GpuFrameSummary.from(avgFps = 100.0, frames = frames, downsampleTo = 600)
        assertThat(s.consistencyPct).isEqualTo(100.0)
    }

    @Test
    fun `consistency is below 100 for skewed frames`() {
        val frames = FloatArray(100) { 10f }
        for (i in 90 until 100) frames[i] = 40f
        val s = GpuFrameSummary.from(avgFps = 0.0, frames = frames, downsampleTo = 600)
        assertThat(s.consistencyPct).isLessThan(100.0)
    }

    @Test
    fun `downsample reduces to target preserving first and last exactly`() {
        val src = FloatArray(2000) { it.toFloat() }
        val out = GpuFrameSummary.downsample(src, 600)
        assertThat(out.size).isEqualTo(600)
        // Fixed implementation: first point = src[0], last point = src[size-1] exactly.
        assertThat(out.first()).isEqualTo(0f)
        assertThat(out.last()).isEqualTo(1999f)
    }

    @Test
    fun `downsample target 1 returns only first element`() {
        val src = FloatArray(100) { it.toFloat() }
        val out = GpuFrameSummary.downsample(src, 1)
        assertThat(out).hasSize(1)
        assertThat(out[0]).isEqualTo(0f)
    }

    @Test
    fun `downsample target 2 returns exactly first and last`() {
        val src = FloatArray(100) { it.toFloat() }
        val out = GpuFrameSummary.downsample(src, 2)
        assertThat(out).hasSize(2)
        assertThat(out[0]).isEqualTo(0f)
        assertThat(out[1]).isEqualTo(99f)
    }

    @Test
    fun `downsample passes through when under target`() {
        val src = floatArrayOf(1f, 2f, 3f)
        assertThat(GpuFrameSummary.downsample(src, 600)).containsExactly(1f, 2f, 3f).inOrder()
    }

    @Test
    fun `empty frames yields zeros and no crash`() {
        val s = GpuFrameSummary.from(avgFps = 12.0, frames = FloatArray(0), downsampleTo = 600)
        assertThat(s.avgFps).isEqualTo(12.0)
        assertThat(s.avgFrameMs).isEqualTo(0.0)
        assertThat(s.p1LowFps).isEqualTo(0.0)
        assertThat(s.consistencyPct).isEqualTo(0.0)
        assertThat(s.frameTimesMsDownsampled).isEmpty()
    }
}
