package io.github.mayusi.calibratesoc.data.autotdp

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
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
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.tunables.TunableSnapshotStore
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.RootWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 0.3.6 — SHIP-BLOCKER FIX coverage for [AyaneoStockReconciler].
 *
 * The hazard: AutoTDP's AYANEO revert only fires from in-process exit paths (stopDaemon /
 * onDestroy / onTaskRemoved / the runDaemon finally). A force-kill or crash leaves NONE of
 * those running, so the device can stay parked at a non-stock vendor performance mode with
 * no automatic recovery. [AyaneoStockReconciler.reconcile] is the NEXT-launch/resume/boot
 * self-heal: read the live vendor `currentMode`, and if it is not stock AND AutoTDP is not
 * the legitimate current owner, re-assert stock (1).
 */
class AyaneoStockReconcilerTest {

    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val policies = listOf(
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

    /** AYANEO vendor-binder-live, no full-kernel path — TdpCaps.supportsPerfMode == true. */
    private fun ayaneoReport(
        privilege: PrivilegeTier = PrivilegeTier.NONE,
        ayaneoBinderLive: Boolean = true,
    ) = CapabilityReport(
        device = DeviceIdentity(
            "AYANEO", "AYANEO", "Pocket DS", "PocketDS", "kalama", "13", 33, "ayaneo_pocket_ds",
        ),
        soc = SoCIdentity("QTI", "SG8275", GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, ayaSpace = true),
        ayaneoBinderLive = ayaneoBinderLive,
    )

    /** ROOT device — TdpCaps.supportsPerfMode == false regardless of ayaneoBinderLive. */
    private fun rootReport() = ayaneoReport(privilege = PrivilegeTier.ROOT)

    /** Seed the fake filesystem's 2 CPU-cluster-cap nodes so
     *  [AyaneoVendorWriter.readCurrentAyaneoMode] reads back exactly [currentMode] (per the
     *  ground-truth table). GPU is deliberately NOT part of the signature (see
     *  readCurrentAyaneoMode KDoc / the 0.3.6 live regression this fixed: the vendor GPU
     *  governor + our own GPU lever move the GPU node independently of the CPU-cluster mode). */
    private fun seedConf(fs: FakeFileSystem, currentMode: Int) {
        val (cap3, cap7) = when (currentMode) {
            0 -> 614_400L to 595_200L
            1 -> 1_785_600L to 1_593_600L
            2 -> 1_536_000L to 1_132_800L
            3 -> 2_803_200L to 1_593_600L
            4 -> 2_803_200L to 3_360_000L
            else -> error("unknown mode $currentMode")
        }
        seedPath(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, cap3.toString())
        seedPath(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, cap7.toString())
    }

    private fun seedPath(fs: FakeFileSystem, path: String, value: String) {
        val p = path.toPath()
        p.parent?.let { fs.createDirectories(it) }
        fs.write(p) { writeUtf8(value) }
    }

    /**
     * A [Context] whose ActivityManager reports AutoTDP running or not, per [running].
     *
     * [android.content.ComponentName]'s constructor is a real Android-framework class on
     * the unit-test stub jar (a "Stub!"-throwing body) — constructing one for real leaves
     * its backing fields unset, so `getClassName()` reads back null/empty regardless of
     * what was "passed in". Mock it instead of instantiating it, and mock `className`
     * (the field [ActivityManager.RunningServiceInfo.service] is a genuine public field —
     * assigning it works fine; it's the ComponentName VALUE that must be a mock).
     */
    private fun contextWithAutoTdpRunning(running: Boolean): Context {
        val ctx = mockk<Context>(relaxed = true)
        val am = mockk<ActivityManager>()
        val cn = mockk<android.content.ComponentName>()
        every { cn.className } returns if (running) AutoTdpService::class.java.name else "some.other.Service"
        val info = ActivityManager.RunningServiceInfo().apply { service = cn }
        every { am.getRunningServices(any()) } returns arrayListOf(info)
        // Matches AyaneoStockReconciler's raw String-key getSystemService call (the same
        // pattern HudQuickTileService uses in production).
        every { ctx.getSystemService(Context.ACTIVITY_SERVICE) } returns am
        return ctx
    }

    /** Build a [TunableWriter] wired to a real [WriterRegistry] → [AyaneoVendorWriter], so the
     *  reconcile's write actually exercises the production routing (not a bespoke mock path). */
    private fun tunableWriter(binder: io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient, fs: FakeFileSystem): TunableWriter {
        val probe = mockk<io.github.mayusi.calibratesoc.data.capability.CapabilityProbe>()
        every { probe.report } returns kotlinx.coroutines.flow.MutableStateFlow(ayaneoReport())
        val ayaneoWriter = AyaneoVendorWriter(binder, probe, fs)
        val registry = mockk<WriterRegistry>()
        every { registry.writerFor(any(), any()) } returns ayaneoWriter
        val snapshotStore = mockk<TunableSnapshotStore>(relaxed = true)
        val adapterRegistry = mockk<DeviceAdapterRegistry>()
        every { adapterRegistry.lookup(any()) } returns null
        val rootWriter = mockk<RootWriter>()
        val pServerWriter = mockk<PServerWriter>()
        return TunableWriter(registry, snapshotStore, adapterRegistry, rootWriter, pServerWriter)
    }

    // ── 1. currentMode already stock → true no-op, no AIDL command sent ─────────

    @Test
    fun `reconcile is a no-op when currentMode is already stock`() = runTest {
        val fs = FakeFileSystem()
        seedConf(fs, currentMode = 1)
        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(ayaneoReport())

        // No binder command should have been sent at all — genuine read-first no-op.
        coVerify(exactly = 0) { binder.sendCommand(any()) }
    }

    // ── 2. currentMode NOT stock + AutoTDP not running → re-asserts stock ───────

    @Test
    fun `reconcile restores stock when currentMode is parked and AutoTDP is not running`() = runTest {
        val fs = FakeFileSystem()
        seedConf(fs, currentMode = 0) // parked at powersave — the hazard state

        val sent = mutableListOf<String>()
        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        val captured = slot<String>()
        coEvery { binder.sendCommand(capture(captured)) } coAnswers {
            sent.add(captured.captured)
            // Simulate the overlay actually restoring the conf file to stock.
            seedConf(fs, currentMode = 1)
            true
        }

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(ayaneoReport())

        assertThat(sent).isNotEmpty()
        assertThat(sent.last()).isEqualTo("calibrate:msg_type_performance:com_set_performance_mode:1")
    }

    // ── 3. AutoTDP IS running → never reconciles, even if currentMode is non-stock ──

    @Test
    fun `reconcile does nothing while AutoTDP daemon is actively running`() = runTest {
        val fs = FakeFileSystem()
        seedConf(fs, currentMode = 0) // a legitimate in-progress tighten

        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(true), writer, fs)

        reconciler.reconcile(ayaneoReport())

        coVerify(exactly = 0) { binder.sendCommand(any()) }
    }

