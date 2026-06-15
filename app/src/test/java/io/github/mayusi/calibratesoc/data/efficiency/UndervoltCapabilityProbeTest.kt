package io.github.mayusi.calibratesoc.data.efficiency

import com.google.common.truth.Truth.assertThat
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [UndervoltCapabilityProbe] and [UndervoltCapability].
 *
 * Uses [FakeFileSystem] — no real sysfs, no Android runtime, pure JVM.
 *
 * Key assertions:
 *  - A path that doesn't exist → exists=false, readable=false, writable=false.
 *  - A path that exists and is readable → readable=true.
 *  - A path that exists but can't be read → readable=false (simulated by
 *    not writing content + probe returning empty note).
 *  - Tier resolution: no writable volt table + freqCapWritable → KNEE_EQUIVALENT.
 *  - Tier resolution: writable volt table → REAL_VOLTAGE_TABLE.
 *  - Tier resolution: no volt table + no freq cap → READ_ONLY.
 *  - [UndervoltCapability.fromProbeResults] correctly aggregates cpu/gpu flags.
 */
class UndervoltCapabilityProbeTest {

    private lateinit var fs: FakeFileSystem
    private lateinit var probe: UndervoltCapabilityProbe

    @Before
    fun setUp() {
        fs = FakeFileSystem()
        probe = UndervoltCapabilityProbe(fs)
    }

    @After
    fun tearDown() {
        fs.checkNoOpenFiles()
    }

    // ─── PathProbeResult via probePath ────────────────────────────────────────

    @Test
    fun `probePath - non-existent path returns exists false`() {
        val result = probe.probePath("/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table",
            PathRole.CPU_VOLT_TABLE)

        assertThat(result.exists).isFalse()
        assertThat(result.readable).isFalse()
        assertThat(result.writable).isFalse()
        assertThat(result.note).contains("not found")
    }

    @Test
    fun `probePath - existing path with content is readable`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table"
        // Create the file with plausible UV_mV_table content.
        fs.createDirectories(path.toPath().parent!!)
        fs.write(path.toPath()) { writeUtf8("1804800 900\n1516800 860\n1209600 820\n") }

        val result = probe.probePath(path, PathRole.CPU_VOLT_TABLE)

