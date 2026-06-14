package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [BenchBottleneck.diagnose].
 *
 * All pure JVM — no Android deps. Tests verify the 5 rules fire correctly
 * and degrade gracefully when data is absent.
 */
class BenchBottleneckTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun kernels(
        cpuUsageDuringGpuPct: Int? = null,
        cpuDrawCallFps: Double? = null,
        gpuFps: Double? = null,
        memoryBandwidthMBps: Double? = null,
    ) = KernelScores(
        cpuUsageDuringGpuPct = cpuUsageDuringGpuPct,
        cpuDrawCallFps = cpuDrawCallFps,
        gpuFps = gpuFps,
        memoryBandwidthMBps = memoryBandwidthMBps,
    )

    private fun throttle(
        dropPct: Double,
        timeToThrottleMs: Long? = 20_000L,
        peakMhz: Int = 3200,
        sustainedMhz: Int = (3200 * (1 - dropPct / 100)).toInt(),
    ) = ThrottleAnalysis(
        startMhz = peakMhz,
        sustainedMhz = sustainedMhz,
        endMhz = sustainedMhz,
        peakMhz = peakMhz,
        dropPct = dropPct,
        timeToThrottleMs = timeToThrottleMs,
        peakCpuTempC = 80f,
        peakGpuTempC = null,
        thermalHeadroomC = 5f,
        avgPowerMw = null,
        energyMwh = null,
    )

    // ── Rule 1: Thermal throttle ─────────────────────────────────────────

    @Test
    fun `rule1 fires when dropPct is 15 percent or more`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 20, gpuFps = 60.0),
            throttle = throttle(dropPct = 20.0, timeToThrottleMs = 25_000L),
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.THERMAL_THROTTLE)
    }

    @Test
    fun `rule1 does not fire when dropPct is below 15 percent`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 20, gpuFps = 60.0),
            throttle = throttle(dropPct = 10.0),
        )
        // Should fall through to GPU_BOUND (cpuPct = 20 < 40)
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.GPU_BOUND)
    }

    @Test
    fun `rule1 reports high confidence when timeToThrottle is known`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(),
            throttle = throttle(dropPct = 18.0, timeToThrottleMs = 30_000L),
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.THERMAL_THROTTLE)
        assertThat(verdict.confidence).isEqualTo(BenchBottleneck.BottleneckVerdict.Confidence.HIGH)
    }

    @Test
    fun `rule1 medium confidence when timeToThrottle is null`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(),
            throttle = throttle(dropPct = 18.0, timeToThrottleMs = null),
        )
        assertThat(verdict.confidence).isEqualTo(BenchBottleneck.BottleneckVerdict.Confidence.MEDIUM)
    }

    // ── Rule 2: CPU draw-call limited ────────────────────────────────────

    @Test
    fun `rule2 fires when cpu usage during gpu is 70 percent or more`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 75, gpuFps = 80.0),
            throttle = null,
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.CPU_DRAW_CALL)
    }

    @Test
    fun `rule2 fires when draw call fps is tight vs gpu fps`() {
        // drawCallFps < gpuFps * 1.5 → cpu limited
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(
                cpuUsageDuringGpuPct = 45,   // not high enough alone
                cpuDrawCallFps = 100.0,
                gpuFps = 80.0,               // 80 * 1.5 = 120 > 100 → tight
            ),
            throttle = null,
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.CPU_DRAW_CALL)
    }

    @Test
    fun `rule2 does not fire when draw call fps is comfortably above gpu fps`() {
        // drawCallFps >= gpuFps * 1.5 → NOT tight
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(
                cpuUsageDuringGpuPct = 45,   // not high enough alone
                cpuDrawCallFps = 200.0,
                gpuFps = 80.0,               // 80 * 1.5 = 120 < 200 → ok
            ),
            throttle = null,
        )
        // Falls through to GPU_BOUND (cpuPct = 45, not < 40) then BALANCED
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.BALANCED)
    }

    @Test
    fun `rule2 high confidence when both signals fire`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(
                cpuUsageDuringGpuPct = 80,
                cpuDrawCallFps = 90.0,
                gpuFps = 80.0,
            ),
            throttle = null,
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.CPU_DRAW_CALL)
        assertThat(verdict.confidence).isEqualTo(BenchBottleneck.BottleneckVerdict.Confidence.HIGH)
    }

    // ── Rule 3: GPU-bound ────────────────────────────────────────────────

    @Test
    fun `rule3 fires when cpu usage is below 40 percent`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 18, gpuFps = 90.0),
            throttle = null,
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.GPU_BOUND)
        assertThat(verdict.confidence).isEqualTo(BenchBottleneck.BottleneckVerdict.Confidence.HIGH)
    }

    @Test
    fun `rule3 does not fire when cpu usage is 40 percent exactly`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 40),
            throttle = null,
        )
        // 40 is not < 40, falls to BALANCED
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.BALANCED)
    }

    // ── Rule 4: Memory-bound ─────────────────────────────────────────────

    @Test
    fun `rule4 fires when memory bandwidth is low and no gpu data`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(memoryBandwidthMBps = 10_000.0),
            throttle = null,
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.MEMORY_BOUND)
    }

    @Test
    fun `rule4 does not fire when bandwidth is above threshold`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(memoryBandwidthMBps = 50_000.0),
            throttle = null,
        )
        // No gpu data → BALANCED
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.BALANCED)
    }

    // ── Rule 5: Balanced fallback ────────────────────────────────────────

    @Test
    fun `rule5 balanced when no data`() {
        val verdict = BenchBottleneck.diagnose(kernels = kernels(), throttle = null)
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.BALANCED)
        assertThat(verdict.confidence).isEqualTo(BenchBottleneck.BottleneckVerdict.Confidence.LOW)
    }

    @Test
    fun `rule5 balanced when cpu usage is mid-range 45 percent`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 45, gpuFps = 70.0),
            throttle = null,
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.BALANCED)
    }

    // ── Priority: thermal overrides CPU rule ────────────────────────────

    @Test
    fun `thermal takes priority over cpu limited`() {
        val verdict = BenchBottleneck.diagnose(
            kernels = kernels(cpuUsageDuringGpuPct = 80),   // would fire CPU rule
            throttle = throttle(dropPct = 20.0),             // fires thermal first
        )
        assertThat(verdict.type).isEqualTo(BenchBottleneck.BottleneckType.THERMAL_THROTTLE)
    }

    // ── Verdict fields are non-empty ─────────────────────────────────────

    @Test
    fun `all verdict fields are non-blank`() {
        val types = listOf(
            BenchBottleneck.diagnose(kernels(cpuUsageDuringGpuPct = 10), null),
            BenchBottleneck.diagnose(kernels(cpuUsageDuringGpuPct = 80), null),
            BenchBottleneck.diagnose(kernels(), throttle(dropPct = 20.0)),
            BenchBottleneck.diagnose(kernels(memoryBandwidthMBps = 5000.0), null),
            BenchBottleneck.diagnose(kernels(), null),
        )
        types.forEach { v ->
            assertThat(v.headline).isNotEmpty()
            assertThat(v.detail).isNotEmpty()
            assertThat(v.knob).isNotEmpty()
        }
    }
}
