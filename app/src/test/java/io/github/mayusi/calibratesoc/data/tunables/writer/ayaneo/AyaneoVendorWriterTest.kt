package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the AYANEO vendor adapter writer.
 *
 * Coverage (matches the build spec's VERIFY list):
 *  - Each driven tunable maps to the EXACT AIDL command string (incl. kHz→Hz).
 *  - Readback-verify: moved → Applied, not-moved → Rejected, unreadable → Unverified.
 *  - Non-bindable tunables (cpu/online etc.) → CapabilityDenied (honest deny).
 *
 * The binder client and capability report are MOCKED — no device, no IPC. The sysfs
 * readback uses an Okio FakeFileSystem so the verify path is exercised deterministically.
 */
class AyaneoVendorWriterTest {

    @Before
    fun stubLog() {
        // android.util.Log is unavailable in the pure-JVM unit-test runtime. The UNVERIFIED
        // and READBACK-MISMATCH paths emit Log.w, so stub the static here so these tests are
        // self-contained rather than free-riding on another class's leaked Log mock (which
        // makes them fail when run in isolation / under test sharding).
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private val pocketDsPolicies = listOf(
        CpuPolicyProbe(
            policyId = 0,
            onlineCores = listOf(0, 1, 2),
            availableFreqsKhz = listOf(300_000, 2_016_000),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 300_000,
            currentMaxKhz = 2_016_000,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(300_000, 2_016_000),
        ),
        CpuPolicyProbe(
            policyId = 3,
            onlineCores = listOf(3, 4, 5, 6),
            availableFreqsKhz = listOf(710_400, 2_803_200),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 710_400,
            currentMaxKhz = 2_803_200,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(710_400, 2_803_200),
        ),
        CpuPolicyProbe(
            policyId = 7,
            onlineCores = listOf(7),
            availableFreqsKhz = listOf(844_800, 3_187_200),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 844_800,
            currentMaxKhz = 3_187_200,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(844_800, 3_187_200),
        ),
    )

    private fun report() = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYANEO", brand = "AYANEO", model = "Pocket DS",
            device = "PocketDS", hardware = "kalama",
            androidVersion = "13", sdkInt = 33, knownHandheldKey = "ayaneo_pocket_ds",
        ),
        soc = SoCIdentity("QTI", "SG8275", GpuFamily.ADRENO),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = pocketDsPolicies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, ayaSpace = true, retroidGameAssistant = false),
        ayaneoBinderLive = true,
    )

    /** Build a writer over a mocked binder + report, with [fs] preloaded readback files. */
    private fun writerWith(
        binder: AyaneoBinderClient,
        fs: FakeFileSystem = FakeFileSystem(),
    ): AyaneoVendorWriter {
        val probe = mockk<CapabilityProbe>()
        every { probe.report } returns MutableStateFlow(report())
        return AyaneoVendorWriter(binder, probe, fs)
    }

    /** A binder mock that accepts every command and captures the last payload sent. */
    private fun acceptingBinder(captured: MutableList<String>): AyaneoBinderClient {
        val b = mockk<AyaneoBinderClient>()
        val slot = slot<String>()
        coEvery { b.isAvailable() } returns true
        coEvery { b.sendCommand(capture(slot)) } coAnswers {
            captured.add(slot.captured); true
        }
        return b
    }

    private fun seed(fs: FakeFileSystem, path: String, value: String) {
        val p = path.toPath()
        p.parent?.let { fs.createDirectories(it) }
        fs.write(p) { writeUtf8(value) }
    }

    // ── 1. Command mapping: CPU cluster cap (kHz → Hz) ──────────────────────────

    @Test
    fun `cpu max freq maps to com_set_performance_cpu with kHz converted to Hz`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.cpuMaxFreq(7) // prime cluster (policy7) → repCpu 7
        // Seed a readback that matches so the verify passes (value in kHz).
        seed(fs, id.target, "2419200")
        val writer = writerWith(acceptingBinder(sent), fs)

        val result = writer.write(id, "2419200")

        // policy7's representative core is its first online core = 7; 2419200 kHz → 2419200000 Hz.
        assertThat(sent).containsExactly(
            "calibrate:msg_type_performance:com_set_performance_cpu:7_2419200000"
        )
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
    }

    @Test
    fun `cpu max freq on policy0 uses cpu0 as representative core`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.cpuMaxFreq(0)
        seed(fs, id.target, "1516800")
        val writer = writerWith(acceptingBinder(sent), fs)

        writer.write(id, "1516800")

        // policy0's first online core is 0; 1516800 kHz → 1516800000 Hz.
        assertThat(sent.single()).isEqualTo(
            "calibrate:msg_type_performance:com_set_performance_cpu:0_1516800000"
        )
    }

    // ── 2. Command mapping: governor → scheduler token ──────────────────────────

    @Test
    fun `governor schedutil maps to scheduler BALANCED`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.cpuGovernor(7)
        seed(fs, id.target, "schedutil")
        val writer = writerWith(acceptingBinder(sent), fs)

        // value written is the governor; verify uses ExactString so seed must equal value.
        seed(fs, id.target, "schedutil")
        writer.write(id, "schedutil")

        assertThat(sent.single()).isEqualTo(
            "calibrate:msg_type_performance:com_set_performance_scheduler:BALANCED"
        )
    }

    @Test
    fun `governor performance maps to HIGH_PERFORMANCE, powersave to POWER_SAVING`() = runTest {
        val sentPerf = mutableListOf<String>()
        val fsPerf = FakeFileSystem()
        val govId = Tunables.cpuGovernor(3)
        seed(fsPerf, govId.target, "performance")
        writerWith(acceptingBinder(sentPerf), fsPerf).write(govId, "performance")
        assertThat(sentPerf.single()).endsWith("com_set_performance_scheduler:HIGH_PERFORMANCE")

        val sentSave = mutableListOf<String>()
        val fsSave = FakeFileSystem()
        seed(fsSave, govId.target, "powersave")
        writerWith(acceptingBinder(sentSave), fsSave).write(govId, "powersave")
        assertThat(sentSave.single()).endsWith("com_set_performance_scheduler:POWER_SAVING")
    }

    // ── 3. Command mapping: GPU max (Hz, real verification) ──────────────────────

    @Test
    fun `gpu devfreq max maps to com_set_performance_gpu in Hz and verifies via readback`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")
        // The kgsl devfreq max_freq node is app-readable (verified live) — seed the moved value.
        seed(fs, id.target, "585000000")
        val writer = writerWith(acceptingBinder(sent), fs)

        val result = writer.write(id, "585000000")

        assertThat(sent.single()).isEqualTo(
            "calibrate:msg_type_performance:com_set_performance_gpu:585000000"
        )
        // Readback (585000000) matches intended → Applied with the verified new value.
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat((result as WriteResult.Success).newValue).isEqualTo("585000000")
    }

    // ── 4. Readback-verify: moved → Applied, not-moved → Rejected, unreadable → Unverified

    @Test
    fun `gpu readback that did not move yields Rejected`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")
        // The node still reads the stock 680 MHz — the write didn't land. Honest Rejected.
        seed(fs, id.target, "680000000")
        val writer = writerWith(acceptingBinder(sent), fs)

        val result = writer.write(id, "585000000")

        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
        assertThat((result as WriteResult.Rejected).message).contains("680000000")
    }

    @Test
    fun `unreadable cpu node yields Success-unverified (never faked verified)`() = runTest {
        val sent = mutableListOf<String>()
        // No file seeded → readback is null → UNVERIFIED accept-but-warn.
        val fs = FakeFileSystem()
        val id = Tunables.cpuMaxFreq(7)
        val writer = writerWith(acceptingBinder(sent), fs)

        val result = writer.write(id, "2419200")

        // Command still goes out; result is Success (so AutoTDP proceeds) but newValue is
        // the INTENDED value, not a verified readback — the honest "unverified" signal.
        assertThat(sent.single()).endsWith("com_set_performance_cpu:7_2419200000")
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat((result as WriteResult.Success).newValue).isEqualTo("2419200")
    }

    @Test
    fun `gpu readback OPP-snapped neighbor within tolerance is Applied`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")
        // Intended 585 MHz; kernel snapped to the nearest OPP 587 MHz (~0.3% < 3% tol).
        seed(fs, id.target, "587000000")
        val writer = writerWith(acceptingBinder(sent), fs)

        val result = writer.write(id, "585000000")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
    }

    // ── 5. Binder rejected the send → Failed (transient) ────────────────────────

    @Test
    fun `binder that rejects the command yields Failed`() = runTest {
        val fs = FakeFileSystem()
        val id = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")
        seed(fs, id.target, "680000000")
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns false
        val writer = writerWith(binder, fs)

        val result = writer.write(id, "585000000")

        assertThat(result).isInstanceOf(WriteResult.Failed::class.java)
    }

    // ── 6. Non-bindable tunables → CapabilityDenied (honest deny) ────────────────

    @Test
    fun `cpu online (core parking) is not bindable - CapabilityDenied`() = runTest {
        val binder = mockk<AyaneoBinderClient>(relaxed = true)
        coEvery { binder.isAvailable() } returns true
        val writer = writerWith(binder)

        val result = writer.write(Tunables.cpuOnline(5), "0")

        assertThat(result).isInstanceOf(WriteResult.CapabilityDenied::class.java)
    }

    @Test
    fun `min freq floor and gpu pwrlevel are not bindable - CapabilityDenied`() = runTest {
        val binder = mockk<AyaneoBinderClient>(relaxed = true)
        coEvery { binder.isAvailable() } returns true
        val writer = writerWith(binder)

        assertThat(writer.write(Tunables.cpuMinFreq(7), "844800"))
            .isInstanceOf(WriteResult.CapabilityDenied::class.java)
        assertThat(writer.write(Tunables.adrenoMaxPowerLevel("/sys/class/kgsl/kgsl-3d0"), "3"))
            .isInstanceOf(WriteResult.CapabilityDenied::class.java)
    }

    @Test
    fun `canWrite is false for non-bindable nodes, true for bindable when available`() = runTest {
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        val writer = writerWith(binder)

        assertThat(writer.canWrite(Tunables.cpuMaxFreq(7))).isTrue()
        assertThat(writer.canWrite(Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0"))).isTrue()
        assertThat(writer.canWrite(Tunables.cpuOnline(5))).isFalse()
        assertThat(writer.canWrite(Tunables.cpuMinFreq(7))).isFalse()
    }

    @Test
    fun `canWrite is false for a bindable node when the binder is unavailable`() = runTest {
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns false
        val writer = writerWith(binder)

        assertThat(writer.canWrite(Tunables.cpuMaxFreq(7))).isFalse()
    }

    // ── 7. isBindableNode static contract ────────────────────────────────────────

    @Test
    fun `isBindableNode matches the four driven families and nothing else`() {
        assertThat(AyaneoVendorWriter.isBindableNode(Tunables.cpuMaxFreq(7).target)).isTrue()
        assertThat(AyaneoVendorWriter.isBindableNode(Tunables.cpuGovernor(7).target)).isTrue()
        assertThat(AyaneoVendorWriter.isBindableNode("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq")).isTrue()
        assertThat(
            AyaneoVendorWriter.isBindableNode(
                "/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon0/pwm1"
            )
        ).isTrue()

        // Not bindable:
        assertThat(AyaneoVendorWriter.isBindableNode(Tunables.cpuOnline(5).target)).isFalse()
        assertThat(AyaneoVendorWriter.isBindableNode(Tunables.cpuMinFreq(7).target)).isFalse()
        assertThat(AyaneoVendorWriter.isBindableNode("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq")).isFalse()
        assertThat(AyaneoVendorWriter.isBindableNode("/sys/class/kgsl/kgsl-3d0/max_pwrlevel")).isFalse()
    }

    // ── 8. read() falls back to the probed report value for an EACCES CPU node ───

    @Test
    fun `read falls back to probed currentMaxKhz when sysfs read is denied`() = runTest {
        // No file in fs → direct read fails → fall back to the report's currentMaxKhz.
        val fs = FakeFileSystem()
        val binder = mockk<AyaneoBinderClient>(relaxed = true)
        val writer = writerWith(binder, fs)

        val readback = writer.read(Tunables.cpuMaxFreq(7))

        // policy7 currentMaxKhz = 3_187_200 in the fixture.
        assertThat(readback).isEqualTo("3187200")
    }

    // ── 9. PERFORMANCE-MODE lever: command mapping + sysfs-tuple readback verify ──

    /** Seed the fake filesystem's 2 cap nodes so [AyaneoVendorWriter.readCurrentAyaneoMode]
     *  reads back exactly [mode] (per the ground-truth table). GPU is deliberately NOT
     *  seeded/keyed-on — the matcher is CPU-cluster-cap-only (see readCurrentAyaneoMode KDoc
     *  for why: the vendor GPU governor + our own GPU lever move the GPU node independently
     *  of the CPU-cluster mode, so it is not a reliable mode indicator). */
    private fun seedMode(fs: FakeFileSystem, mode: Int) {
        val (cap3, cap7) = when (mode) {
            0 -> 614_400L to 595_200L
            1 -> 1_785_600L to 1_593_600L
            2 -> 1_536_000L to 1_132_800L
            3 -> 2_803_200L to 1_593_600L
            4 -> 2_803_200L to 3_360_000L
            else -> error("unknown mode $mode")
        }
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, cap3.toString())
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, cap7.toString())
    }

    @Test
    fun `perf mode maps to com_set_performance_mode and verifies via the sysfs tuple`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val id = Tunables.ayaPerformanceMode()
        val binder = mockk<AyaneoBinderClient>()
        val slot = slot<String>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(capture(slot)) } coAnswers {
            // Simulate the vendor overlay actuating the tuple nodes to the requested mode.
            val mode = slot.captured.substringAfterLast(":").toInt()
            seedMode(fs, mode)
            sent.add(slot.captured); true
        }
        val writer = writerWith(binder, fs)

        val result = writer.write(id, "3")

        // EXACT bare-integer payload.
        assertThat(sent.single())
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_mode:3")
        // Verified via the sysfs tuple reading back mode 3 → Applied.
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat((result as WriteResult.Success).newValue).isEqualTo("3")
        assertThat(result.verified).isTrue()
    }

    @Test
    fun `perf mode value is clamped to 0 to 4 in the emitted command`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val binder = acceptingBinder(sent)
        val writer = writerWith(binder, fs)
        // Seed a tuple so the readback path doesn't null out (verify unaffected here).
        seedMode(fs, 4)

        writer.write(Tunables.ayaPerformanceMode(), "9")

        assertThat(sent.single())
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_mode:4")
    }

    @Test
    fun `perf mode sysfs tuple that did not move yields Rejected`() = runTest {
        val fs = FakeFileSystem()
        // Tuple stays at stock mode 1 — the overlay did not take the requested mode 0.
        seedMode(fs, 1)
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true
        val writer = writerWith(binder, fs)

        val result = writer.write(Tunables.ayaPerformanceMode(), "0")

        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
        assertThat((result as WriteResult.Rejected).message).contains("mode 1")
    }

    @Test
    fun `perf mode with an unreadable sysfs tuple is Success but verified=false`() = runTest {
        // No tuple nodes seeded → readback null → accept-but-unverified (never faked verified).
        val fs = FakeFileSystem()
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true
        val writer = writerWith(binder, fs)

        val result = writer.write(Tunables.ayaPerformanceMode(), "2")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat((result as WriteResult.Success).newValue).isEqualTo("2")
        assertThat(result.verified).isFalse()
    }

    @Test
    fun `perf mode binder rejecting the send yields Failed (transient)`() = runTest {
        val fs = FakeFileSystem()
        seedMode(fs, 1)
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns false
        val writer = writerWith(binder, fs)

        val result = writer.write(Tunables.ayaPerformanceMode(), "3")

        assertThat(result).isInstanceOf(WriteResult.Failed::class.java)
    }

    @Test
    fun `perf mode read() returns stock mode 1 as the revert snapshot baseline`() = runTest {
        // Even when the sysfs tuple currently reads a NON-stock mode, read() (the snapshot
        // seed) returns stock mode 1 so revertAll restores factory state, not the pre-engage
        // mode. This is the constraint: revert targets mode 1, never 0 or a stray mode.
        val fs = FakeFileSystem()
        seedMode(fs, 4)
        val binder = mockk<AyaneoBinderClient>(relaxed = true)
        val writer = writerWith(binder, fs)

        assertThat(writer.read(Tunables.ayaPerformanceMode())).isEqualTo("1")
    }

    @Test
    fun `perf mode canWrite is true when binder available, PerformanceMode is not Unsupported`() = runTest {
        val binder = mockk<AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        val writer = writerWith(binder)

        assertThat(writer.canWrite(Tunables.ayaPerformanceMode())).isTrue()
    }

    // ── 10. readCurrentAyaneoMode: the tuple matcher itself ──────────────────────

    @Test
    fun `readCurrentAyaneoMode maps each of the 5 known tuples to its mode`() {
        for (mode in 0..4) {
            val fs = FakeFileSystem()
            seedMode(fs, mode)
            assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fs)).isEqualTo(mode)
        }
    }

    @Test
    fun `readCurrentAyaneoMode disambiguates modes 3 and 4 which share policy3`() {
        // Both modes 3 & 4 have cap3=2803200 — must use policy7 to tell them apart.
        val fsMode3 = FakeFileSystem()
        seed(fsMode3, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "2803200")
        seed(fsMode3, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "1593600")
        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fsMode3)).isEqualTo(3)

        val fsMode4 = FakeFileSystem()
        seed(fsMode4, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "2803200")
        seed(fsMode4, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "3360000")
        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fsMode4)).isEqualTo(4)
    }

    @Test
    fun `readCurrentAyaneoMode disambiguates modes 1 and 3 which share policy7`() {
        // Both modes 1 & 3 have cap7=1593600 — must use policy3 to tell them apart.
        val fsMode1 = FakeFileSystem()
        seed(fsMode1, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "1785600")
        seed(fsMode1, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "1593600")
        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fsMode1)).isEqualTo(1)

        val fsMode3 = FakeFileSystem()
        seed(fsMode3, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "2803200")
        seed(fsMode3, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "1593600")
        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fsMode3)).isEqualTo(3)
    }

    @Test
    fun `readCurrentAyaneoMode returns null for an unrecognized cap pair`() {
        val fs = FakeFileSystem()
        // Mid-transition / garbage values that don't match any known mode row.
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "999999")
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "888888")

        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fs)).isNull()
    }

    @Test
    fun `readCurrentAyaneoMode returns null when either cap node is unreadable`() {
        // Only policy3 seeded — policy7 missing.
        val fs = FakeFileSystem()
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "1785600")

        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fs)).isNull()
    }

    // ── 11. LIVE REGRESSION: GPU must NOT be part of the mode signature ──────────

    @Test
    fun `readCurrentAyaneoMode returns mode 0 even when GPU reads 680MHz (live regression)`() {
        // Device-verified regression (kill-parked Pocket DS, mode 0): policy3=614400 and
        // policy7=595200 are exactly mode-0-correct, but the GPU devfreq max_freq node reads
        // 680000000 (mode 1/2/3's nominal value) instead of mode 0's nominal 220000000 — the
        // vendor GPU governor idles/varies the node independently of the CPU-cluster mode.
        // The OLD 3-node tuple matched nothing here and returned null, which made the
        // AyaneoStockReconciler skip self-heal and left the device stuck at mode 0. The
        // caps-only matcher must return 0 regardless of what the GPU node reads.
        val fs = FakeFileSystem()
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "614400")
        seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "595200")
        // GPU node intentionally seeded to a NON-mode-0 value (or left unseeded — either way
        // must not affect the result, since GPU is no longer read by the matcher at all).
        seed(fs, "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "680000000")

        assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fs)).isEqualTo(0)
    }

    @Test
    fun `readCurrentAyaneoMode ignores GPU entirely for all 5 modes`() {
        // Seed every mode's CPU caps but with a deliberately WRONG/irrelevant GPU value —
        // the matcher must still resolve the correct mode from caps alone.
        val capsByMode = mapOf(
            0 to (614_400L to 595_200L),
            1 to (1_785_600L to 1_593_600L),
            2 to (1_536_000L to 1_132_800L),
            3 to (2_803_200L to 1_593_600L),
            4 to (2_803_200L to 3_360_000L),
        )
        for ((mode, caps) in capsByMode) {
            val fs = FakeFileSystem()
            seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, caps.first.toString())
            seed(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, caps.second.toString())
            seed(fs, "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "999999999") // garbage GPU
            assertThat(AyaneoVendorWriter.readCurrentAyaneoMode(fs)).isEqualTo(mode)
        }
    }
}
