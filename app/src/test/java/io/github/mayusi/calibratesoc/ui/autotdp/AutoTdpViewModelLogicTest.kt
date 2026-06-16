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

    // ─── resolveRung ──────────────────────────────────────────────────────────
    //
    // resolveRung is now a pure function of (report, state, primeFreqLiveWritable).
    // The mapping from tier/flags → primeFreqLiveWritable is delegated to
    // WriterRegistry.isLiveWritable (the single source of truth) and is exercised
    // by ShizukuWriterRegistryTest / TierResolutionOrderTest / PServerWriterLiveTest.
    // The end-to-end "Shizuku-only reaches LIVE" path is proven in
    // TierResolutionOrderTest.`Shizuku-only device reaches LIVE rung end-to-end`.
    // Here we test the rung-decision logic itself given that boolean.

    @Test
    fun `resolveRung returns ADVISORY when report is null`() {
        val rung = AutoTdpViewModel.resolveRung(null, idleState, primeFreqLiveWritable = false)
        assertThat(rung).isEqualTo(AutoTdpRung.ADVISORY)
    }

    @Test
    fun `resolveRung returns SCRIPT when prime freq not live-writable`() {
        val report = makeReport(privilege = PrivilegeTier.NONE)
        val rung = AutoTdpViewModel.resolveRung(report, idleState, primeFreqLiveWritable = false)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    @Test
    fun `resolveRung returns SCRIPT for VENDOR_SETTINGS when prime freq not live-writable`() {
        // VENDOR_SETTINGS alone is the vendor-preset surface, NOT a live cpufreq
        // path — so the prime cap is NOT live-writable and we route to SCRIPT.
        val report = makeReport(privilege = PrivilegeTier.VENDOR_SETTINGS)
        val rung = AutoTdpViewModel.resolveRung(report, idleState, primeFreqLiveWritable = false)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    @Test
    fun `resolveRung returns SCRIPT for SHIZUKU when node probe denied`() {
        // Shizuku connected but the per-node write-probe failed (vendor SELinux
        // denial) → prime freq not live-writable → SCRIPT.
        val report = makeReport(privilege = PrivilegeTier.SHIZUKU)
        val rung = AutoTdpViewModel.resolveRung(report, idleState, primeFreqLiveWritable = false)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    // ─── resolveRung: LIVE when prime freq is live-writable ───────────────────

    @Test
    fun `resolveRung returns LIVE when prime freq is live-writable`() {
        // Vendor-agnostic: this is true for ROOT, unlock-chmod, PServer, OR a
        // Shizuku-only device whose per-node probe confirmed the write.
        val report = makeReport(privilege = PrivilegeTier.NONE)
        val rung = AutoTdpViewModel.resolveRung(report, idleState, primeFreqLiveWritable = true)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `resolveRung returns LIVE for SHIZUKU-only device with probe-confirmed prime freq`() {
        // THE KEY GENERALIZATION: a no-root, no-PServer device reaches LIVE when
        // the Shizuku shell write-probe confirmed scaling_max_freq is writable.
        val report = makeReport(privilege = PrivilegeTier.SHIZUKU)
        val rung = AutoTdpViewModel.resolveRung(report, idleState, primeFreqLiveWritable = true)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    @Test
    fun `resolveRung returns LIVE when prime freq live-writable + running state`() {
        val report = makeReport(privilege = PrivilegeTier.ROOT)
        val rung = AutoTdpViewModel.resolveRung(report, runningState, primeFreqLiveWritable = true)
        assertThat(rung).isEqualTo(AutoTdpRung.LIVE)
    }

    // ─── resolveRung: LIVE_UNAVAILABLE override ───────────────────────────────

    @Test
    fun `resolveRung returns SCRIPT when daemon reports LIVE_UNAVAILABLE even if prime freq writable`() {
        // The daemon is the authoritative arbiter — if it says writes failed,
        // we route to SCRIPT even though the probe said the node was writable.
        val report = makeReport(privilege = PrivilegeTier.ROOT)
        val rung = AutoTdpViewModel.resolveRung(report, liveUnavailableState, primeFreqLiveWritable = true)
        assertThat(rung).isEqualTo(AutoTdpRung.SCRIPT)
    }

    // ─── shouldShowPServerUnlockCta (now consistent with the live check) ──────

    @Test
    fun `shouldShowPServerUnlockCta returns false when report is null`() {
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(null, primeFreqLiveWritable = false)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns true for AYN device not yet whitelisted`() {
        // AYN device with game assistant, no live path yet.
        val report = makeReport(aynGameAssistant = true, pserverSysfsLive = false)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report, primeFreqLiveWritable = false)).isTrue()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns true when langerhansOdinTools is present`() {
        // Odin device with OdinTools installed (strong signal for PServer presence).
        val report = makeReport(langerhansOdinTools = true, pserverSysfsLive = false)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report, primeFreqLiveWritable = false)).isTrue()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false when already live by any path`() {
        // Already live (root / Shizuku-probed / direct sysfs / PServer) — no CTA.
        val report = makeReport(aynGameAssistant = true)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report, primeFreqLiveWritable = true)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false when Shizuku-live on an AYN device`() {
        // GENERALIZATION: an AYN device that is already LIVE via the Shizuku-probed
        // path must NOT be nagged to unlock PServer it does not need.
        val report = makeReport(privilege = PrivilegeTier.SHIZUKU, aynGameAssistant = true)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report, primeFreqLiveWritable = true)).isFalse()
    }

    @Test
    fun `shouldShowPServerUnlockCta returns false for non-AYN device`() {
        // No vendor apps present — PServer is unlikely to exist.
        val report = makeReport(privilege = PrivilegeTier.NONE)
        assertThat(AutoTdpViewModel.shouldShowPServerUnlockCta(report, primeFreqLiveWritable = false)).isFalse()
    }

    // ─── buildUnlockLadder (vendor-neutral path ladder) ──────────────────────

    @Test
    fun `buildUnlockLadder returns null when already live`() {
        val report = makeReport(privilege = PrivilegeTier.SHIZUKU)
        assertThat(AutoTdpViewModel.buildUnlockLadder(report, primeFreqLiveWritable = true)).isNull()
    }

    @Test
    fun `buildUnlockLadder returns null when report is null`() {
        assertThat(AutoTdpViewModel.buildUnlockLadder(null, primeFreqLiveWritable = false)).isNull()
    }

    @Test
    fun `buildUnlockLadder offers all three rungs in order for a generic no-root device`() {
        // Generic Android handheld, no Shizuku, no root, no vendor app.
        val report = makeReport(privilege = PrivilegeTier.NONE)
        val ladder = AutoTdpViewModel.buildUnlockLadder(report, primeFreqLiveWritable = false)
        assertThat(ladder).isNotNull()
        assertThat(ladder!!.steps.map { it.kind }).containsExactly(
            UnlockStepKind.SHIZUKU,
            UnlockStepKind.UNLOCK_SCRIPT,
            UnlockStepKind.ROOT,
        ).inOrder()
        // No vendor binder on a generic device.
        assertThat(ladder.vendorBinderPathAvailable).isFalse()
    }

    @Test
    fun `buildUnlockLadder marks Shizuku DONE_BUT_INSUFFICIENT when granted but kernel blocks writes`() {
        // Shizuku granted, but the node-probe failed (vendor SELinux denial) so the
        // device is NOT live. The ladder must honestly flag Shizuku as granted-but-
        // insufficient and still offer the other rungs.
        val report = makeReport(privilege = PrivilegeTier.SHIZUKU).copy(
            shizuku = io.github.mayusi.calibratesoc.data.capability.ShizukuStatus(
                installed = true, running = true, permissionGranted = true,
                sysfsWriteAllowed = false,
            ),
        )
        val ladder = AutoTdpViewModel.buildUnlockLadder(report, primeFreqLiveWritable = false)!!
        val shizukuStep = ladder.steps.first { it.kind == UnlockStepKind.SHIZUKU }
        assertThat(shizukuStep.state).isEqualTo(UnlockStepState.DONE_BUT_INSUFFICIENT)
    }

    @Test
    fun `buildUnlockLadder lights vendor binder path on AYN`() {
        val report = makeReport(aynGameAssistant = true)
        val ladder = AutoTdpViewModel.buildUnlockLadder(report, primeFreqLiveWritable = false)!!
        assertThat(ladder.vendorBinderPathAvailable).isTrue()
    }

    @Test
    fun `buildUnlockLadder marks root AVAILABLE when root present but not opted-in`() {
        val report = makeReport(privilege = PrivilegeTier.NONE).copy(
            rootKind = io.github.mayusi.calibratesoc.data.capability.RootKind.MAGISK,
        )
        val ladder = AutoTdpViewModel.buildUnlockLadder(report, primeFreqLiveWritable = false)!!
        val rootStep = ladder.steps.first { it.kind == UnlockStepKind.ROOT }
        // Root present (Magisk) but privilege is not ROOT (user hasn't opted in).
        assertThat(rootStep.state).isEqualTo(UnlockStepState.AVAILABLE)
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

    // ─── remainingMahFromChargeCounter (FIX 2: real mAh + honest fallback) ─────
    //
    // The battery-target preview used to feed a hardcoded 3000 mAh placeholder into
    // BatteryTarget.capForTarget, so the REQUIRED CAP was wrong on every device. The
    // ViewModel now converts the real charge-counter reading; these tests pin the
    // conversion + the honest-unavailable contract.

    @Test
    fun `remainingMahFromChargeCounter converts uAh to mAh`() {
        // An Odin-class 5000 mAh reading (5_000_000 µAh) → 5000 mAh, NOT the old 3000.
        val mah = AutoTdpViewModel.remainingMahFromChargeCounter(5_000_000L)
        assertThat(mah).isEqualTo(5000)
        assertThat(mah).isNotEqualTo(3000) // the old fabricated constant
    }

    @Test
    fun `remainingMahFromChargeCounter returns null when reading is unavailable`() {
        // null = device exposes no charge counter → caller shows "estimate unavailable"
        // instead of a number backed by a fake constant.
        assertThat(AutoTdpViewModel.remainingMahFromChargeCounter(null)).isNull()
    }

    @Test
    fun `remainingMahFromChargeCounter treats non-positive reading as unavailable`() {
        assertThat(AutoTdpViewModel.remainingMahFromChargeCounter(0L)).isNull()
        assertThat(AutoTdpViewModel.remainingMahFromChargeCounter(-1L)).isNull()
    }

    @Test
    fun `remainingMahFromChargeCounter floors a tiny reading to at least 1 mAh`() {
        // 500 µAh = 0.5 mAh → floored to 1 so the energy math can't go zero/negative.
        assertThat(AutoTdpViewModel.remainingMahFromChargeCounter(500L)).isEqualTo(1)
    }

    @Test
    fun `real-mAh path yields a different required cap than the old 3000 constant`() {
        // End-to-end proof the fix matters: same draw/voltage/target, but the REAL
        // 5000 mAh capacity (from the charge counter) maps to a HIGHER allowable cap
        // than the old hardcoded 3000 mAh would have — i.e. the old constant
        // systematically under-capped on a larger battery.
        val caps = io.github.mayusi.calibratesoc.data.autotdp.TdpCaps(
            primeCoreIndices = listOf(7),
            bigPolicyId = 4,
            bigClusterOppStepsKhz = listOf(
                499_000, 844_000, 1_171_000, 1_536_000,
                1_920_000, 2_323_000, 2_707_000, 2_803_000,
            ),
            gpuMinLevel = 0,
            gpuMaxLevel = 6,
            minOnlineCores = 4,
            totalOnlineCores = 8,
        )
        val realMah = AutoTdpViewModel.remainingMahFromChargeCounter(5_000_000L)!!
        val withReal = io.github.mayusi.calibratesoc.data.autotdp.BatteryTarget.capForTarget(
            targetHours = 4.0, remainingCapacityMah = realMah,
            batteryVoltageMv = 4000, currentDrawMw = 5_000L, caps = caps,
        )
        val withOldConstant = io.github.mayusi.calibratesoc.data.autotdp.BatteryTarget.capForTarget(
            targetHours = 4.0, remainingCapacityMah = 3000,
            batteryVoltageMv = 4000, currentDrawMw = 5_000L, caps = caps,
        )
        // More remaining energy → a higher (or equal) sustainable cap; never lower.
        val realCap = withReal.mappedCapKhz ?: 0
        val oldCap = withOldConstant.mappedCapKhz ?: 0
        assertThat(realCap).isAtLeast(oldCap)
        // And the real budget is strictly larger (5000 mAh vs 3000 mAh at same V/hours).
        assertThat(withReal.budgetW).isGreaterThan(withOldConstant.budgetW)
    }
}
