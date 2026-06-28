package io.github.mayusi.calibratesoc.data.tunables.writer

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Wave 2: PServer is the STRONGEST sysfs tier on ANY device that has it.
 *
 * Proves the new [WriterRegistry] precedence:
 *   1. When `pserverSysfsLive` is true, SYSFS perf nodes (cpufreq, DDR/bus devfreq,
 *      uclamp, IO scheduler, input-boost, GPU) route to [PServerWriter] — including
 *      the families that previously fell through to [NoopWriter] on a non-root device.
 *   2. The vendor FAN node is carved out: even on a PServer-live + AYANEO-binder-live
 *      device it stays on the AYANEO binder, not PServer.
 *   3. When NOT pserverSysfsLive, the AYANEO binder still routes its bindable nodes.
 *   4. PServer wins over chmod-direct (sysfsDirectlyWritable) for sysfs.
 *   5. SETTINGS_SYSTEM routes to PServer whenever it is transactable.
 *
 * Pure JVM — no Android, no binder IPC.
 */
class PServerStrongestTierTest {

    // ── Writers / registry ────────────────────────────────────────────────────

    private fun pserverMock(transactable: Boolean): PServerWriter =
        mockk<PServerWriter>(relaxed = true).also {
            every { it.binder() } returns null
            every { it.transactableNow() } returns transactable
        }

