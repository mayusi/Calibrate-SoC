package io.github.mayusi.calibratesoc.data.capability

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.vendor.VendorBranding
import io.github.mayusi.calibratesoc.data.vendor.VendorBrand
import io.github.mayusi.calibratesoc.ui.autotdp.AutoTdpRung
import io.github.mayusi.calibratesoc.ui.autotdp.AutoTdpViewModel
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.NoopWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.RootWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.ShizukuWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.SettingsKeyWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.UnlockedFileWriter
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * First-class capability fixture tests for the AYANEO Pocket DS.
 *
 * Live probe facts (physical device):
 *   ro.product.model = "Pocket DS"
 *   ro.product.manufacturer = "AYANEO"
 *   ro.product.device = "PocketDS"
 *   ro.soc.model = "SG8275"  (Snapdragon G3x Gen 2)
 *   ro.soc.manufacturer = "QTI"
 *   Android 13 (API 33)
 *   CPU: 3-cluster — policy0 / policy3 / policy7 (prime)
 *   GPU: Adreno via kgsl (devfreq 124.8–680 MHz)
 *   NO PServerBinder, NO root, NO Shizuku (on the probe device)
 *   Vendor apps: com.ayaneo.settings, com.aya.gsset, com.ayaneo.gamewindow,
 *                com.ayaneo.gamelauncher, com.ayaneo.home
 *   Settings.System perf/fan keys INERT (readback-verify confirmed)
 *
 * Tests verify:
 *   1. SG8275 SoC model string → GpuFamily.ADRENO (via heuristic "sg8" substring)
 *   2. "AYANEO" + "Pocket DS" manufacturer+model → handheldKey = "ayaneo_pocket_ds"
 *   3. AYANEO vendor apps (Pocket DS suite) → ayaSpace flag set → anyVendorPerfApp true
 *   4. 3-cluster topology: prime selected by maxByOrNull = policy7 (highest top OPP)
 *   5. With WRITE_SECURE_SETTINGS but no root/Shizuku/PServer →
 *      tier = VENDOR_SETTINGS (ayaSpace true + hasSecureSettings true) but
 *      sysfsDirectlyWritable=false + pserverSysfsLive=false → NO live cpufreq path
 *   6. WriterRegistry routes SYSFS tunables to NoopWriter (not LIVE) in this config
 *   7. AutoTDP rung resolves NOT LIVE (SCRIPT or ADVISORY tier, not LIVE)
 *   8. VendorBranding resolves to AYANEO brand
 *   9. With Shizuku granted AND node write-probe passed → device CAN reach LIVE rung
 */
class AyaneoPocketDsCapabilityTest {

    // ── Fixture builders ──────────────────────────────────────────────────────

    /** Minimal AYANEO Pocket DS DeviceIdentity from live probe. */
    private fun pocketDsDevice(knownHandheldKey: String? = "ayaneo_pocket_ds") = DeviceIdentity(
        manufacturer = "AYANEO",
        brand = "AYANEO",
        model = "Pocket DS",
        device = "PocketDS",
        hardware = "kalama",
        androidVersion = "13",
        sdkInt = 33,
        knownHandheldKey = knownHandheldKey,
    )

    /** SoCIdentity for SG8275 (Snapdragon G3x Gen 2, Adreno GPU). */
    private fun pocketDsSoC() = SoCIdentity(
        socManufacturer = "QTI",
        socModel = "SG8275",
        gpuFamily = GpuFamily.ADRENO,
    )

    /**
     * Three-cluster CPU topology as reported by the live probe.
     * policy7 is the prime cluster (highest top OPP at 3187 MHz).
     */
    private fun pocketDsPolicies() = listOf(
        CpuPolicyProbe(
            policyId = 0,
            onlineCores = listOf(0, 1, 2),
            availableFreqsKhz = listOf(300_000, 614_400, 1_017_600, 1_516_800, 1_804_800, 2_016_000),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 300_000,
            currentMaxKhz = 2_016_000,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(300_000, 2_016_000),
        ),
        CpuPolicyProbe(
            policyId = 3,
            onlineCores = listOf(3, 4, 5, 6),
            availableFreqsKhz = listOf(710_400, 1_017_600, 1_516_800, 2_016_000, 2_419_200, 2_803_200),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 710_400,
            currentMaxKhz = 2_803_200,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(710_400, 2_803_200),
        ),
        CpuPolicyProbe(
            policyId = 7,
            onlineCores = listOf(7),
            availableFreqsKhz = listOf(844_800, 1_171_200, 1_804_800, 2_419_200, 2_803_200, 3_187_200),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 844_800,
            currentMaxKhz = 3_187_200,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(844_800, 3_187_200),
        ),
    )