    // ── 4. Non-AYANEO / ROOT / RP6 / Odin path → never reconciles ───────────────

    @Test
    fun `reconcile never fires on a fully-kernel-writable ROOT device`() = runTest {
        val fs = FakeFileSystem()
        // Even if a conf file happened to exist with a non-stock value, ROOT devices don't
        // support the perf-mode lever at all (TdpCaps.supportsPerfMode == false).
        seedConf(fs, currentMode = 0)

        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(rootReport())

        coVerify(exactly = 0) { binder.sendCommand(any()) }
    }

    @Test
    fun `reconcile never fires when ayaneoBinderLive is false`() = runTest {
        val fs = FakeFileSystem()
        seedConf(fs, currentMode = 0)

        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns false
        coEvery { binder.sendCommand(any()) } returns true

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(ayaneoReport(ayaneoBinderLive = false))

        coVerify(exactly = 0) { binder.sendCommand(any()) }
    }

    // ── 5. Unreadable/unrecognized sysfs tuple → conservative skip (never fights a transient) ──

    @Test
    fun `reconcile skips this pass when the sysfs tuple is unreadable`() = runTest {
        val fs = FakeFileSystem() // tuple nodes NOT seeded — unreadable

        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(ayaneoReport())

        // Conservative: a null/unreadable tuple (mid-transition, unrecognized) must NOT
        // trigger a reassert — only a confidently non-stock tuple does.
        coVerify(exactly = 0) { binder.sendCommand(any()) }
    }

    @Test
    fun `reconcile skips this pass when the sysfs tuple is unrecognized (mid-transition)`() = runTest {
        val fs = FakeFileSystem()
        // Garbage/mid-transition values that don't match any known mode row.
        seedPath(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "999999")
        seedPath(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "888888")

        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(any()) } returns true

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(ayaneoReport())

        coVerify(exactly = 0) { binder.sendCommand(any()) }
    }

    // ── 6. LIVE REGRESSION: mode-0 CPU caps + a non-mode-0 GPU reading must still self-heal ──

    @Test
    fun `reconcile self-heals a kill-parked mode 0 device even though GPU reads 680MHz`() = runTest {
        // Device-verified regression (kill-parked Pocket DS): policy3=614400, policy7=595200
        // are exactly mode-0-correct, but GPU devfreq max_freq reads 680000000 (NOT mode 0's
        // nominal 220000000) — the vendor GPU governor idles/varies independently of the
        // CPU-cluster mode, and CalibrateSoC's own GPU lever moves the same node. Before the
        // fix, the 3-node tuple matched nothing → readCurrentAyaneoMode returned null → this
        // reconciler logged "sysfs tuple unreadable/unrecognized — skipping" and left the
        // device stuck at mode 0 forever. The caps-only matcher must recognize mode 0 from
        // (policy3, policy7) alone and re-assert stock (mode 1).
        val fs = FakeFileSystem()
        seedPath(fs, AyaneoVendorWriter.PERF_MODE_POLICY3_MAX, "614400")
        seedPath(fs, AyaneoVendorWriter.PERF_MODE_POLICY7_MAX, "595200")
        seedPath(fs, "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "680000000")

        val sent = mutableListOf<String>()
        val binder = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient>()
        coEvery { binder.isAvailable() } returns true
        val captured = slot<String>()
        coEvery { binder.sendCommand(capture(captured)) } coAnswers {
            sent.add(captured.captured)
            // Simulate the overlay actually restoring the CPU caps to stock (mode 1).
            seedConf(fs, currentMode = 1)
            true
        }

        val writer = tunableWriter(binder, fs)
        val reconciler = AyaneoStockReconciler(contextWithAutoTdpRunning(false), writer, fs)

        reconciler.reconcile(ayaneoReport())

        assertThat(sent).isNotEmpty()
        assertThat(sent.last()).isEqualTo("calibrate:msg_type_performance:com_set_performance_mode:1")
    }
}
