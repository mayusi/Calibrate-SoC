package io.github.mayusi.calibratesoc.ui.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfileConfig
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.LevelRange
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import org.junit.Test

/**
 * JVM unit tests for the pure static helpers in [AutoTdpViewModel].
 *
 * No Android, no mocks, no coroutines — all functions under test are
 * pure companions on the ViewModel companion object.
 *
 * Covers:
 *  1. Rung resolution (resolveRung): LIVE / SCRIPT / ADVISORY branches.
 *  2. Trigger precedence (resolveTriggerPrecedence): manual > idle/charge.
 */
class AutoTdpViewModelLogicTest {

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private fun makeReport(
        privilege: PrivilegeTier = PrivilegeTier.NONE,
        sysfsDirectlyWritable: Boolean = false,
        pserverSysfsLive: Boolean = false,
        aynGameAssistant: Boolean = false,
        langerhansOdinTools: Boolean = false,
    ): CapabilityReport = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test",
            brand = "Test",
            model = "TestDevice",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = null,
        ),
        soc = SoCIdentity("Qualcomm", "Snapdragon 8 Gen 2", GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(
            CpuPolicyProbe(
                policyId = 0,
                onlineCores = listOf(0, 1, 2, 3),
                currentMinKhz = 300_000,
                currentMaxKhz = 2_016_000,
                availableFreqsKhz = listOf(300_000, 1_000_000, 2_016_000),
                currentGovernor = "schedutil",
                availableGovernors = listOf("schedutil", "performance"),
                hardwareLimitsKhz = FreqRange(300_000, 2_016_000),
            ),
        ),
        gpu = GpuProbe(
            family = GpuFamily.ADRENO,
            rootPath = "/sys/class/kgsl/kgsl-3d0",
            currentMinHz = 390_000_000L,
            currentMaxHz = 800_000_000L,
            availableFreqsHz = listOf(390_000_000L, 600_000_000L, 800_000_000L),
            currentGovernor = "msm-adreno-tz",
            availableGovernors = listOf("msm-adreno-tz"),
            powerLevelRange = LevelRange(0, 6),
        ),
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(
            aynGameAssistant = aynGameAssistant,
            langerhansOdinTools = langerhansOdinTools,
            ayaSpace = false,
        ),
        sysfsDirectlyWritable = sysfsDirectlyWritable,
        pserverSysfsLive = pserverSysfsLive,
    )

    private val efficiencyConfig = AutoTdpProfileConfig(AutoTdpProfile.EFFICIENCY)
    private val balancedConfig = AutoTdpProfileConfig(AutoTdpProfile.BALANCED)
    private val idleState = AutoTdpRunState(status = AutoTdpStatus.IDLE)
    private val runningState = AutoTdpRunState(status = AutoTdpStatus.RUNNING, liveAvailable = true)
    private val liveUnavailableState = AutoTdpRunState(
        status = AutoTdpStatus.LIVE_UNAVAILABLE,
        liveUnavailableReason = "sysfs not writable",
    )

    // ─── resolveRung: ADVISORY ─────────────────────────────────────────────────

    @Test
    fun `resolveRung returns ADVISORY when report is null`() {
        val rung = AutoTdpViewModel.resolveRung(null, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.ADVISORY)
    }

    @Test
    fun `resolveRung returns SCRIPT for NONE privilege without sysfs writable`() {
        val report = makeReport(privilege = PrivilegeTier.NONE, sysfsDirectlyWritable = false)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    @Test
    fun `resolveRung returns SCRIPT for AYN_SETTINGS without sysfs writable`() {
        val report = makeReport(privilege = PrivilegeTier.AYN_SETTINGS, sysfsDirectlyWritable = false)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    @Test
    fun `resolveRung returns SCRIPT for SHIZUKU without sysfs writable`() {
        val report = makeReport(privilege = PrivilegeTier.SHIZUKU, sysfsDirectlyWritable = false)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    // ─── resolveRung: LIVE ────────────────────────────────────────────────────

    @Test
    fun `resolveRung returns LIVE for ROOT privilege`() {
        val report = makeReport(privilege = PrivilegeTier.ROOT, sysfsDirectlyWritable = false)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `resolveRung returns LIVE when sysfsDirectlyWritable is true regardless of privilege`() {
        val report = makeReport(privilege = PrivilegeTier.NONE, sysfsDirectlyWritable = true)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `resolveRung returns LIVE for ROOT + running state`() {
        val report = makeReport(privilege = PrivilegeTier.ROOT)
        val rung = AutoTdpViewModel.resolveRung(report, runningState)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    // ─── resolveRung: LIVE_UNAVAILABLE override ───────────────────────────────

    @Test
    fun `resolveRung returns SCRIPT when daemon reports LIVE_UNAVAILABLE even for ROOT`() {
        // The daemon is the authoritative arbiter — if it says writes failed,
        // we route to SCRIPT even though the probe said ROOT.
        val report = makeReport(privilege = PrivilegeTier.ROOT)
        val rung = AutoTdpViewModel.resolveRung(report, liveUnavailableState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    @Test
    fun `resolveRung returns SCRIPT when daemon reports LIVE_UNAVAILABLE for sysfsDirectlyWritable`() {
        val report = makeReport(privilege = PrivilegeTier.NONE, sysfsDirectlyWritable = true)
        val rung = AutoTdpViewModel.resolveRung(report, liveUnavailableState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    // ─── resolveRung: pserverSysfsLive → LIVE ────────────────────────────────

    @Test
    fun `resolveRung returns LIVE when pserverSysfsLive is true regardless of privilege`() {
        // PServer live path is verified: transact() confirmed working for our UID.
        val report = makeReport(privilege = PrivilegeTier.NONE, pserverSysfsLive = true)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `resolveRung returns LIVE when pserverSysfsLive is true on AYN_SETTINGS tier`() {
        val report = makeReport(privilege = PrivilegeTier.AYN_SETTINGS, pserverSysfsLive = true)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `resolveRung returns SCRIPT when pserverSysfsLive is false and no other live path`() {
        // PServer binder may exist on the device but whitelist step not yet run.
        val report = makeReport(privilege = PrivilegeTier.NONE, pserverSysfsLive = false, aynGameAssistant = true)
        val rung = AutoTdpViewModel.resolveRung(report, idleState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    @Test
    fun `resolveRung honours LIVE_UNAVAILABLE even when pserverSysfsLive is true`() {
        // The daemon is authoritative: if it reports LIVE_UNAVAILABLE, we drop to SCRIPT.
        val report = makeReport(privilege = PrivilegeTier.NONE, pserverSysfsLive = true)
        val rung = AutoTdpViewModel.resolveRung(report, liveUnavailableState)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    // ─── shouldShowPServerUnlockCta ──────────────────────────────────────────

    @Test
    fun `shouldShowPServerUnlockCta returns false when report is null`() {
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(null)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns true for AYN device not yet whitelisted`() {
        // AYN device with game assistant, no live path yet.
        val report = makeReport(aynGameAssistant = true, pserverSysfsLive = false)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report)).isTrue()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns true when langerhansOdinTools is present`() {
        // Odin device with OdinTools installed (strong signal for PServer presence).
        val report = makeReport(langerhansOdinTools = true, pserverSysfsLive = false)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report)).isTrue()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false when pserverSysfsLive is already true`() {
        // Already live — no CTA needed.
        val report = makeReport(aynGameAssistant = true, pserverSysfsLive = true)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false when sysfsDirectlyWritable is true`() {
        // Already live via direct sysfs — no CTA needed.
        val report = makeReport(aynGameAssistant = true, sysfsDirectlyWritable = true)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false when ROOT tier is active`() {
        val report = makeReport(privilege = PrivilegeTier.ROOT, aynGameAssistant = true)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false for non-AYN device`() {
        // No vendor apps present — PServer is unlikely to exist.
        val report = makeReport(privilege = PrivilegeTier.NONE)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report)).isFalse()
    }

    // ─── resolveTriggerPrecedence: manual wins ─────────────────────────────────

    @Test
    fun `resolveTriggerPrecedence returns manual config when manually on`() {
        val result = AutoTdpViewModel.resolveTriggerPrecedence(
            manuallyOn = true,
            manualConfig = balancedConfig,
            idleChargeSignal = efficiencyConfig,
        )
        assertThat(result).isEqualTo(balancedConfig)
    }

    @Test
    fun `resolveTriggerPrecedence returns manual config even when idle signal is null`() {
        val result = AutoTdpViewModel.resolveTriggerPrecedence(
            manuallyOn = true,
            manualConfig = balancedConfig,
            idleChargeSignal = null,
        )
        assertThat(result).isEqualTo(balancedConfig)
    }

    // ─── resolveTriggerPrecedence: trigger active when manual is off ───────────

    @Test
    fun `resolveTriggerPrecedence returns idle-charge signal when manual is off`() {
        val result = AutoTdpViewModel.resolveTriggerPrecedence(
            manuallyOn = false,
            manualConfig = null,
            idleChargeSignal = efficiencyConfig,
        )
        assertThat(result).isEqualTo(efficiencyConfig)
    }

    @Test
    fun `resolveTriggerPrecedence returns null when manual off and no trigger signal`() {
        val result = AutoTdpViewModel.resolveTriggerPrecedence(
            manuallyOn = false,
            manualConfig = null,
            idleChargeSignal = null,
        )
        assertThat(result).isNull()
    }

    // ─── resolveTriggerPrecedence: manual null config passthrough ─────────────

    @Test
    fun `resolveTriggerPrecedence returns null manual config when manually on and manual config is null`() {
        // Manual is ON but the user didn't pass a config (shouldn't happen in practice,
        // but the pure function handles it correctly).
        val result = AutoTdpViewModel.resolveTriggerPrecedence(
            manuallyOn = true,
            manualConfig = null,
            idleChargeSignal = efficiencyConfig,
        )
        // Manual wins — null means "stop" (the caller interprets null as stop).
        assertThat(result).isNull()
    }

    // ─── Rung enum sanity ─────────────────────────────────────────────────────

    @Test
    fun `AutoTdpRung has exactly three values`() {
        assertThat(AutoTdpRung.entries).hasSize(3)
        assertThat(AutoTdpRung.entries).containsExactly(
            AutoTdpRung.LIVE, AutoTdpRung.SCRIPT, AutoTdpRung.ADVISORY,
        )
    }
}