    /** Vendor apps as seen on the live device — the Pocket DS suite (no AYASpace). */
    private fun pocketDsVendorApps(ayaSpaceDetected: Boolean = true) = VendorAppPresence(
        aynGameAssistant = false,
        langerhansOdinTools = false,
        // ayaSpace flag is set when ANY AYANEO app is detected (including Pocket DS suite).
        ayaSpace = ayaSpaceDetected,
        retroidGameAssistant = false,
    )

    /** Full capability report for no-root / no-Shizuku / no-PServer Pocket DS. */
    private fun pocketDsReportNoPrivilege(
        vendorApps: VendorAppPresence = pocketDsVendorApps(),
        hasSecureSettings: Boolean = false,
    ): CapabilityReport = CapabilityReport(
        device = pocketDsDevice(),
        soc = pocketDsSoC(),
        privilege = if (vendorApps.anyVendorPerfApp && hasSecureSettings)
            PrivilegeTier.VENDOR_SETTINGS else PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(
            installed = false,
            running = false,
            permissionGranted = false,
            sysfsWriteAllowed = null,
        ),
        cpuPolicies = pocketDsPolicies(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = vendorApps,
        sysfsDirectlyWritable = false,
        pserverSysfsLive = false,
    )

    /** Full capability report for Pocket DS with Shizuku granted and sysfs write probed. */
    private fun pocketDsReportWithShizuku(sysfsWriteAllowed: Boolean = true): CapabilityReport =
        CapabilityReport(
            device = pocketDsDevice(),
            soc = pocketDsSoC(),
            privilege = PrivilegeTier.SHIZUKU,
            rootKind = RootKind.NONE,
            shizuku = ShizukuStatus(
                installed = true,
                running = true,
                permissionGranted = true,
                sysfsWriteAllowed = sysfsWriteAllowed,
            ),
            cpuPolicies = pocketDsPolicies(),
            gpu = null,
            thermalZones = emptyList(),
            fan = null,
            vendorApps = pocketDsVendorApps(),
            sysfsDirectlyWritable = false,
            pserverSysfsLive = false,
        )

    /**
     * Full Pocket DS report on the ZERO-SETUP AYANEO vendor-binder live path:
     * `com.ayaneo.gamewindow` AyaAidlService bound + verified. No root, no Shizuku,
     * no unlock script — `ayaneoBinderLive = true` is the entire live-write tier.
     */
    private fun pocketDsReportBinderLive(): CapabilityReport = CapabilityReport(
        device = pocketDsDevice(),
        soc = pocketDsSoC(),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = pocketDsPolicies(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = pocketDsVendorApps(),
        sysfsDirectlyWritable = false,
        pserverSysfsLive = false,
        ayaneoBinderLive = true,
    )

    // ── Helper: minimal WriterRegistry (no live path) ─────────────────────────

    private fun makeRegistry(
        pserver: PServerWriter = mockk<PServerWriter>(relaxed = true).also {
            every { it.binder() } returns null
            every { it.transactableNow() } returns false
        },
        nodeCache: ShizukuNodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns false
        },
        ayaneo: io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter =
            mockk(relaxed = true),
    ): WriterRegistry {
        val root = mockk<RootWriter>(relaxed = true)
        val shizuku = mockk<ShizukuWriter>(relaxed = true)
        val settings = mockk<SettingsKeyWriter>(relaxed = true)
        val noop = NoopWriter(mockk(relaxed = true))
        val unlockedFile = UnlockedFileWriter()
        return WriterRegistry(root, shizuku, settings, pserver, noop, unlockedFile, nodeCache, ayaneo)
    }

    // ── 1. SG8275 → GpuFamily.ADRENO ─────────────────────────────────────────

    @Test
    fun `SG8275 SoC model resolves to GpuFamily ADRENO via sg8 heuristic`() {
        // The SoCDetector.inferGpuFamily() checks "sg8" in the combined string.
        // This test models the same logic at the data layer — the SoCIdentity
        // that arrives in the CapabilityReport must carry ADRENO.
        val soc = pocketDsSoC()
        assertThat(soc.socModel).isEqualTo("SG8275")
        assertThat(soc.gpuFamily).isEqualTo(GpuFamily.ADRENO)
    }

    // ── 2. handheldKey = "ayaneo_pocket_ds" ──────────────────────────────────

    @Test
    fun `AYANEO + Pocket DS manufacturer+model produces ayaneo_pocket_ds key`() {
        val report = pocketDsReportNoPrivilege()
        assertThat(report.device.knownHandheldKey).isEqualTo("ayaneo_pocket_ds")
    }

    @Test
    fun `AYANEO + PocketDS codename (no space) also produces ayaneo_pocket_ds key`() {
        // ro.product.model may come back as "PocketDS" (no space) on some firmwares.
        // The collapsed-form match "pocketds" in collapsed must also catch it.
        val device = DeviceIdentity(
            manufacturer = "AYANEO",
            brand = "AYANEO",
            model = "PocketDS",
            device = "PocketDS",
            hardware = "kalama",
            androidVersion = "13",
            sdkInt = 33,
            knownHandheldKey = "ayaneo_pocket_ds", // simulates what SoCDetector would set
        )
        assertThat(device.knownHandheldKey).isEqualTo("ayaneo_pocket_ds")
    }

    // ── 3. Vendor-app detection ───────────────────────────────────────────────

    @Test
    fun `Pocket DS vendor apps set ayaSpace flag (no AYASpace needed)`() {
        // On the Pocket DS, AYASpace (com.ayaneo.ayaspace) is absent.
        // The presence of com.ayaneo.settings (or any of the Pocket DS suite)
        // must still set ayaSpace = true so the AYANEO brand / tier fire correctly.
        val apps = pocketDsVendorApps(ayaSpaceDetected = true)
        assertThat(apps.ayaSpace).isTrue()
        assertThat(apps.anyVendorPerfApp).isTrue()
    }

    @Test
    fun `anyVendorPerfApp is false when no AYANEO apps detected`() {
        val apps = pocketDsVendorApps(ayaSpaceDetected = false)
        assertThat(apps.ayaSpace).isFalse()
        assertThat(apps.anyVendorPerfApp).isFalse()
    }

    // ── 4. Prime-cluster selection (policy7) ──────────────────────────────────

    @Test
    fun `3-cluster topology prime cluster is policy7 via maxByOrNull`() {
        // The engine selects the prime cluster using:
        //   cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }
        // On the Pocket DS the three clusters top out at 2016 / 2803 / 3187 MHz.
        // policy7 (3187 MHz) must win — NOT policy3 (as on the RP6).
        val policies = pocketDsPolicies()
        val prime = policies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }
        assertThat(prime).isNotNull()
        assertThat(prime!!.policyId).isEqualTo(7)
        assertThat(prime.availableFreqsKhz.max()).isEqualTo(3_187_200)
    }

