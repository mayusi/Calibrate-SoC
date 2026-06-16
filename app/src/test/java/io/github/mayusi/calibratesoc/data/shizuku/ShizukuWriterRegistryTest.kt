package io.github.mayusi.calibratesoc.data.shizuku

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.NoopWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.RootWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.SettingsKeyWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.ShizukuWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.UnlockedFileWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [WriterRegistry] tier-resolution with Shizuku per-node probe.
 *
 * HONESTY INVARIANT TESTS: verify that:
 *   - A SYSFS node on the SHIZUKU tier is routed to [ShizukuWriter] ONLY when
 *     [ShizukuNodeCache.isCachedWritable] returns true.
 *   - A SYSFS node on the SHIZUKU tier where the probe returned DENIED is routed
 *     to [NoopWriter] (not ShizukuWriter).
 *   - [isLiveWritable] returns false for denied nodes (so AutoTDP daemon
 *     does not start for nodes that provably cannot be written).
 */
class ShizukuWriterRegistryTest {

    private lateinit var root: RootWriter
    private lateinit var shizuku: ShizukuWriter
    private lateinit var settings: SettingsKeyWriter
    private lateinit var pserver: PServerWriter
    private lateinit var noop: NoopWriter
    private lateinit var unlockedFile: UnlockedFileWriter
    private lateinit var nodeCache: ShizukuNodeCache
    private lateinit var registry: WriterRegistry

    @Before
    fun setUp() {
        root = mockk(relaxed = true)
        shizuku = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        pserver = mockk(relaxed = true)
        noop = mockk(relaxed = true)
        unlockedFile = mockk(relaxed = true)
        nodeCache = mockk()
        // Default: pserver binder is null (non-AYN device)
        every { pserver.binder() } returns null
        every { pserver.transactableNow() } returns false

        registry = WriterRegistry(
            root = root,
            shizuku = shizuku,
            settings = settings,
            pserver = pserver,
            noop = noop,
            unlockedFile = unlockedFile,
            nodeCache = nodeCache,
            ayaneo = mockk(relaxed = true),
        )
    }

    // ── SHIZUKU tier + probe passed → ShizukuWriter ───────────────────────────

    @Test
    fun `SHIZUKU tier with probe-passed node routes to ShizukuWriter`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(path) } returns true

        val writer = registry.writerFor(id, reportFor(PrivilegeTier.SHIZUKU))

        assertThat(writer).isSameInstanceAs(shizuku)
    }

    // ── HONESTY: SHIZUKU tier + probe denied → NoopWriter ────────────────────

    @Test
    fun `SHIZUKU tier with probe-denied node routes to NoopWriter not ShizukuWriter`() {
        // This is the core honesty test: Snapdragon vendor SELinux denies shell
        // writes to cpufreq on many devices. The probe caught it — NoopWriter.
        val path = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(path) } returns false

        val writer = registry.writerFor(id, reportFor(PrivilegeTier.SHIZUKU))

        assertThat(writer).isSameInstanceAs(noop)
    }

    // ── HONESTY: not-yet-probed node on SHIZUKU tier → NoopWriter ────────────

    @Test
    fun `SHIZUKU tier with not-yet-probed node routes to NoopWriter`() {
        // isCachedWritable returns false when the path has never been probed
        // (getCached returns null, isCachedWritable defaults to false).
        val path = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(path) } returns false // not-yet-probed same as denied

        val writer = registry.writerFor(id, reportFor(PrivilegeTier.SHIZUKU))

        assertThat(writer).isSameInstanceAs(noop)
    }

    // ── isLiveWritable: probe-passed → true ───────────────────────────────────

    @Test
    fun `isLiveWritable returns true for SHIZUKU tier with probe-passed node`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(path) } returns true

        assertThat(registry.isLiveWritable(id, reportFor(PrivilegeTier.SHIZUKU))).isTrue()
    }

    // ── isLiveWritable: probe-denied → false ──────────────────────────────────

    @Test
    fun `isLiveWritable returns false for SHIZUKU tier with probe-denied node`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(path) } returns false

        assertThat(registry.isLiveWritable(id, reportFor(PrivilegeTier.SHIZUKU))).isFalse()
    }

    // ── ROOT tier always routes to RootWriter (no probe needed) ──────────────

    @Test
    fun `ROOT tier routes to RootWriter without consulting the node cache`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val id = TunableId(TunableKind.SYSFS, path)
        // Root bypasses probe — cache is never queried for ROOT tier.
        every { nodeCache.isCachedWritable(any()) } returns false // would deny if consulted

        val writer = registry.writerFor(id, reportFor(PrivilegeTier.ROOT))

        assertThat(writer).isSameInstanceAs(root)
    }

    // ── NONE tier without unlock → NoopWriter ────────────────────────────────

    @Test
    fun `NONE tier without unlock routes to NoopWriter`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(any()) } returns false

        val writer = registry.writerFor(
            id,
            reportFor(PrivilegeTier.NONE, sysfsDirectlyWritable = false),
        )

        assertThat(writer).isSameInstanceAs(noop)
    }

    // ── NONE tier with unlock → UnlockedFileWriter for covered node ───────────

    @Test
    fun `NONE tier with sysfsDirectlyWritable routes unlock-covered node to UnlockedFileWriter`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        assertThat(Tunables.isUnlockCoveredNode(path)).isTrue()
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(any()) } returns false

        val writer = registry.writerFor(
            id,
            reportFor(PrivilegeTier.NONE, sysfsDirectlyWritable = true),
        )

        assertThat(writer).isSameInstanceAs(unlockedFile)
    }

    // ── Shizuku probe passed GPU node also routes correctly ───────────────────

    @Test
    fun `SHIZUKU tier with probe-passed GPU node routes to ShizukuWriter`() {
        val path = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
        val id = TunableId(TunableKind.SYSFS, path)
        every { nodeCache.isCachedWritable(path) } returns true

        val writer = registry.writerFor(id, reportFor(PrivilegeTier.SHIZUKU))

        assertThat(writer).isSameInstanceAs(shizuku)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun reportFor(
        tier: PrivilegeTier,
        sysfsDirectlyWritable: Boolean = false,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test", brand = "Test", model = "TestDevice",
            device = "test", hardware = "test",
            androidVersion = "13", sdkInt = 33, knownHandheldKey = null,
        ),
        soc = SoCIdentity("QTI", "SM8550", GpuFamily.ADRENO),
        privilege = tier,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(
            installed = true,
            running = true,
            permissionGranted = tier == PrivilegeTier.SHIZUKU,
            sysfsWriteAllowed = null,
        ),
        cpuPolicies = emptyList(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
        sysfsDirectlyWritable = sysfsDirectlyWritable,
    )
}
