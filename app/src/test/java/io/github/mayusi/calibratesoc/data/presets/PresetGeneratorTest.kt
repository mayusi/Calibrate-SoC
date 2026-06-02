package io.github.mayusi.calibratesoc.data.presets

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
import io.github.mayusi.calibratesoc.data.devicedb.CommunityPreset
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class PresetGeneratorTest {

    @Test
    fun `unknown phone with two clusters still gets four built-in presets`() {
        val report = reportWith(
            handheldKey = null, // truly unknown device
            family = GpuFamily.UNKNOWN,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(400, 800, 1200, 1600, 2000)),
                policy(id = 4, freqsMhz = listOf(800, 1400, 2000, 2600, 3200)),
            ),
        )
        val generator = generatorWithoutAdapter()

        val presets = generator.presetsFor(report)

        // Built-in: Battery Saver, Balanced, Performance, Max — that's it for a truly unknown device.
        assertThat(presets.map { it.name }).containsExactly(
            "Battery Saver", "Balanced", "Performance", "Max (stock ceiling)",
        ).inOrder()
        // All four sit at the UNKNOWN_FAMILY tier so the safety gate fires.
        assertThat(presets.map { it.verification }).containsExactly(
            VerificationTier.GENERIC_UNKNOWN_FAMILY,
            VerificationTier.GENERIC_UNKNOWN_FAMILY,
            VerificationTier.GENERIC_UNKNOWN_FAMILY,
            VerificationTier.GENERIC_UNKNOWN_FAMILY,
        )
    }

    @Test
    fun `every chosen cap snaps to an actual OPP step`() {
        // OPP table designed so that "round percentage of max" lands
        // BETWEEN steps — the generator must snap to the nearest one
        // because writing an in-between value would EINVAL.
        val freqsMhz = listOf(400, 800, 1200, 1600, 2000, 2400, 2800, 3200)
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(policy(id = 0, freqsMhz = freqsMhz)),
        )
        val generator = generatorWithoutAdapter()

        val presets = generator.presetsFor(report)
        val freqsKhz = freqsMhz.map { it * 1000 }

        for (preset in presets) {
            preset.cpuPolicyMaxKhz.values.forEach { capKhz ->
                assertThat(freqsKhz).contains(capKhz)
            }
        }
    }

    @Test
    fun `known family Adreno gets the KNOWN_FAMILY tier`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(policy(id = 0, freqsMhz = listOf(500, 1500))),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)
        assertThat(presets.first().verification).isEqualTo(VerificationTier.GENERIC_KNOWN_FAMILY)
    }

    @Test
    fun `community presets appear first and keep the COMMUNITY_TUNED tier`() {
        val adapter = DeviceAdapter(
            key = "ayn_odin3",
            displayName = "AYN Odin 3",
            vendorAppPackage = null,
            fanAdapter = null,
            perfPresetAdapter = null,
            communityPresets = listOf(
                CommunityPreset(
                    name = "Underclock — Large",
                    description = "TheOldTaylor",
                    sourceUrl = "https://github.com/TheOldTaylor/Odin3-CPU-Underclock",
                    cpuPolicyMaxKhz = mapOf("0" to 1785600, "6" to 1958400),
                ),
            ),
        )
        val registry = mockk<DeviceAdapterRegistry>()
        every { registry.lookup("ayn_odin3") } returns adapter
        val report = reportWith(
            handheldKey = "ayn_odin3",
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(384, 1785, 3532)),
                policy(id = 6, freqsMhz = listOf(1017, 1958, 4320)),
            ),
        )

        val presets = PresetGenerator(registry).presetsFor(report)

        assertThat(presets.first().name).isEqualTo("Underclock — Large")
        assertThat(presets.first().verification).isEqualTo(VerificationTier.COMMUNITY_TUNED)
        // Built-ins still come after.
        assertThat(presets.map { it.name }).containsAtLeast("Battery Saver", "Max (stock ceiling)")
    }

    @Test
    fun `governor is omitted when none of the preferred governors are available`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0, 1, 2, 3),
                    availableFreqsKhz = listOf(500_000, 1_500_000),
                    availableGovernors = listOf("some_weird_oem_governor"),
                    currentMinKhz = 500_000,
                    currentMaxKhz = 1_500_000,
                    currentGovernor = "some_weird_oem_governor",
                    hardwareLimitsKhz = FreqRange(500_000, 1_500_000),
                ),
            ),
        )

        val presets = generatorWithoutAdapter().presetsFor(report)

        // None of our preferred governors are in the available list,
        // so the generator MUST NOT write one — leaving the kernel's
        // current governor alone.
        presets.forEach { preset ->
            assertThat(preset.cpuPolicyGovernor).isEmpty()
        }
    }

    @Test
    fun `three-cluster Thor gets all clusters set in every preset`() {
        // AYN Thor (Snapdragon 8 Gen 2 / kalama) real OPP tables:
        //   policy0 cores 0-2 little, max 2016 MHz
        //   policy3 cores 3-6 gold,   max 2803.2 MHz
        //   policy7 core 7   prime,   max 3187.2 MHz
        val report = reportWith(
            handheldKey = "ayn_thor",
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(307, 556, 902, 1228, 1555, 1900, 2016)),
                policy(id = 3, freqsMhz = listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803)),
                policy(id = 7, freqsMhz = listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187)),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        // EVERY preset must set EVERY cluster — the middle cluster
        // (policy3) must never be silently dropped, and every cluster
        // must get a MIN floor (the "don't leave min pinned high and
        // cook the device" fix).
        for (preset in presets) {
            assertThat(preset.cpuPolicyMaxKhz.keys).containsExactly(0, 3, 7)
            assertThat(preset.cpuPolicyMinKhz.keys).containsExactly(0, 3, 7)
            // Each min must be that cluster's lowest OPP, not inherited
            // from a previous high tune.
            assertThat(preset.cpuPolicyMinKhz[0]).isEqualTo(307_000)
            assertThat(preset.cpuPolicyMinKhz[3]).isEqualTo(499_000)
            assertThat(preset.cpuPolicyMinKhz[7]).isEqualTo(595_000)
        }

        // Max preset restores each cluster to its own ceiling.
        val max = presets.single { it.id == "builtin_max" }
        assertThat(max.cpuPolicyMaxKhz[0]).isEqualTo(2_016_000)
        assertThat(max.cpuPolicyMaxKhz[3]).isEqualTo(2_803_000)
        assertThat(max.cpuPolicyMaxKhz[7]).isEqualTo(3_187_000)

        // Balanced caps the prime (policy7) at a LOWER fraction of its
        // ceiling than the little cluster — asymmetric power curve.
        val balanced = presets.single { it.id == "builtin_balanced" }
        val primeFrac = balanced.cpuPolicyMaxKhz[7]!!.toDouble() / 3_187_000
        val littleFrac = balanced.cpuPolicyMaxKhz[0]!!.toDouble() / 2_016_000
        assertThat(primeFrac).isLessThan(littleFrac)
    }

    @Test
    fun `max preset returns to kernel's cpuinfo_max_freq`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0, 1, 2, 3),
                    availableFreqsKhz = listOf(500_000, 1_000_000, 1_500_000),
                    availableGovernors = listOf("schedutil"),
                    currentMinKhz = 500_000,
                    currentMaxKhz = 1_000_000, // currently capped low
                    currentGovernor = "schedutil",
                    hardwareLimitsKhz = FreqRange(500_000, 1_500_000),
                ),
            ),
        )
        val max = generatorWithoutAdapter().presetsFor(report).single { it.id == "builtin_max" }
        assertThat(max.cpuPolicyMaxKhz[0]).isEqualTo(1_500_000)
    }

    // --- helpers --------------------------------------------------------

    private fun generatorWithoutAdapter(): PresetGenerator {
        val registry = mockk<DeviceAdapterRegistry>()
        every { registry.lookup(any()) } returns null
        every { registry.lookup(null) } returns null
        return PresetGenerator(registry)
    }

    private fun policy(id: Int, freqsMhz: List<Int>) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsMhz.map { it * 1000 },
        availableGovernors = listOf("schedutil", "performance", "powersave"),
        currentMinKhz = freqsMhz.first() * 1000,
        currentMaxKhz = freqsMhz.last() * 1000,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsMhz.first() * 1000, freqsMhz.last() * 1000),
    )

    private fun reportWith(
        handheldKey: String?,
        family: GpuFamily,
        policies: List<CpuPolicyProbe>,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Generic",
            brand = "Generic",
            model = "Test",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = handheldKey,
        ),
        soc = SoCIdentity(socManufacturer = "", socModel = "", gpuFamily = family),
        privilege = PrivilegeTier.ROOT,
        rootKind = RootKind.MAGISK,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
    )
}