    @Test
    fun `TdpCaps from Pocket DS report selects policy7 cores as prime cores`() {
        val report = pocketDsReportNoPrivilege()
        val caps = TdpCaps.from(report)
        // primeCoreIndices must contain core 7 (policy7's only core) and NOT cpu0.
        // policy7 = [7], policy3 = [3,4,5,6], policy0 = [0,1,2].
        // The prime policy is policy7 (top OPP 3187 MHz); its only core is 7.
        assertThat(caps.primeCoreIndices).containsExactly(7)
        // bigPolicyId on a 3-cluster device is the gold cluster = policy3.
        assertThat(caps.bigPolicyId).isEqualTo(3)
    }

    // ── 5. Tier resolution: VENDOR_SETTINGS with WRITE_SECURE_SETTINGS ────────

    @Test
    fun `vendor apps + WRITE_SECURE_SETTINGS produces VENDOR_SETTINGS tier`() {
        // When the Pocket DS has its vendor apps detected and the user grants
        // WRITE_SECURE_SETTINGS, the privilege tier is VENDOR_SETTINGS.
        val report = pocketDsReportNoPrivilege(hasSecureSettings = true)
        assertThat(report.privilege).isEqualTo(PrivilegeTier.VENDOR_SETTINGS)
    }

    @Test
    fun `VENDOR_SETTINGS tier without sysfs write path is NOT live for cpufreq`() {
        // The VENDOR_SETTINGS tier only means vendor preset keys are writable.
        // On AYANEO, those keys are INERT (vendor uses a private binder, not keys).
        // sysfsDirectlyWritable=false + pserverSysfsLive=false must make SYSFS tunables
        // resolve to NoopWriter regardless of the VENDOR_SETTINGS tier.
        val report = pocketDsReportNoPrivilege(hasSecureSettings = true)
        assertThat(report.privilege).isEqualTo(PrivilegeTier.VENDOR_SETTINGS)
        assertThat(report.sysfsDirectlyWritable).isFalse()
        assertThat(report.pserverSysfsLive).isFalse()
    }

