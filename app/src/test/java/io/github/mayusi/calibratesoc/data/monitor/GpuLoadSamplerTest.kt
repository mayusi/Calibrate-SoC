package io.github.mayusi.calibratesoc.data.monitor

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegedSysfsReader
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

/**
 * Pure-JVM tests for [GpuLoadSampler] — the GPU clock + busy% sampler and, in
 * particular, the cross-device privileged-read fallback (RP6/Odin kgsl SELinux
 * denial fix).
 *
 * Honesty invariants covered:
 *  - GPU freq + load read DIRECTLY when the app UID can read kgsl (most devices).
 *  - When the direct app-UID read is denied (modelled by an absent node in the
 *    FakeFileSystem), the sampler FALLS BACK to the privileged PServer `cat` and
 *    surfaces the real value — exactly like AutoTDP's GPU-devfreq probe already does.
 *  - When BOTH the direct read AND the privileged read fail (no PServer / genuinely
 *    unreadable), the value stays null → the HUD renders "--" (never a fake 0).
 *  - The read-source capability is probed ONCE per node and cached, so the hot
 *    1 Hz path never re-issues a doomed direct open() or an unnecessary root cat.
 */
class GpuLoadSamplerTest {

    private val adrenoRoot = "/sys/class/kgsl/kgsl-3d0"

    private fun probe() = GpuProbe(
        family = GpuFamily.ADRENO,
        rootPath = adrenoRoot,
        availableFreqsHz = emptyList(),
        availableGovernors = emptyList(),
        currentMinHz = 0L,
        currentMaxHz = 0L,
        currentGovernor = "",
        powerLevelRange = null,
    )

    /**
     * Counting test double for [PrivilegedSysfsReader]: returns a value only for
     * nodes the privileged context can read, and records how many times each path
     * was queried so we can assert the probe-once caching.
     */
    private class CountingPrivilegedReader(
        private val values: Map<String, String>,
    ) : PrivilegedSysfsReader(pServerWriter = null) {
        val calls = mutableMapOf<String, Int>()
        override fun catOrNull(path: String): String? {
            calls[path] = (calls[path] ?: 0) + 1
            return values[path]
        }
    }

    private fun write(fs: FakeFileSystem, path: String, contents: String) {
        val p = path.toPath()
        p.parent?.let { fs.createDirectories(it) }
        fs.write(p) { writeUtf8(contents) }
    }

    // ── direct path (app UID can read kgsl) ──────────────────────────────────

    @Test
    fun `reads freq and load directly when app UID can read kgsl`() {
        val fs = FakeFileSystem()
        write(fs, "$adrenoRoot/gpubusy_percentage", "39")
        write(fs, "$adrenoRoot/devfreq/cur_freq", "670000000")
        val priv = CountingPrivilegedReader(emptyMap())

        val r = GpuLoadSampler(fs, priv).sample(probe())

        assertThat(r.loadPct).isEqualTo(39)
        assertThat(r.freqHz).isEqualTo(670_000_000L)
        // Direct read succeeded → the privileged reader must never be consulted.
        assertThat(priv.calls).isEmpty()
    }

    // ── privileged fallback (RP6/Odin kgsl SELinux denial) ───────────────────

    @Test
    fun `falls back to privileged reader for freq and load when direct read is denied`() {
        // Empty FakeFileSystem models the app-UID EACCES: the app's own open()
        // sees nothing. PServer-root, however, CAN cat the nodes.
        val fs = FakeFileSystem()
        val priv = CountingPrivilegedReader(
            mapOf(
                "$adrenoRoot/gpu_busy_percentage" to "39",
                "$adrenoRoot/devfreq/cur_freq" to "670000000",
            ),
        )

        val r = GpuLoadSampler(fs, priv).sample(probe())

        assertThat(r.loadPct).isEqualTo(39)
        assertThat(r.freqHz).isEqualTo(670_000_000L)
    }

    @Test
    fun `stays null when neither direct nor privileged read works (honest dash)`() {
        // No node on disk AND a privileged reader that can read nothing — exactly
        // the RP6-without-PServer case. Honest absence: null → HUD shows "--".
        val fs = FakeFileSystem()
        val priv = CountingPrivilegedReader(emptyMap())

        val r = GpuLoadSampler(fs, priv).sample(probe())

        assertThat(r.loadPct).isNull()
        assertThat(r.freqHz).isNull()
    }

    @Test
    fun `with no privileged reader injected a denied read stays null (never fabricated)`() {
        // Test-construction site GpuLoadSampler(fs) — privileged reader defaults to
        // null. A denied/absent node must stay null, never a fake 0.
        val fs = FakeFileSystem()

        val r = GpuLoadSampler(fs).sample(probe())

        assertThat(r.loadPct).isNull()
        assertThat(r.freqHz).isNull()
    }

    // ── probe-once caching cadence ───────────────────────────────────────────

