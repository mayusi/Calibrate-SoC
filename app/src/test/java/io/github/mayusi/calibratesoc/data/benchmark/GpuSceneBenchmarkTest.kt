package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for pure-JVM logic in [GpuSceneBenchmark] and [GpuSceneResult].
 *
 * NOTE: The GL rendering path ([GpuSceneBenchmark.run]) requires a live GLES
 * context and cannot be tested on the JVM. These tests cover the parts that
 * CAN be tested without a GPU:
 *   - Procedural sphere geometry generation ([GpuSceneBenchmark.buildSphere])
 *   - Tier resolution mapping ([SceneTier])
 *   - Stability% computation ([GpuSceneResult.computeStabilityPct])
 */
class GpuSceneBenchmarkTest {

    // ─── Geometry tests ────────────────────────────────────────────────────────

    @Test
    fun `buildSphere produces correct vertex count`() {
        // A UV-sphere with latDiv latitude and lonDiv longitude divisions has
        // (latDiv+1) * (lonDiv+1) vertices.
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 4, lonDiv = 6)
        val expectedVerts = (4 + 1) * (6 + 1)
        // vertexData has 8 floats per vertex.
        assertThat(sphere.vertexData.capacity()).isEqualTo(expectedVerts * 8)
    }

    @Test
    fun `buildSphere produces correct index count`() {
        // latDiv * lonDiv quads, each 2 triangles, each 3 indices.
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 4, lonDiv = 6)
        val expectedIdx = 4 * 6 * 6  // 144
        assertThat(sphere.indexCount).isEqualTo(expectedIdx)
        assertThat(sphere.indexData.capacity()).isEqualTo(expectedIdx)
    }

    @Test
    fun `buildSphere first vertex is top pole (0,1,0)`() {
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 6, lonDiv = 8)
        // First vertex: lat=0, lon=0 → theta=0, phi=0 → (sin0·cos0, cos0, sin0·sin0) = (0,1,0)
        val data = sphere.vertexData
        assertThat(data[0]).isWithin(0.001f).of(0f)   // x
        assertThat(data[1]).isWithin(0.001f).of(1f)   // y
        assertThat(data[2]).isWithin(0.001f).of(0f)   // z
    }

    @Test
    fun `buildSphere normals equal positions on unit sphere`() {
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 6, lonDiv = 6)
        val data = sphere.vertexData
        // Each vertex is 8 floats: pos(0-2), normal(3-5), uv(6-7).
        var allMatch = true
        for (v in 0 until (sphere.vertexData.capacity() / 8)) {
            val base = v * 8
            val px = data[base]; val py = data[base + 1]; val pz = data[base + 2]
            val nx = data[base + 3]; val ny = data[base + 4]; val nz = data[base + 5]
            if (Math.abs(px - nx) > 0.001f || Math.abs(py - ny) > 0.001f || Math.abs(pz - nz) > 0.001f) {
                allMatch = false
                break
            }
        }
        assertThat(allMatch).isTrue()
    }

    @Test
    fun `buildSphere all position vectors lie on unit sphere`() {
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 10, lonDiv = 10)
        val data = sphere.vertexData
        var allOnSphere = true
        for (v in 0 until (sphere.vertexData.capacity() / 8)) {
            val base = v * 8
            val px = data[base].toDouble()
            val py = data[base + 1].toDouble()
            val pz = data[base + 2].toDouble()
            val len = Math.sqrt(px * px + py * py + pz * pz)
            if (Math.abs(len - 1.0) > 0.002) {
                allOnSphere = false
                break
            }
        }
        assertThat(allOnSphere).isTrue()
    }

    @Test
    fun `buildSphere indices are in valid range`() {
        val bench = makeBenchmark()
        val latDiv = 5; val lonDiv = 5
        val sphere = bench.buildSphere(latDiv, lonDiv)
        val maxIdx = (latDiv + 1) * (lonDiv + 1) - 1
        val idxBuf = sphere.indexData
        for (i in 0 until idxBuf.capacity()) {
            val idx = idxBuf[i]
            assertThat(idx).isAtLeast(0)
            assertThat(idx).isAtMost(maxIdx)
        }
    }

    // ─── SceneTier resolution mapping ─────────────────────────────────────────

    @Test
    fun `STANDARD tier is 1920x1080`() {
        assertThat(SceneTier.STANDARD.width).isEqualTo(1920)
        assertThat(SceneTier.STANDARD.height).isEqualTo(1080)
    }

    @Test
    fun `EXTREME tier is 2560x1440`() {
        assertThat(SceneTier.EXTREME.width).isEqualTo(2560)
        assertThat(SceneTier.EXTREME.height).isEqualTo(1440)
    }

    @Test
    fun `ULTRA tier is 3840x2160`() {
        assertThat(SceneTier.ULTRA.width).isEqualTo(3840)
        assertThat(SceneTier.ULTRA.height).isEqualTo(2160)
    }

    @Test
    fun `tier labels are non-empty`() {
        for (tier in SceneTier.entries) {
            assertThat(tier.label).isNotEmpty()
        }
    }

    // ─── Stability% computation ────────────────────────────────────────────────

    @Test
    fun `computeStabilityPct with single loop returns null`() {
        assertThat(GpuSceneResult.computeStabilityPct(listOf(60.0))).isNull()
    }

    @Test
    fun `computeStabilityPct with empty list returns null`() {
        assertThat(GpuSceneResult.computeStabilityPct(emptyList())).isNull()
    }

    @Test
    fun `computeStabilityPct with flat fps returns 100`() {
        val fps = listOf(60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0)
        assertThat(GpuSceneResult.computeStabilityPct(fps)).isEqualTo(100)
    }

    @Test
    fun `computeStabilityPct with drooping fps shows degradation`() {
        // Same pattern as StabilityResultTest: [120, 115, 100, 90, 80, 70, 65, 60]
        // Last 25% (2 loops) = [65, 60]. avg=62.5. peak=120. 62.5/120*100 = 52.
        val fps = listOf(120.0, 115.0, 100.0, 90.0, 80.0, 70.0, 65.0, 60.0)
        assertThat(GpuSceneResult.computeStabilityPct(fps)).isEqualTo(52)
    }

    @Test
    fun `computeStabilityPct result is clamped 0 to 100`() {
        val pct = GpuSceneResult.computeStabilityPct(listOf(100.0, 10.0))
        assertThat(pct).isNotNull()
        assertThat(pct!!).isIn(0..100)
    }

    @Test
    fun `computeStabilityPct uses same SUSTAINED_WINDOW_RATIO as StabilityResult`() {
        // 4 loops [60, 55, 45, 40]. Last 25% = [40]. 40/60=66%.
        val fps = listOf(60.0, 55.0, 45.0, 40.0)
        // Must match StabilityResult.compute exactly.
        val expected = StabilityResult.compute(fps)
        assertThat(GpuSceneResult.computeStabilityPct(fps)).isEqualTo(expected)
    }

    @Test
    fun `computeStabilityPct zero peak returns null`() {
        assertThat(GpuSceneResult.computeStabilityPct(listOf(0.0, 0.0))).isNull()
    }

    // ─── HONESTY_CAPTION ──────────────────────────────────────────────────────

    @Test
    fun `honesty caption contains compare your own runs`() {
        assertThat(GpuSceneResult.HONESTY_CAPTION)
            .contains("compare your own runs")
    }

    // ─── Triangle count estimation ─────────────────────────────────────────────

    @Test
    fun `sphere geometry gives triangles in expected range`() {
        // 60x60 sphere: latDiv*lonDiv*2 triangles = 60*60*2 = 7200 per instance.
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 60, lonDiv = 60)
        val triCount = sphere.indexCount / 3
        assertThat(triCount).isEqualTo(60 * 60 * 2)
    }

    @Test
    fun `GLES3 instance grid gives at least 200k triangles per frame`() {
        // 11×11 = 121 instances × (60×60×2) = 121 × 7200 = 871,200 tri.
        val bench = makeBenchmark()
        val sphere = bench.buildSphere(latDiv = 60, lonDiv = 60)
        val triPerInstance = sphere.indexCount / 3
        val grid = 11  // INSTANCE_GRID_GLES3 constant
        val total = triPerInstance.toLong() * grid * grid
        assertThat(total).isGreaterThan(200_000L)
        assertThat(total).isAtMost(1_500_000L)  // sanity upper bound
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Create a [GpuSceneBenchmark] with a stub [io.github.mayusi.calibratesoc.data.monitor.MonitorService].
     * Only [buildSphere] is tested — no EGL/GL calls are made in these tests.
     */
    private fun makeBenchmark(): GpuSceneBenchmark {
        // MonitorService is only used in run() (GL path). A simple mock stub suffices;
        // we use io.mockk.mockk lazily so the import only pulls mockk for test.
        val monitorService = io.mockk.mockk<io.github.mayusi.calibratesoc.data.monitor.MonitorService>(relaxed = true)
        return GpuSceneBenchmark(monitorService)
    }
}