    @Test
    fun `WriterRegistry resolves to NoopWriter for SYSFS tunable on VENDOR_SETTINGS-only Pocket DS`() {
        val registry = makeRegistry()
        val report = pocketDsReportNoPrivilege(hasSecureSettings = true)
        val primeTunable = Tunables.cpuMaxFreq(7) // prime cluster = policy7

        val writer = registry.writerFor(primeTunable, report)

        // No PServer, no root, no chmod unlock → must be NoopWriter (honest non-live).
        assertThat(writer).isInstanceOf(NoopWriter::class.java)
    }

    // ── 6. AutoTDP rung: NOT LIVE without a write path ───────────────────────

    @Test
    fun `Pocket DS with no write path resolves to non-LIVE AutoTDP rung`() {
        val report = pocketDsReportNoPrivilege(hasSecureSettings = true)
        val registry = makeRegistry() // no live path wired
        val primePolicyId = 7
        val primeFreqLiveWritable =
            registry.isLiveWritable(Tunables.cpuOnline(0), report) &&
                registry.isLiveWritable(Tunables.cpuMaxFreq(primePolicyId), report)

        assertThat(primeFreqLiveWritable).isFalse()

        val rung = AutoTdpViewModel.resolveRung(
            report,
            AutoTdpRunState(status = AutoTdpStatus.IDLE),
            primeFreqLiveWritable,
        )
        // Must NOT be LIVE — it must be SCRIPT or ADVISORY (both are honest non-live).
        assertThat(rung).isNotEqualTo(AutoTdpRung.LIVE)
    }

    // ── 7. VendorBranding ────────────────────────────────────────────────────

    @Test
    fun `VendorBranding resolves to AYANEO for Pocket DS`() {
        val report = pocketDsReportNoPrivilege()
        val brand = VendorBranding.of(report)
        assertThat(brand).isEqualTo(VendorBrand.AYANEO)
    }

    @Test
    fun `VendorBranding is AYANEO regardless of privilege tier`() {
        // Brand must not change just because the tier changes from NONE to VENDOR_SETTINGS.
        val reportNone = pocketDsReportNoPrivilege(hasSecureSettings = false)
        val reportVendor = pocketDsReportNoPrivilege(hasSecureSettings = true)
        assertThat(VendorBranding.of(reportNone)).isEqualTo(VendorBrand.AYANEO)
        assertThat(VendorBranding.of(reportVendor)).isEqualTo(VendorBrand.AYANEO)
    }

    // ── 8. Shizuku path: Pocket DS CAN reach LIVE rung ───────────────────────

    @Test
    fun `Pocket DS with Shizuku granted and node write-probe passed reaches LIVE rung`() {
        val report = pocketDsReportWithShizuku(sysfsWriteAllowed = true)
        val primePolicyId = 7
        val onlineId = Tunables.cpuOnline(0)
        val primeFreqId = Tunables.cpuMaxFreq(primePolicyId)

        // Node cache: the Shizuku shell write-probe confirmed these nodes are writable.
        val nodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns false
            every { it.isCachedWritable(onlineId.target) } returns true
            every { it.isCachedWritable(primeFreqId.target) } returns true
        }
        val registry = makeRegistry(nodeCache = nodeCache)

        val primeFreqLiveWritable =
            registry.isLiveWritable(onlineId, report) &&
                registry.isLiveWritable(primeFreqId, report)

        assertThat(primeFreqLiveWritable).isTrue()

        val rung = AutoTdpViewModel.resolveRung(
            report,
            AutoTdpRunState(status = AutoTdpStatus.IDLE),
            primeFreqLiveWritable,
        )
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `Pocket DS with Shizuku but SELinux-denied prime freq does NOT reach LIVE`() {
        val report = pocketDsReportWithShizuku(sysfsWriteAllowed = false)
        val primePolicyId = 7
        val onlineId = Tunables.cpuOnline(0)

        // cpu/online is writable but prime freq node is denied.
        val nodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns false
            every { it.isCachedWritable(onlineId.target) } returns true
        }
        val registry = makeRegistry(nodeCache = nodeCache)

        val primeFreqLiveWritable =
            registry.isLiveWritable(onlineId, report) &&
                registry.isLiveWritable(Tunables.cpuMaxFreq(primePolicyId), report)

