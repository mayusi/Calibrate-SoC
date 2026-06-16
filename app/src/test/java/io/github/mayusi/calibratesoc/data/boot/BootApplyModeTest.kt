package io.github.mayusi.calibratesoc.data.boot

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
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.profiles.PresetSafetyGate
import io.mockk.coEvery
import io.mockk.mockk
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure-JVM tests for [resolveBootApplyMode] and the safety gate behaviour
 * when the boot receiver would apply a wrong-device profile.
 */
class BootApplyModeTest {

    // ── shared fixtures ───────────────────────────────────────────────────────

    private fun policy(id: Int, vararg freqsKhz: Int) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsKhz.toList(),
        availableGovernors = listOf("schedutil"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = freqsKhz.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun report(
        privilege: PrivilegeTier = PrivilegeTier.NONE,
        pserverSysfsLive: Boolean = false,
        sysfsDirectlyWritable: Boolean = false,
        handheldKey: String? = "ayn_odin3",
        policies: List<CpuPolicyProbe> = listOf(
            policy(0, 384_000, 2_745_600, 3_532_800),
            policy(6, 1_017_600, 3_072_000, 4_320_000),
        ),
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test",
            brand = "Test",
            model = "Test",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = handheldKey,
        ),
        soc = SoCIdentity(socManufacturer = "", socModel = "", gpuFamily = GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
        pserverSysfsLive = pserverSysfsLive,
        sysfsDirectlyWritable = sysfsDirectlyWritable,
    )

    // ── resolveBootApplyMode tests ────────────────────────────────────────────

    @Test
    fun `resolveBootApplyMode returns AUTO when root available`() {
        val mode = resolveBootApplyMode(report(privilege = PrivilegeTier.ROOT))
        assertThat(mode).isEqualTo(BootApplyMode.AUTO)
    }

    @Test
    fun `resolveBootApplyMode returns AUTO when Shizuku available`() {
        val mode = resolveBootApplyMode(report(privilege = PrivilegeTier.SHIZUKU))
        assertThat(mode).isEqualTo(BootApplyMode.AUTO)
    }

    @Test
    fun `resolveBootApplyMode returns AUTO when pserverSysfsLive`() {
        val mode = resolveBootApplyMode(
            report(privilege = PrivilegeTier.VENDOR_SETTINGS, pserverSysfsLive = true),
        )
        assertThat(mode).isEqualTo(BootApplyMode.AUTO)
    }

    @Test
    fun `resolveBootApplyMode returns AUTO when sysfsDirectlyWritable`() {
        val mode = resolveBootApplyMode(
            report(privilege = PrivilegeTier.NONE, sysfsDirectlyWritable = true),
        )
        assertThat(mode).isEqualTo(BootApplyMode.AUTO)
    }

    @Test
    fun `resolveBootApplyMode returns REMINDER when only VENDOR_SETTINGS tier`() {
        // VENDOR_SETTINGS without pserverSysfsLive or sysfsDirectlyWritable means
        // we can only write via the app context (Settings.System).  The receiver
        // cannot do that autonomously.
        val mode = resolveBootApplyMode(
            report(
                privilege = PrivilegeTier.VENDOR_SETTINGS,
                pserverSysfsLive = false,
                sysfsDirectlyWritable = false,
            ),
        )
        assertThat(mode).isEqualTo(BootApplyMode.REMINDER)
    }

    @Test
    fun `resolveBootApplyMode returns UNSUPPORTED when NONE and no live paths`() {
        val mode = resolveBootApplyMode(
            report(
                privilege = PrivilegeTier.NONE,
                pserverSysfsLive = false,
                sysfsDirectlyWritable = false,
            ),
        )
        assertThat(mode).isEqualTo(BootApplyMode.UNSUPPORTED)
    }

    // ── Safety gate: wrong-device applyOnBoot profile is blocked ─────────────

    /**
     * Verify that PresetSafetyGate.check rejects an RP6 preset at boot-apply
     * time on an Odin 3.  This is the gate the BootRevertReceiver relies on —
     * ProfileApplier.apply delegates to it before writing anything.
     */
    @Test
    fun `wrong-device applyOnBoot profile is blocked by safety gate`() = runTest {
        val rp6Preset = Preset(
            id = "rp6_ps2_gc",
            name = "RP6 PS2 GC",
            description = "RP6 only",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1_555_000, 3 to 1_920_000, 7 to 2_227_000),
            cpuPolicyMinKhz = mapOf(0 to 307_000, 3 to 499_000, 7 to 595_000),
            targetHandheldKeys = listOf("retroid_pocket6"),
        )

        val odin3Report = report(
            privilege = PrivilegeTier.ROOT,
            handheldKey = "ayn_odin3",
            policies = listOf(
                policy(0, 384_000, 2_745_600, 3_532_800),
                policy(6, 1_017_600, 3_072_000, 4_320_000),
            ),
        )

        // Safety gate check must return Rejected.
        val verdict = PresetSafetyGate.check(rp6Preset, odin3Report)
        assertThat(verdict).isInstanceOf(PresetSafetyGate.SafetyVerdict.Rejected::class.java)
        val reason = (verdict as PresetSafetyGate.SafetyVerdict.Rejected).reason
        assertThat(reason).contains("retroid_pocket6")
        assertThat(reason).contains("ayn_odin3")

        // And ProfileApplier.apply (the boot path) must also return Rejected.
        val writer = mockk<TunableWriter>()
        coEvery { writer.write(any(), any(), any(), any()) } answers {
            WriteResult.Success(id = firstArg(), previousValue = null, newValue = secondArg())
        }
        val applier = ProfileApplier(writer)
        val results = applier.apply(rp6Preset, odin3Report, "boot re-apply: RP6 PS2 GC")

        assertThat(results).hasSize(1)
        assertThat(results.single()).isInstanceOf(WriteResult.Rejected::class.java)
    }

    /**
     * A correctly-targeted RP6 profile IS applied when the device is RP6.
     */
    @Test
    fun `correct-device applyOnBoot profile passes safety gate`() = runTest {
        val rp6Preset = Preset(
            id = "rp6_ps2_gc",
            name = "RP6 PS2 GC",
            description = "RP6 only",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1_555_000, 3 to 1_920_000, 7 to 2_227_000),
            cpuPolicyMinKhz = mapOf(0 to 307_000, 3 to 499_000, 7 to 595_000),
            targetHandheldKeys = listOf("retroid_pocket6"),
        )

        val rp6Report = report(
            privilege = PrivilegeTier.ROOT,
            handheldKey = "retroid_pocket6",
            policies = listOf(
                policy(0, 307_000, 1_555_000, 2_016_000),
                policy(3, 499_000, 1_920_000, 2_803_000),
                policy(7, 595_000, 2_227_000, 3_187_000),
            ),
        )

        val verdict = PresetSafetyGate.check(rp6Preset, rp6Report)
        assertThat(verdict).isInstanceOf(PresetSafetyGate.SafetyVerdict.Ok::class.java)

        val writer = mockk<TunableWriter>()
        coEvery { writer.write(any(), any(), any(), any()) } answers {
            WriteResult.Success(id = firstArg(), previousValue = null, newValue = secondArg())
        }
        val applier = ProfileApplier(writer)
        val results = applier.apply(rp6Preset, rp6Report, "boot re-apply: RP6 PS2 GC")

        assertThat(results).isNotEmpty()
        assertThat(results.none { it is WriteResult.Rejected }).isTrue()
    }
}