        assertThat(result.exists).isTrue()
        assertThat(result.readable).isTrue()
        // On FakeFileSystem canWrite() may return false (no real OS permission bits).
        // That is fine — writable depends on real device permissions; the test just
        // verifies we reach the "readable" branch.
        assertThat(result.role).isEqualTo(PathRole.CPU_VOLT_TABLE)
        assertThat(result.path).isEqualTo(path)
    }

    @Test
    fun `probePath - path exists but content is blank returns readable false`() {
        val path = "/sys/class/kgsl/kgsl-3d0/vdd_levels"
        fs.createDirectories(path.toPath().parent!!)
        // Write only whitespace — simulates a kernel file that exists but returns nothing.
        fs.write(path.toPath()) { writeUtf8("   \n  ") }

        val result = probe.probePath(path, PathRole.GPU_VOLT_TABLE)

        assertThat(result.exists).isTrue()
        assertThat(result.readable).isFalse()
        assertThat(result.writable).isFalse()
        assertThat(result.note).contains("empty")
    }

    @Test
    fun `probePath - GPU role is set correctly`() {
        val path = "/sys/class/kgsl/kgsl-3d0/vdd_levels"
        fs.createDirectories(path.toPath().parent!!)
        fs.write(path.toPath()) { writeUtf8("598000000 688000\n490000000 644000\n") }

        val result = probe.probePath(path, PathRole.GPU_VOLT_TABLE)

        assertThat(result.role).isEqualTo(PathRole.GPU_VOLT_TABLE)
        assertThat(result.readable).isTrue()
    }

    // ─── UndervoltCapability.fromProbeResults (pure parsing) ─────────────────

    @Test
    fun `fromProbeResults - no readable paths, no freq cap = READ_ONLY`() {
        val results = listOf(
            PathProbeResult("/some/cpu_path", PathRole.CPU_VOLT_TABLE,
                exists = false, readable = false, writable = false),
            PathProbeResult("/some/gpu_path", PathRole.GPU_VOLT_TABLE,
                exists = false, readable = false, writable = false),
        )

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = false)

        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.READ_ONLY)
        assertThat(cap.cpuVoltTablePresent).isFalse()
        assertThat(cap.cpuVoltTableWritable).isFalse()
        assertThat(cap.gpuVoltTablePresent).isFalse()
        assertThat(cap.gpuVoltTableWritable).isFalse()
        assertThat(cap.freqCapWritable).isFalse()
    }

    @Test
    fun `fromProbeResults - readable cpu path but not writable, freqCap available = KNEE_EQUIVALENT`() {
        // This is the stock Odin 3 / Retroid Pocket 6 scenario:
        // volt table may be present (some debug builds expose it readable)
        // but is NOT writable (signed firmware). freqCap IS available via PServer.
        val results = listOf(
            PathProbeResult("/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table",
                PathRole.CPU_VOLT_TABLE,
                exists = true, readable = true, writable = false,
                note = "read-only (stock firmware)"),
        )

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = true)

        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.KNEE_EQUIVALENT)
        assertThat(cap.cpuVoltTablePresent).isTrue()
        assertThat(cap.cpuVoltTableWritable).isFalse()
        assertThat(cap.freqCapWritable).isTrue()
    }

    @Test
    fun `fromProbeResults - no volt table anywhere, freqCap writable = KNEE_EQUIVALENT`() {
        // Most common real-world stock case: no volt table, PServer/Shizuku allows freq cap.
        val results = listOf(
            PathProbeResult("/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table",
                PathRole.CPU_VOLT_TABLE,
                exists = false, readable = false, writable = false, note = "not found"),
            PathProbeResult("/sys/class/kgsl/kgsl-3d0/vdd_levels",
                PathRole.GPU_VOLT_TABLE,
                exists = false, readable = false, writable = false, note = "not found"),
        )

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = true)

        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.KNEE_EQUIVALENT)
        assertThat(cap.cpuVoltTablePresent).isFalse()
        assertThat(cap.gpuVoltTablePresent).isFalse()
    }

    @Test
    fun `fromProbeResults - writable cpu volt table = REAL_VOLTAGE_TABLE`() {
        // Rare: rooted device with custom kernel (Sultan, NetHunter, etc.)
        val results = listOf(
            PathProbeResult("/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table",
                PathRole.CPU_VOLT_TABLE,
                exists = true, readable = true, writable = true,
                note = "readable + writable"),
        )

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = true)

        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.REAL_VOLTAGE_TABLE)
        assertThat(cap.cpuVoltTablePresent).isTrue()
        assertThat(cap.cpuVoltTableWritable).isTrue()
        assertThat(cap.gpuVoltTableWritable).isFalse()
    }

    @Test
    fun `fromProbeResults - writable gpu volt table = REAL_VOLTAGE_TABLE`() {
        val results = listOf(
            PathProbeResult("/sys/class/kgsl/kgsl-3d0/vdd_levels",
                PathRole.GPU_VOLT_TABLE,
                exists = true, readable = true, writable = true,
                note = "readable + writable"),
        )

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = false)

        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.REAL_VOLTAGE_TABLE)
        assertThat(cap.gpuVoltTablePresent).isTrue()
        assertThat(cap.gpuVoltTableWritable).isTrue()
        assertThat(cap.cpuVoltTableWritable).isFalse()
    }

    @Test
    fun `fromProbeResults - multiple cpu paths, only one present = cpuVoltTablePresent true`() {
        val results = listOf(
            PathProbeResult("/sys/devices/system/cpu/cpufreq/policy0/UV_mV_table",
                PathRole.CPU_VOLT_TABLE,
                exists = false, readable = false, writable = false),
            PathProbeResult("/sys/devices/system/cpu/cpufreq/policy4/UV_mV_table",
                PathRole.CPU_VOLT_TABLE,
                exists = true, readable = true, writable = false),
        )

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = true)

        assertThat(cap.cpuVoltTablePresent).isTrue()
        assertThat(cap.cpuVoltTableWritable).isFalse()
        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.KNEE_EQUIVALENT)
    }

    // ─── Tier resolution summary ──────────────────────────────────────────────

    @Test
    fun `tier enum ordering is correct - REAL_VOLTAGE_TABLE is not KNEE_EQUIVALENT`() {
        assertThat(UndervoltCapabilityTier.REAL_VOLTAGE_TABLE)
            .isNotEqualTo(UndervoltCapabilityTier.KNEE_EQUIVALENT)
        assertThat(UndervoltCapabilityTier.KNEE_EQUIVALENT)
            .isNotEqualTo(UndervoltCapabilityTier.READ_ONLY)
    }

    @Test
    fun `stock Odin 3 scenario - no volt table, pserver live = KNEE_EQUIVALENT`() {
        // Simulate: all volt table paths absent (stock Snapdragon 8 Gen 2 firmware),
        // but freq cap is writable via AYN PServer.
        val results = UndervoltCapabilityProbe.CPU_VOLT_TABLE_PATHS.map { path ->
            PathProbeResult(path, PathRole.CPU_VOLT_TABLE,
                exists = false, readable = false, writable = false, note = "not found")
        } + UndervoltCapabilityProbe.GPU_VOLT_TABLE_PATHS.map { path ->
            PathProbeResult(path, PathRole.GPU_VOLT_TABLE,
                exists = false, readable = false, writable = false, note = "not found")
        }

        val cap = UndervoltCapability.fromProbeResults(results, freqCapWritable = true)

        assertThat(cap.tier).isEqualTo(UndervoltCapabilityTier.KNEE_EQUIVALENT)
        assertThat(cap.cpuVoltTableWritable).isFalse()
        assertThat(cap.gpuVoltTableWritable).isFalse()
        // Honesty: probeDetails carries the full list so the UI can show "why"
        assertThat(cap.probeDetails).hasSize(results.size)
    }
}