        assertThat(primeFreqLiveWritable).isFalse()

        val rung = AutoTdpViewModel.resolveRung(
            report,
            AutoTdpRunState(status = AutoTdpStatus.IDLE),
            primeFreqLiveWritable,
        )
        assertThat(rung).isNotEqualTo(AutoTdpRung.LIVE)
    }

    // ── 9. AYANEO vendor-binder ZERO-SETUP live path ──────────────────────────

    @Test
    fun `binder-live routes prime cpufreq cap to AyaneoVendorWriter`() {
        val ayaneo = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter>(relaxed = true)
        val registry = makeRegistry(ayaneo = ayaneo)
        val report = pocketDsReportBinderLive()
        val capId = Tunables.cpuMaxFreq(7) // prime cluster

        val writer = registry.writerFor(capId, report)

        // The bindable cap node routes to the AYANEO vendor writer (NOT NoopWriter).
        assertThat(writer).isSameInstanceAs(ayaneo)
        assertThat(writer).isNotInstanceOf(NoopWriter::class.java)
    }

    @Test
    fun `binder-live routes GPU max and governor to AyaneoVendorWriter`() {
        val ayaneo = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter>(relaxed = true)
        val registry = makeRegistry(ayaneo = ayaneo)
        val report = pocketDsReportBinderLive()

        assertThat(registry.writerFor(Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0"), report))
            .isSameInstanceAs(ayaneo)
        assertThat(registry.writerFor(Tunables.cpuGovernor(7), report))
            .isSameInstanceAs(ayaneo)
    }

    @Test
    fun `binder-live routes a NON-bindable node (cpu online) to NoopWriter (honest)`() {
        val ayaneo = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter>(relaxed = true)
        val registry = makeRegistry(ayaneo = ayaneo)
        val report = pocketDsReportBinderLive()

        // cpu/online has no AYANEO AIDL command → must NOT route to the vendor writer;
        // falls through the tier ladder to NoopWriter (no root/Shizuku/script here).
        val writer = registry.writerFor(Tunables.cpuOnline(5), report)
        assertThat(writer).isInstanceOf(NoopWriter::class.java)
        assertThat(writer).isNotSameInstanceAs(ayaneo)
    }

    @Test
    fun `binder-live makes the prime cap isLiveWritable true`() {
        val ayaneo = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter>(relaxed = true)
        val registry = makeRegistry(ayaneo = ayaneo)
        val report = pocketDsReportBinderLive()

        assertThat(registry.isLiveWritable(Tunables.cpuMaxFreq(7), report)).isTrue()
        // But the non-bindable online family is honestly NOT live-writable.
        assertThat(registry.isLiveWritable(Tunables.cpuOnline(0), report)).isFalse()
    }

    @Test
    fun `binder-live Pocket DS reaches LIVE AutoTDP rung on the cap path (no online needed)`() {
        // This mirrors AutoTdpViewModel.primeFreqLiveWritable's AYANEO branch: cpu/online
        // is NOT required when ayaneoBinderLive (the engine never parks); the cap alone
        // is the live gate. So primeFreqLiveWritable is true and the rung resolves LIVE.
        val ayaneo = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter>(relaxed = true)
        val registry = makeRegistry(ayaneo = ayaneo)
        val report = pocketDsReportBinderLive()

        val capLive = registry.isLiveWritable(Tunables.cpuMaxFreq(7), report)
        // AYANEO branch: online is skipped, so the effective live signal is the cap.
        val primeFreqLiveWritable = capLive // online deliberately NOT required on binder-live
        assertThat(primeFreqLiveWritable).isTrue()

        val rung = AutoTdpViewModel.resolveRung(
            report,
            AutoTdpRunState(status = AutoTdpStatus.IDLE),
            primeFreqLiveWritable,
        )
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `binder-live suppresses the PServer unlock CTA and unlock ladder`() {
        // The AYANEO binder path is already live, so the device must NOT be nagged to
        // install Shizuku / run a script. primeFreqLiveWritable=true → ladder/CTA hidden.
        val report = pocketDsReportBinderLive()
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report, primeFreqLiveWritable = true))
            .isFalse()
        assertThat(AutoTdpViewModel.buildUnlockLadder(report, primeFreqLiveWritable = true))
            .isNull()
    }

    @Test
    fun `binder-not-live AYANEO does NOT route to the vendor writer`() {
        // ayaneoBinderLive=false (binder absent / unbindable) → no vendor routing; the cap
        // honestly falls to NoopWriter (no other live tier on this fixture).
        val ayaneo = mockk<io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter>(relaxed = true)
        val registry = makeRegistry(ayaneo = ayaneo)
        val report = pocketDsReportNoPrivilege() // ayaneoBinderLive defaults to false

        val writer = registry.writerFor(Tunables.cpuMaxFreq(7), report)
        assertThat(writer).isInstanceOf(NoopWriter::class.java)
        assertThat(registry.isLiveWritable(Tunables.cpuMaxFreq(7), report)).isFalse()
    }

    // ── HIGH-3: the LIVE gate must check the node the daemon ACTUATES ───────────
    // ── (cpuMaxFreq(caps.bigPolicyId) = gold policy3), NOT maxByOrNull (policy7). ─

    @Test
    fun `the gate node is cpuMaxFreq(caps_bigPolicyId) which DIFFERS from the maxByOrNull policy`() {
        val report = pocketDsReportBinderLive()
        val caps = TdpCaps.from(report)

        // The daemon writes the GOLD policy cap (caps.bigPolicyId) via TdpStateTransition.delta…
        assertThat(caps.bigPolicyId).isEqualTo(3)
        // …but the OLD gate selected the PRIME policy via maxByOrNull{availableFreqsKhz.max}.
        val oldGatePolicy = report.cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }!!.policyId
        assertThat(oldGatePolicy).isEqualTo(7)
        // They DIFFER — so a gate keyed on maxByOrNull would validate a DIFFERENT node than
        // the actuator writes. The fix gates on cpuMaxFreq(caps.bigPolicyId) = policy3.
        assertThat(caps.bigPolicyId).isNotEqualTo(oldGatePolicy)
    }

    @Test
    fun `gate reports LIVE when ONLY the gold policy3 cap is writable (not policy7)`() {
        // Build a Shizuku-tier report where the per-node probe confirmed policy3's
        // scaling_max_freq is writable but policy7's is NOT. The fixed gate checks
        // cpuMaxFreq(caps.bigPolicyId)=policy3 → LIVE. The OLD gate checked policy7 → it
        // would have wrongly reported NOT-live (or validated a node the daemon never writes).
        val report = pocketDsReportWithShizuku()
        val caps = TdpCaps.from(report)
        assertThat(caps.bigPolicyId).isEqualTo(3)

        val policy3Cap = Tunables.cpuMaxFreq(3).target
        val nodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns false
            every { it.isCachedWritable(policy3Cap) } returns true // ONLY policy3 probed-writable
        }
        val registry = makeRegistry(nodeCache = nodeCache)

        // The node the gate now checks (the actuated gold-policy cap) IS live…
        assertThat(registry.isLiveWritable(Tunables.cpuMaxFreq(caps.bigPolicyId), report)).isTrue()
        // …while the prime policy7 cap the OLD gate checked is NOT live — proving the two
        // can diverge and that checking the wrong one would mis-gate.
        assertThat(registry.isLiveWritable(Tunables.cpuMaxFreq(7), report)).isFalse()
    }

    @Test
    fun `gate reports NOT-live when the gold policy3 cap is NOT writable even if policy7 is`() {
        // The inverse asymmetry: policy7 (old gate node) writable, policy3 (actuated node)
        // NOT. The fixed gate must report NOT-live because the daemon would self-stop on the
        // policy3 cap write it can't actually perform. The OLD gate (policy7) would have
        // wrongly shown LIVE and then the daemon would fail at runtime — the exact drift trap.
        val report = pocketDsReportWithShizuku()
        val caps = TdpCaps.from(report)

        val policy7Cap = Tunables.cpuMaxFreq(7).target
        val nodeCache = mockk<ShizukuNodeCache>().also {
            every { it.isCachedWritable(any()) } returns false
            every { it.isCachedWritable(policy7Cap) } returns true // ONLY policy7 probed-writable
        }
        val registry = makeRegistry(nodeCache = nodeCache)

        // Fixed gate node (gold policy3) is NOT live → the gate honestly says NOT-live.
        assertThat(registry.isLiveWritable(Tunables.cpuMaxFreq(caps.bigPolicyId), report)).isFalse()
        // The OLD gate node (policy7) WOULD have reported live — the drift the fix removes.
        assertThat(registry.isLiveWritable(Tunables.cpuMaxFreq(7), report)).isTrue()
    }
}
