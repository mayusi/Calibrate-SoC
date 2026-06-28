package io.github.mayusi.calibratesoc.data.tunables

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.RootWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.SysfsWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TunableWriterTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var store: TunableSnapshotStore
    private lateinit var registry: WriterRegistry
    private lateinit var writer: TunableWriter
    private lateinit var fakeBackend: SysfsWriter

    private lateinit var pServerWriter: PServerWriter

    @Before
    fun setUp() {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns tempDir.root
        store = TunableSnapshotStore(ctx, FileSystem.SYSTEM, Json { ignoreUnknownKeys = true })
        fakeBackend = mockk(relaxed = true)
        registry = mockk()
        every { registry.writerFor(any(), any()) } returns fakeBackend
        // Adapter registry returns null for the test's generic key, so
        // the WriteProtocol branch falls through to the plain write
        // path — keeps these tests focused on snapshot/revert semantics
        // rather than per-device protocols (which have their own test).
        val adapterRegistry = mockk<DeviceAdapterRegistry>()
        every { adapterRegistry.lookup(any()) } returns null
        val rootWriter = mockk<RootWriter>(relaxed = true)
        pServerWriter = mockk(relaxed = true)
        writer = TunableWriter(registry, store, adapterRegistry, rootWriter, pServerWriter)
    }

    @Test
    fun `write snapshots previous value then performs write`() = runTest {
        val id = Tunables.cpuMaxFreq(0)
        coEvery { fakeBackend.read(id) } returns "1804800"
        coEvery { fakeBackend.write(id, "2400000") } returns
            WriteResult.Success(id, "1804800", "2400000")

        val result = writer.write(id, "2400000", REPORT, "tune slider")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val journal = store.read()
        assertThat(journal.entries).hasSize(1)
        assertThat(journal.entries.first().previousValue).isEqualTo("1804800")
        assertThat(journal.entries.first().reason).isEqualTo("tune slider")
    }

    @Test
    fun `revertAll writes back each previous value in reverse order`() = runTest {
        val id1 = Tunables.cpuMaxFreq(0)
        val id2 = Tunables.cpuGovernor(0)
        coEvery { fakeBackend.read(id1) } returns "1804800" andThen "2400000"
        coEvery { fakeBackend.read(id2) } returns "schedutil"
        coEvery { fakeBackend.write(id1, any()) } returns WriteResult.Success(id1, null, "x")
        coEvery { fakeBackend.write(id2, any()) } returns WriteResult.Success(id2, null, "x")

        writer.write(id1, "2400000", REPORT, "first")
        writer.write(id2, "performance", REPORT, "second")
        val summary = writer.revertAll(REPORT)

        assertThat(summary.totalEntries).isEqualTo(2)
        assertThat(summary.ok).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(0)
        // Successful revert clears the journal.
        assertThat(store.read().entries).isEmpty()
    }

    @Test
    fun `cpufreq write on adapter with daemon list routes through writeWithProtocol`() = runTest {
        val odin3Adapter = io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter(
            key = "ayn_odin3",
            displayName = "AYN Odin 3",
            vendorAppPackage = null,
            fanAdapter = null,
            perfPresetAdapter = null,
            perfDaemonsToStopOnWrite = listOf("perfd", "vendor.perf-hal-1-0"),
            chmodLockCpuFreqWrites = true,
        )
        val adapterRegistry = mockk<DeviceAdapterRegistry>()
        every { adapterRegistry.lookup("ayn_odin3") } returns odin3Adapter
        val rootWriter = mockk<RootWriter>(relaxed = true)
        val routedRegistry = mockk<WriterRegistry>()
        every { routedRegistry.writerFor(any(), any()) } returns rootWriter
        val w = TunableWriter(routedRegistry, store, adapterRegistry, rootWriter, pServerWriter)

        val odin3Report = REPORT.copy(
            device = REPORT.device.copy(knownHandheldKey = "ayn_odin3"),
        )
        val id = Tunables.cpuMaxFreq(6)
        coEvery { rootWriter.read(id) } returns "4320000"
        coEvery { rootWriter.writeWithProtocol(id, "1958400", any()) } returns
            WriteResult.Success(id, "4320000", "1958400")

        val result = w.write(id, "1958400", odin3Report, "Underclock — Large")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        // Snapshot still recorded with the original stock cap so revert works.
        assertThat(store.read().entries.first().previousValue).isEqualTo("4320000")
    }

    @Test
    fun `revertAll keeps journal if any entry fails`() = runTest {
        val id = Tunables.cpuGovernor(0)
        coEvery { fakeBackend.read(id) } returns "schedutil"
        coEvery { fakeBackend.write(id, "schedutil") } returns
            WriteResult.Rejected(id, errno = 13, message = "EACCES")
        writer.write(id, "performance", REPORT, "test")

        val summary = writer.revertAll(REPORT)

        assertThat(summary.ok).isEqualTo(0)
        assertThat(summary.failed).isEqualTo(1)
        // Journal preserved for the next attempt.
        assertThat(store.read().entries).hasSize(1)
    }

    // ── BUG 2 regression: CapabilityDenied in revertAll must NOT block journal clear ─

    @Test
    fun `revertAll treats CapabilityDenied as skip not failure — journal clears`() = runTest {
        val id = Tunables.cpuMaxFreq(0)
        coEvery { fakeBackend.read(id) } returns "1804800"
        // Simulate a privilege tier downgrade: the writer now returns CapabilityDenied
        // for the revert write (e.g. rebooted into VENDOR_SETTINGS tier after a Root-tier write).
        coEvery { fakeBackend.write(id, "1804800") } returns
            WriteResult.CapabilityDenied(id, "Not enough privilege after reboot")
        writer.write(id, "2400000", REPORT, "test tune")

        val summary = writer.revertAll(REPORT)

        // CapabilityDenied must count as ok (skip), not a failure.
        assertThat(summary.ok).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(0)
        // Journal must be cleared so boot-revert doesn't loop forever.
        assertThat(store.read().entries).isEmpty()
    }

    @Test
    fun `revertAll with mix of success and CapabilityDenied clears journal`() = runTest {
        val id1 = Tunables.cpuMaxFreq(0)
        val id2 = Tunables.cpuGovernor(0)
        coEvery { fakeBackend.read(id1) } returns "1804800"
        coEvery { fakeBackend.read(id2) } returns "schedutil"
        coEvery { fakeBackend.write(id1, "1804800") } returns
            WriteResult.Success(id1, "2400000", "1804800")
        coEvery { fakeBackend.write(id2, "schedutil") } returns
            WriteResult.CapabilityDenied(id2, "Privilege downgrade")
        writer.write(id1, "2400000", REPORT, "tune cpu")
        writer.write(id2, "performance", REPORT, "tune gov")

        val summary = writer.revertAll(REPORT)

        assertThat(summary.ok).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(0)
        assertThat(store.read().entries).isEmpty()
    }

    // ── HIGH-1 regression: an UNVERIFIED revert of a critical (CPU-cap) node must ──
    // ── NOT clear the journal, so BootRevertReceiver stays armed as the backstop. ──

    @Test
    fun `revertAll keeps journal when a critical CPU-cap revert is Success-unverified`() = runTest {
        val capId = Tunables.cpuMaxFreq(7) // scaling_max_freq → a CRITICAL node
        coEvery { fakeBackend.read(capId) } returns "3187200"
        // The AYANEO EACCES path: the binder accepted the stock-restoring command but the
        // node is not app-readable, so the writer returns Success with verified=false.
        coEvery { fakeBackend.write(capId, "3187200") } returns
            WriteResult.Success(capId, previousValue = "2419200", newValue = "3187200", verified = false)
        writer.write(capId, "2419200", REPORT, "AutoTDP cap")

        val summary = writer.revertAll(REPORT)

        // The revert "succeeded" at the binder layer (counts toward ok, no failure)…
        assertThat(summary.ok).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(0)
        // …but it was UNVERIFIED on a critical node, so the journal is PRESERVED: the
        // boot-revert backstop must survive an unconfirmed revert that may not have landed.
        assertThat(summary.journalCleared).isFalse()
        assertThat(store.read().entries).hasSize(1)
    }

    @Test
    fun `revertAll still clears journal for a VERIFIED critical CPU-cap revert`() = runTest {
        val capId = Tunables.cpuMaxFreq(7)
        coEvery { fakeBackend.read(capId) } returns "3187200"
        // Verified revert (readback confirmed) → default verified=true → journal clears,
        // exactly as the existing PServer/Shizuku/Root verified paths do (no regression).
        coEvery { fakeBackend.write(capId, "3187200") } returns
            WriteResult.Success(capId, previousValue = "2419200", newValue = "3187200")
        writer.write(capId, "2419200", REPORT, "AutoTDP cap")

        val summary = writer.revertAll(REPORT)

        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.journalCleared).isTrue()
        assertThat(store.read().entries).isEmpty()
    }

    @Test
    fun `revertAll clears journal when only a NON-critical node is reverted unverified`() = runTest {
        // A GPU max revert that comes back unverified is benign (not device-pinning) — it
        // must NOT keep the journal pinned. Only the CPU cap is gated by HIGH-1.
        val gpuId = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")
        coEvery { fakeBackend.read(gpuId) } returns "680000000"
        coEvery { fakeBackend.write(gpuId, "680000000") } returns
            WriteResult.Success(gpuId, previousValue = "585000000", newValue = "680000000", verified = false)
        writer.write(gpuId, "585000000", REPORT, "AutoTDP gpu")

        val summary = writer.revertAll(REPORT)

        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.journalCleared).isTrue()
        assertThat(store.read().entries).isEmpty()
    }

    // ── BUG 3 regression: PServerWriter must receive protocol pre/post hooks ──

    @Test
    fun `cpufreq write on adapter with daemon list dispatches pre post hooks via PServer`() = runTest {
        val odin3Adapter = io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter(
            key = "ayn_odin3",
            displayName = "AYN Odin 3",
            vendorAppPackage = null,
            fanAdapter = null,
            perfPresetAdapter = null,
            perfDaemonsToStopOnWrite = listOf("perfd", "vendor.perf-hal-1-0"),
            chmodLockCpuFreqWrites = true,
        )
        val adapterRegistry = mockk<DeviceAdapterRegistry>()
        every { adapterRegistry.lookup("ayn_odin3") } returns odin3Adapter

        val rootWriter = mockk<RootWriter>(relaxed = true)
        val routedRegistry = mockk<WriterRegistry>()
        // Route to pServerWriter for this test.
        every { routedRegistry.writerFor(any(), any()) } returns pServerWriter
        val w = TunableWriter(routedRegistry, store, adapterRegistry, rootWriter, pServerWriter)

        val odin3Report = REPORT.copy(
            device = REPORT.device.copy(knownHandheldKey = "ayn_odin3"),
        )
        val id = Tunables.cpuMaxFreq(6)
        coEvery { pServerWriter.read(id) } returns "4320000"
        coEvery { pServerWriter.write(id, "1958400") } returns
            WriteResult.Success(id, "4320000", "1958400")
        coEvery { pServerWriter.executeShell(any()) } returns (0 to "")

        val result = w.write(id, "1958400", odin3Report, "Underclock — PServer tier")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        // Protocol pre-hooks (stop perfd etc.) must have been sent via PServer.
        coVerify(atLeast = 1) { pServerWriter.executeShell(match { it.startsWith("stop ") }) }
        // Protocol post-hooks (start perfd etc.) must have been sent via PServer.
        coVerify(atLeast = 1) { pServerWriter.executeShell(match { it.startsWith("start ") }) }
    }

    // ── WAVE 3A: session-level perfd suppression of the per-write daemon dance ──

    /**
     * When a session-level owner (AutoTDP's PerfDaemonController) has stopped the
     * vendor perf daemons for the whole session, [TunableWriter.setPerfDaemonsSessionStopped]
     * is true and the per-write `stop`/`start` hooks must be SUPPRESSED — otherwise the
     * per-tick `start` post-hook would restart the daemons and defeat the session stop.
     * The write itself (and the chmod-lock) must still happen.
     */
    @Test
    fun `per-write daemon dance is suppressed while a session-level perfd stop is active`() = runTest {
        val odin3Adapter = io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter(
            key = "ayn_odin3",
            displayName = "AYN Odin 3",
            vendorAppPackage = null,
            fanAdapter = null,
            perfPresetAdapter = null,
            perfDaemonsToStopOnWrite = listOf("perfd", "vendor.perf-hal-1-0"),
            chmodLockCpuFreqWrites = true,
        )
        val adapterRegistry = mockk<DeviceAdapterRegistry>()
        every { adapterRegistry.lookup("ayn_odin3") } returns odin3Adapter
        val rootWriter = mockk<RootWriter>(relaxed = true)
        val routedRegistry = mockk<WriterRegistry>()
        every { routedRegistry.writerFor(any(), any()) } returns pServerWriter
        val w = TunableWriter(routedRegistry, store, adapterRegistry, rootWriter, pServerWriter)

        val odin3Report = REPORT.copy(device = REPORT.device.copy(knownHandheldKey = "ayn_odin3"))
        val id = Tunables.cpuMaxFreq(6)
        coEvery { pServerWriter.read(id) } returns "4320000"
        coEvery { pServerWriter.write(id, "1958400") } returns WriteResult.Success(id, "4320000", "1958400")
        coEvery { pServerWriter.executeShell(any()) } returns (0 to "")

        // Session-level owner has stopped the daemons; suppress the per-write dance.
        w.setPerfDaemonsSessionStopped(true)
        val result = w.write(id, "1958400", odin3Report, "AutoTDP cap (session perfd stopped)")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        // The write still happened…
        coVerify(exactly = 1) { pServerWriter.write(id, "1958400") }
        // …but NO per-write stop/start hooks were issued (the session owns the daemons).
        coVerify(exactly = 0) { pServerWriter.executeShell(match { it.startsWith("stop ") }) }
        coVerify(exactly = 0) { pServerWriter.executeShell(match { it.startsWith("start ") }) }
    }

    /**
     * Clearing the suppression restores the per-write dance for the next write — proving
     * the flag is not sticky and the suppression is per-session, not permanent.
     */
    @Test
    fun `clearing the session perfd flag restores the per-write daemon dance`() = runTest {
        val odin3Adapter = io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter(
            key = "ayn_odin3",
            displayName = "AYN Odin 3",
            vendorAppPackage = null,
            fanAdapter = null,
            perfPresetAdapter = null,
            perfDaemonsToStopOnWrite = listOf("perfd"),
            chmodLockCpuFreqWrites = true,
        )
        val adapterRegistry = mockk<DeviceAdapterRegistry>()
        every { adapterRegistry.lookup("ayn_odin3") } returns odin3Adapter
        val rootWriter = mockk<RootWriter>(relaxed = true)
        val routedRegistry = mockk<WriterRegistry>()
        every { routedRegistry.writerFor(any(), any()) } returns pServerWriter
        val w = TunableWriter(routedRegistry, store, adapterRegistry, rootWriter, pServerWriter)

        val odin3Report = REPORT.copy(device = REPORT.device.copy(knownHandheldKey = "ayn_odin3"))
        val id = Tunables.cpuMaxFreq(6)
        coEvery { pServerWriter.read(id) } returns "4320000"
        coEvery { pServerWriter.write(id, any()) } returns WriteResult.Success(id, "4320000", "1958400")
        coEvery { pServerWriter.executeShell(any()) } returns (0 to "")

        w.setPerfDaemonsSessionStopped(true)
        w.write(id, "1958400", odin3Report, "suppressed")
        w.setPerfDaemonsSessionStopped(false)
        w.write(id, "1958400", odin3Report, "restored")

        // After clearing, the per-write dance resumes — stop+start issued for the 2nd write.
        coVerify(atLeast = 1) { pServerWriter.executeShell("stop perfd") }
        coVerify(atLeast = 1) { pServerWriter.executeShell("start perfd") }
    }

    private companion object {
        val REPORT = CapabilityReport(
            device = DeviceIdentity(
                manufacturer = "AYN", brand = "AYN", model = "Odin2",
                device = "odin2", hardware = "kalama",
                androidVersion = "13", sdkInt = 33, knownHandheldKey = "ayn_odin2",
            ),
            soc = SoCIdentity(
                socManufacturer = "qualcomm", socModel = "sm8550", gpuFamily = GpuFamily.ADRENO,
            ),
            privilege = PrivilegeTier.ROOT,
            rootKind = RootKind.MAGISK,
            shizuku = ShizukuStatus(false, false, false, null),
            cpuPolicies = emptyList(),
            gpu = null,
            thermalZones = emptyList(),
            fan = null,
            vendorApps = VendorAppPresence(false, false, false),
        )
    }
}