    private fun makeRegistry(
        pserver: PServerWriter,
        nodeCacheWritable: Boolean = false,
    ): WriterRegistry {
        val root = mockk<RootWriter>(relaxed = true)
        val shizuku = mockk<ShizukuWriter>(relaxed = true)
        val settings = mockk<SettingsKeyWriter>(relaxed = true)
        val noop = NoopWriter(mockk(relaxed = true))
        val unlockedFile = UnlockedFileWriter()
        val ayaneo = mockk<AyaneoVendorWriter>(relaxed = true)
        val nodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns nodeCacheWritable
        }
        return WriterRegistry(root, shizuku, settings, pserver, noop, unlockedFile, nodeCache, ayaneo)
    }

    // ── Reports ───────────────────────────────────────────────────────────────

    /** Retroid Pocket 6: PServer-LIVE (full root), routes EVERYTHING via PServer. */
    private fun rp6Report(
        pserverSysfsLive: Boolean,
        ayaneoBinderLive: Boolean = false,
        sysfsDirectlyWritable: Boolean = false,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Moorechip", brand = "Retroid", model = "Retroid Pocket 6",
            device = "kalama", hardware = "kalama", androidVersion = "13", sdkInt = 33,
            knownHandheldKey = "retroid_pocket6",
        ),
        soc = SoCIdentity("QTI", "QCS8550", GpuFamily.ADRENO),
        privilege = PrivilegeTier.VENDOR_SETTINGS,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = emptyList(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(
            aynGameAssistant = false, langerhansOdinTools = false,
            ayaSpace = false, retroidGameAssistant = true,
        ),
        sysfsDirectlyWritable = sysfsDirectlyWritable,
        pserverSysfsLive = pserverSysfsLive,
        ayaneoBinderLive = ayaneoBinderLive,
    )

    // ── Node fixtures ─────────────────────────────────────────────────────────

    private val cpuMax = TunableId(TunableKind.SYSFS, "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq")
    private val ddrDevfreqMax = KernelTunables.devfreqMaxFreq("19091000.cpubw")
    private val ioScheduler = KernelTunables.ioScheduler("sda")
    private val inputBoost = KernelTunables.inputBoostFreq()
    private val uclampMin = KernelTunables.uclampMin("top-app")
    private val gpuMax = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")
    private val ayaneoFanPwm = TunableId(
        TunableKind.SYSFS,
        "/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon0/pwm1",
    )

    // ── 1. PServer-LIVE routes the perf families that used to be NoopWriter ────

    @Test
    fun `RP6 PServer-live routes cpufreq DDR IO input-boost uclamp GPU to PServer`() {
        val pserver = pserverMock(transactable = true)
        val registry = makeRegistry(pserver)
        val report = rp6Report(pserverSysfsLive = true)

        // All of these previously routed to NoopWriter on a non-root RP6.
        for (id in listOf(cpuMax, ddrDevfreqMax, ioScheduler, inputBoost, uclampMin, gpuMax)) {
            assertThat(registry.writerFor(id, report)).isInstanceOf(PServerWriter::class.java)
            assertThat(registry.isLiveWritable(id, report)).isTrue()
        }
    }

    @Test
    fun `same perf families are NoopWriter when NOT PServer-live (no regression baseline)`() {
        val pserver = pserverMock(transactable = false)
        val registry = makeRegistry(pserver)
        val report = rp6Report(pserverSysfsLive = false)

        // DDR / IO / input-boost / uclamp have no chmod-direct or binder path here →
        // they stay NoopWriter, exactly as before Wave 2 (honest: not live-writable).
        for (id in listOf(ddrDevfreqMax, ioScheduler, inputBoost, uclampMin)) {
            assertThat(registry.writerFor(id, report)).isInstanceOf(NoopWriter::class.java)
            assertThat(registry.isLiveWritable(id, report)).isFalse()
        }
    }

    // ── 2. Fan carve-out ──────────────────────────────────────────────────────

    @Test
    fun `fan pwm node stays on AYANEO binder even when PServer is live`() {
        val pserver = pserverMock(transactable = true)
        val registry = makeRegistry(pserver)
        // BOTH live: PServer-root AND the AYANEO binder. The fan must stay vendor-routed.
        val report = rp6Report(pserverSysfsLive = true, ayaneoBinderLive = true)

        assertThat(registry.isFanOnlyBinderNode(ayaneoFanPwm.target)).isTrue()
        assertThat(registry.writerFor(ayaneoFanPwm, report))
            .isInstanceOf(AyaneoVendorWriter::class.java)
        // ...while a NON-fan node on the same device goes to PServer.
        assertThat(registry.writerFor(cpuMax, report)).isInstanceOf(PServerWriter::class.java)
    }

    @Test
    fun `isFanOnlyBinderNode matches hwmon pwm fan and rejects perf nodes`() {
        val pserver = pserverMock(transactable = true)
        val registry = makeRegistry(pserver)
        assertThat(registry.isFanOnlyBinderNode("/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon0/pwm1")).isTrue()
        assertThat(registry.isFanOnlyBinderNode("/sys/class/hwmon/hwmon3/pwm1")).isTrue()
        assertThat(registry.isFanOnlyBinderNode(cpuMax.target)).isFalse()
        assertThat(registry.isFanOnlyBinderNode(ddrDevfreqMax.target)).isFalse()
        assertThat(registry.isFanOnlyBinderNode(uclampMin.target)).isFalse()
    }

    // ── 3. AYANEO binder still routes when NOT PServer-live ────────────────────

    @Test
    fun `AYANEO binder routes bindable nodes when not PServer-live`() {
        val pserver = pserverMock(transactable = false)
        val registry = makeRegistry(pserver)
        val report = rp6Report(pserverSysfsLive = false, ayaneoBinderLive = true)

        // CPU cap is a bindable node → AYANEO binder.
        assertThat(registry.writerFor(cpuMax, report)).isInstanceOf(AyaneoVendorWriter::class.java)
        // Fan is bindable too.
        assertThat(registry.writerFor(ayaneoFanPwm, report)).isInstanceOf(AyaneoVendorWriter::class.java)
    }

    // ── 4. PServer wins over chmod-direct ─────────────────────────────────────

    @Test
    fun `PServer wins over chmod-direct for sysfs`() {
        val pserver = pserverMock(transactable = true)
        val registry = makeRegistry(pserver)
        // Both pserver-live AND chmod-direct true: PServer (root) is stronger.
        val report = rp6Report(pserverSysfsLive = true, sysfsDirectlyWritable = true)
        assertThat(registry.writerFor(cpuMax, report)).isInstanceOf(PServerWriter::class.java)
    }

    // ── 5. SETTINGS_SYSTEM routes to PServer when transactable ────────────────

    @Test
    fun `SETTINGS_SYSTEM routes to PServer when transactable`() {
        val pserver = pserverMock(transactable = true)
        val registry = makeRegistry(pserver)
        val report = rp6Report(pserverSysfsLive = true)
        val fanMode = TunableId(TunableKind.SETTINGS_SYSTEM, "fan_mode")
        assertThat(registry.writerFor(fanMode, report)).isInstanceOf(PServerWriter::class.java)
    }

    @Test
    fun `SETTINGS_SYSTEM falls back to SettingsKeyWriter when not transactable and no vendor binder`() {
        val pserver = pserverMock(transactable = false)
        val registry = makeRegistry(pserver)
        val report = rp6Report(pserverSysfsLive = false)
        val fanMode = TunableId(TunableKind.SETTINGS_SYSTEM, "fan_mode")
        assertThat(registry.writerFor(fanMode, report)).isInstanceOf(SettingsKeyWriter::class.java)
    }
}
