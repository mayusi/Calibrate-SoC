package io.github.mayusi.calibratesoc.data.tunables.writer

import android.content.Context
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
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * CHANGE 2 honesty tests: a vendor SETTINGS_SYSTEM key write that's supposed to
 * drive a kernel node must be readback-verified. An INERT key (the AYANEO case,
 * where fan/perf ride a private binder, not these Settings keys) must NOT be
 * reported as a live kernel write. And the VENDOR_SETTINGS privilege tier must
 * never be assumed to be a live cpufreq path.
 */
class SettingsKeyVerifyTest {

    private val writer = SettingsKeyWriter(mockk<Context>(relaxed = true))

    private val key = TunableId(TunableKind.SETTINGS_SYSTEM, "performance_mode")

    // ── evaluateNodeMovement: the pure honesty core ───────────────────────────

    @Test
    fun `node that changed counts as MOVED (vendor subscribes to the key)`() {
        val result = writer.evaluateNodeMovement(
            nodeBefore = "2016000",
            nodeAfter = "3200000",
            expectedNodeValue = null,
        )
        assertThat(result).isEqualTo(SettingsKeyWriter.NodeMovement.MOVED)
    }

    @Test
    fun `node that did NOT change is INERT (AYANEO private-binder case)`() {
        // The key write succeeded but the kernel node is unchanged — the vendor
        // does not subscribe to this Settings key on this device.
        val result = writer.evaluateNodeMovement(
            nodeBefore = "2016000",
            nodeAfter = "2016000",
            expectedNodeValue = null,
        )
        assertThat(result).isEqualTo(SettingsKeyWriter.NodeMovement.INERT)
    }

    @Test
    fun `unreadable node is UNVERIFIED not a fabricated live result`() {
        val result = writer.evaluateNodeMovement(
            nodeBefore = null,
            nodeAfter = null,
            expectedNodeValue = null,
        )
        assertThat(result).isEqualTo(SettingsKeyWriter.NodeMovement.UNREADABLE)
    }

    @Test
    fun `expected value match counts as MOVED`() {
        val result = writer.evaluateNodeMovement(
            nodeBefore = "0",
            nodeAfter = "1",
            expectedNodeValue = "1",
        )
        assertThat(result).isEqualTo(SettingsKeyWriter.NodeMovement.MOVED)
    }

    @Test
    fun `expected value mismatch is INERT even if the node moved to something else`() {
        // Node moved, but NOT to the value the vendor key was supposed to set —
        // honest: this is not the effect we asked for, so don't claim success.
        val result = writer.evaluateNodeMovement(
            nodeBefore = "0",
            nodeAfter = "2",
            expectedNodeValue = "1",
        )
        assertThat(result).isEqualTo(SettingsKeyWriter.NodeMovement.INERT)
    }

    @Test
    fun `expected value match tolerates surrounding whitespace`() {
        val result = writer.evaluateNodeMovement(
            nodeBefore = "0",
            nodeAfter = " 1 ",
            expectedNodeValue = "1",
        )
        assertThat(result).isEqualTo(SettingsKeyWriter.NodeMovement.MOVED)
    }

    // ── VENDOR_SETTINGS tier never claims a live cpufreq path ─────────────────

    /**
     * End-to-end: on a VENDOR_SETTINGS device with no root, no PServer, no chmod,
     * and no Shizuku-probed node, the prime-cluster scaling_max_freq SYSFS node is
     * NOT live-writable. The vendor-settings tier is the PRESET surface, never a
     * live cpufreq path.
     */
    @Test
    fun `VENDOR_SETTINGS tier does not claim a live cpufreq path`() {
        val nodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns false
        }
        val pserver = mockk<PServerWriter>(relaxed = true).also {
            every { it.binder() } returns null
            every { it.transactableNow() } returns false
        }
        val registry = WriterRegistry(
            root = mockk(relaxed = true),
            shizuku = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            pserver = pserver,
            noop = NoopWriter(mockk(relaxed = true)),
            unlockedFile = UnlockedFileWriter(),
            nodeCache = nodeCache,
        )

        val report = vendorSettingsReport()
        val bigPolicy = report.cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }!!
        val freqId = Tunables.cpuMaxFreq(bigPolicy.policyId)

        // The SYSFS cap node is NOT live-writable on the vendor-settings tier.
        assertThat(registry.isLiveWritable(freqId, report)).isFalse()
        assertThat(registry.writerFor(freqId, report)).isInstanceOf(NoopWriter::class.java)
    }

    private fun vendorSettingsReport() = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYANEO", brand = "AYANEO", model = "Pocket",
            device = "pocket", hardware = "pocket",
            androidVersion = "13", sdkInt = 33, knownHandheldKey = "ayaneo_pocket",
        ),
        soc = SoCIdentity("QTI", "SM8550", GpuFamily.ADRENO),
        privilege = PrivilegeTier.VENDOR_SETTINGS,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(
            CpuPolicyProbe(
                policyId = 0,
                onlineCores = listOf(0, 1, 2, 3),
                availableFreqsKhz = listOf(300_000, 2_016_000),
                availableGovernors = listOf("schedutil"),
                currentMinKhz = 300_000,
                currentMaxKhz = 2_016_000,
                currentGovernor = "schedutil",
                hardwareLimitsKhz = FreqRange(300_000, 2_016_000),
            ),
            CpuPolicyProbe(
                policyId = 4,
                onlineCores = listOf(4, 5, 6, 7),
                availableFreqsKhz = listOf(700_000, 3_200_000),
                availableGovernors = listOf("schedutil"),
                currentMinKhz = 700_000,
                currentMaxKhz = 3_200_000,
                currentGovernor = "schedutil",
                hardwareLimitsKhz = FreqRange(700_000, 3_200_000),
            ),
        ),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        // AYANEO has AYASpace, which is a vendor perf app — so the tier is
        // VENDOR_SETTINGS, yet the cpufreq node is still not live-writable.
        vendorApps = VendorAppPresence(
            aynGameAssistant = false, langerhansOdinTools = false,
            ayaSpace = true, retroidGameAssistant = false,
        ),
        sysfsDirectlyWritable = false,
        pserverSysfsLive = false,
    )
}