    @Test
    fun `privileged read source is probed once then reused on subsequent ticks`() {
        val fs = FakeFileSystem()
        val freqNode = "$adrenoRoot/devfreq/cur_freq"
        val priv = CountingPrivilegedReader(mapOf(freqNode to "585000000"))
        val sampler = GpuLoadSampler(fs, priv)

        // Five ticks (mirrors the 1 Hz hot path).
        repeat(5) { assertThat(sampler.sample(probe()).freqHz).isEqualTo(585_000_000L) }

        // The freq node resolved to PRIVILEGED on tick 1 and was reused thereafter:
        // exactly one privileged cat per tick (no extra probing), and crucially the
        // gpubusy_percentage node — which catOrNull cannot read — is probed ONCE and
        // then cached UNAVAILABLE so it is never cat-ed again.
        assertThat(priv.calls[freqNode]).isEqualTo(5)
        assertThat(priv.calls["$adrenoRoot/gpubusy_percentage"]).isEqualTo(1)
    }

    @Test
    fun `a directly-readable node never escalates to the privileged reader on later ticks`() {
        val fs = FakeFileSystem()
        write(fs, "$adrenoRoot/devfreq/cur_freq", "670000000")
        // gpubusy_percentage is directly readable too, so load never needs privileged.
        write(fs, "$adrenoRoot/gpubusy_percentage", "42")
        val priv = CountingPrivilegedReader(emptyMap())
        val sampler = GpuLoadSampler(fs, priv)

        repeat(3) {
            val r = sampler.sample(probe())
            assertThat(r.freqHz).isEqualTo(670_000_000L)
            assertThat(r.loadPct).isEqualTo(42)
        }
        // Every node read directly → privileged reader never touched across all ticks.
        assertThat(priv.calls).isEmpty()
    }

    @Test
    fun `null probe yields null result with no reads`() {
        val fs = FakeFileSystem()
        val priv = CountingPrivilegedReader(emptyMap())
        val r = GpuLoadSampler(fs, priv).sample(null)
        assertThat(r.loadPct).isNull()
        assertThat(r.freqHz).isNull()
        assertThat(priv.calls).isEmpty()
    }

    // ── busy% parsing honesty (RP6 negative-sentinel / range gate) ───────────────

    @Test
    fun `busy percent parses a plain integer and a trailing-percent variant`() {
        val fs = FakeFileSystem()
        write(fs, "$adrenoRoot/gpubusy_percentage", "42")
        assertThat(GpuLoadSampler(fs).sample(probe()).loadPct).isEqualTo(42)

        val fs2 = FakeFileSystem()
        write(fs2, "$adrenoRoot/gpubusy_percentage", "7 %")
        assertThat(GpuLoadSampler(fs2).sample(probe()).loadPct).isEqualTo(7)
    }

    @Test
    fun `busy percent parses the two-number ratio format busy total`() {
        // Some kgsl/devfreq nodes emit "busy total" — e.g. "53 100" = 53%.
        val fs = FakeFileSystem()
        write(fs, "$adrenoRoot/gpubusy_percentage", "53 100")
        assertThat(GpuLoadSampler(fs).sample(probe()).loadPct).isEqualTo(53)

        // 18 / 24 ≈ 75%.
        val fs2 = FakeFileSystem()
        write(fs2, "$adrenoRoot/gpubusy_percentage", "18 24")
        assertThat(GpuLoadSampler(fs2).sample(probe()).loadPct).isEqualTo(75)
    }

    @Test
    fun `negative busy percent is rejected to null (never a wrong -53 percent)`() {
        // RP6: the SELinux-permissive-logged node can yield a negative sentinel.
        // A busy% is physically 0..100, so a negative reading is dishonest → null
        // (HUD shows "--"), NEVER an impossible "-53%".
        val fs = FakeFileSystem()
        write(fs, "$adrenoRoot/gpubusy_percentage", "-53")
        assertThat(GpuLoadSampler(fs).sample(probe()).loadPct).isNull()
    }

    @Test
    fun `out-of-range and garbage busy percent are rejected to null`() {
        val over = FakeFileSystem()
        write(over, "$adrenoRoot/gpubusy_percentage", "150")
        assertThat(GpuLoadSampler(over).sample(probe()).loadPct).isNull()

        val garbage = FakeFileSystem()
        write(garbage, "$adrenoRoot/gpubusy_percentage", "n/a")
        assertThat(GpuLoadSampler(garbage).sample(probe()).loadPct).isNull()
    }

    @Test
    fun `a genuine zero busy percent is kept (idle GPU is honest)`() {
        // 0 is a VALID reading (idle GPU) — it must not be confused with "unreadable".
        val fs = FakeFileSystem()
        write(fs, "$adrenoRoot/gpubusy_percentage", "0")
        assertThat(GpuLoadSampler(fs).sample(probe()).loadPct).isEqualTo(0)
    }

    @Test
    fun `boundary busy percent values 1 and 100 are kept`() {
        val one = FakeFileSystem()
        write(one, "$adrenoRoot/gpubusy_percentage", "1")
        assertThat(GpuLoadSampler(one).sample(probe()).loadPct).isEqualTo(1)

        val full = FakeFileSystem()
        write(full, "$adrenoRoot/gpubusy_percentage", "100")
        assertThat(GpuLoadSampler(full).sample(probe()).loadPct).isEqualTo(100)
    }
}
