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
        // for the revert write (e.g. rebooted into AYN_SETTINGS tier after a Root-tier write).
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
