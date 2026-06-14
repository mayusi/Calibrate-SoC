package io.github.mayusi.calibratesoc.data.baseline

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FanProbe
import io.github.mayusi.calibratesoc.data.capability.FanSource
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.SchedBoostInterface
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableSnapshot
import org.junit.Test

/**
 * Unit tests for [BaselineDegradation.analyze].
 *
 * We only use pure data constructors — no Android framework, no Hilt.
 */
class BaselineDegradationTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun cpuMaxSnapAt(policyId: Int, khz: Long) = TunableSnapshot(
        id = TunableId(
            kind = TunableKind.SYSFS,
            target = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq",
        ),
        previousValue = khz.toString(),
        writtenAtMs = 0L,
        reason = "factory baseline",
    )

    private fun cpuMinSnapAt(policyId: Int, khz: Long) = TunableSnapshot(
        id = TunableId(
            kind = TunableKind.SYSFS,
            target = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_min_freq",
        ),
        previousValue = khz.toString(),
        writtenAtMs = 0L,
        reason = "factory baseline",
    )

    private fun gpuMaxSnapAt(hz: Long) = TunableSnapshot(
        id = TunableId(
            kind = TunableKind.SYSFS,
            target = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
        ),
        previousValue = hz.toString(),
        writtenAtMs = 0L,
        reason = "factory baseline",
    )

    private fun settingsSnap(key: String, value: String) = TunableSnapshot(
        id = TunableId(kind = TunableKind.SETTINGS_SYSTEM, target = key),
        previousValue = value,
        writtenAtMs = 0L,
        reason = "factory baseline",
    )

    private fun baseline(vararg snaps: TunableSnapshot) = FactoryBaseline(
        capturedAtMs = 1_000_000L,
        appVersionAtCapture = "0.1.0",
        deviceModel = "Test Device",
        socModel = "Test SoC",
        tunables = snaps.toList(),
    )

    private fun cpuPolicy(
        policyId: Int,
        maxKhz: Int,
        minKhz: Int = 300_000,
    ) = CpuPolicyProbe(
        policyId = policyId,
        onlineCores = listOf(policyId),
        availableFreqsKhz = listOf(minKhz, maxKhz),
        availableGovernors = listOf("schedutil"),
        currentMinKhz = minKhz,
        currentMaxKhz = maxKhz,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = null,
    )

    private fun minReport(
        cpuPolicies: List<CpuPolicyProbe> = emptyList(),
        gpu: GpuProbe? = null,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test",
            brand = "Test",
            model = "TestPhone",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = null,
        ),
        soc = SoCIdentity(
            socManufacturer = "Qualcomm",
            socModel = "SM8550",
            gpuFamily = GpuFamily.ADRENO,
        ),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(
            installed = false,
            running = false,
            permissionGranted = false,
            sysfsWriteAllowed = null,
        ),
        cpuPolicies = cpuPolicies,
        gpu = gpu,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(
            aynGameAssistant = false,
            langerhansOdinTools = false,
            ayaSpace = false,
        ),
    )

    private fun gpuProbe(maxHz: Long, minHz: Long = 100_000_000L) = GpuProbe(
        family = GpuFamily.ADRENO,
        rootPath = "/sys/class/kgsl/kgsl-3d0",
        availableFreqsHz = listOf(minHz, maxHz),
        availableGovernors = listOf("msm-adreno-tz"),
        currentMinHz = minHz,
        currentMaxHz = maxHz,
        currentGovernor = "msm-adreno-tz",
        powerLevelRange = null,
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `OK when current cpu max equals baseline`() {
        val bl = baseline(cpuMaxSnapAt(policyId = 0, khz = 3_187_200L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_187_200)))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.OK)
        assertThat(result.findings).hasSize(1)
        assertThat(result.findings[0].changePct).isWithin(0.001).of(0.0)
        assertThat(result.insufficientDataReason).isNull()
    }

    @Test
    fun `DEGRADED when cpu max dropped more than 10 percent`() {
        // Baseline 3187 MHz, now 2500 MHz → drop ≈ 21.5 %
        val bl = baseline(cpuMaxSnapAt(policyId = 0, khz = 3_187_200L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 2_500_000)))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.DEGRADED)
        val finding = result.findings[0]
        assertThat(finding.isDrop).isTrue()
        assertThat(finding.changePct).isGreaterThan(BaselineDegradation.DEGRADED_THRESHOLD_PCT)
    }

    @Test
    fun `MINOR when cpu max dropped between 1 and 10 percent`() {
        // Baseline 3187200 kHz, now 3000000 kHz → drop ≈ 5.9 %
        val bl = baseline(cpuMaxSnapAt(policyId = 0, khz = 3_187_200L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_000_000)))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.MINOR)
    }

    @Test
    fun `INSUFFICIENT_DATA when no comparable signal exists`() {
        // Baseline only has vendor Settings keys — no CPU/GPU freq tunables.
        val bl = baseline(settingsSnap("fan_mode", "0"), settingsSnap("performance_mode", "2"))
        val report = minReport(cpuPolicies = emptyList(), gpu = null)
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.INSUFFICIENT_DATA)
        assertThat(result.findings).isEmpty()
        assertThat(result.insufficientDataReason).isNotNull()
        assertThat(result.insufficientDataReason).isNotEmpty()
    }

    @Test
    fun `INSUFFICIENT_DATA when baseline has cpu freq tunable but policy no longer exists in report`() {
        // Policy 4 was in baseline but device now only exposes policy 0.
        val bl = baseline(cpuMaxSnapAt(policyId = 4, khz = 2_841_600L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_187_200)))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.INSUFFICIENT_DATA)
    }

    @Test
    fun `OK when GPU max freq matches baseline`() {
        val hz = 700_000_000L  // 700 MHz
        val bl = baseline(gpuMaxSnapAt(hz))
        val report = minReport(gpu = gpuProbe(maxHz = hz))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.OK)
        assertThat(result.findings[0].changePct).isWithin(0.001).of(0.0)
    }

    @Test
    fun `DEGRADED when GPU max freq dropped more than 10 percent`() {
        // 700 MHz baseline → 580 MHz live (≈17% drop)
        val bl = baseline(gpuMaxSnapAt(700_000_000L))
        val report = minReport(gpu = gpuProbe(maxHz = 580_000_000L))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.DEGRADED)
    }

    @Test
    fun `worst finding drives overall status`() {
        // policy0 max dropped 2% (MINOR level) and policy4 max dropped 15% (DEGRADED level).
        val bl = baseline(
            cpuMaxSnapAt(policyId = 0, khz = 1_800_000L),
            cpuMaxSnapAt(policyId = 4, khz = 3_187_200L),
        )
        val report = minReport(
            cpuPolicies = listOf(
                cpuPolicy(policyId = 0, maxKhz = 1_764_000),  // ~2% drop
                cpuPolicy(policyId = 4, maxKhz = 2_700_000),  // ~15% drop
            ),
        )
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.DEGRADED)
        assertThat(result.findings).hasSize(2)
    }

    @Test
    fun `OK status has limitation note not insufficient data reason`() {
        val bl = baseline(cpuMaxSnapAt(policyId = 0, khz = 3_187_200L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_187_200)))
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.OK)
        assertThat(result.insufficientDataReason).isNull()
        assertThat(result.limitationNote).isNotNull()
    }

    @Test
    fun `finding formattedPct shows drop as negative sign and rise as positive sign`() {
        // A finding where changePct is positive (clock dropped)
        val drop = DegradationFinding("cpu max", "3000 MHz", "2700 MHz", changePct = 10.0)
        assertThat(drop.formattedPct).startsWith("-")
        assertThat(drop.isDrop).isTrue()

        // A finding where changePct is negative (clock rose — floor bump)
        val rise = DegradationFinding("cpu min", "300 MHz", "600 MHz", changePct = -100.0)
        assertThat(rise.formattedPct).startsWith("+")
        assertThat(rise.isDrop).isFalse()
    }

    @Test
    fun `cpu min freq change is included when above minor threshold`() {
        // Baseline min 300 MHz, now 800 MHz → floor rose >1%.  Should appear.
        val bl = baseline(cpuMinSnapAt(policyId = 0, khz = 300_000L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_187_200, minKhz = 800_000)))
        val result = BaselineDegradation.analyze(bl, report)
        // Should find the min-freq change even though max-freq wasn't in baseline
        val minFinding = result.findings.firstOrNull { it.signal.contains("min clock floor") }
        assertThat(minFinding).isNotNull()
    }

    @Test
    fun `cpu min freq change below minor threshold is suppressed`() {
        // Baseline min 300000, now 300100 → <1% change. Should NOT appear.
        val bl = baseline(cpuMinSnapAt(policyId = 0, khz = 300_000L))
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_187_200, minKhz = 300_100)))
        val result = BaselineDegradation.analyze(bl, report)
        val minFinding = result.findings.firstOrNull { it.signal.contains("min clock floor") }
        assertThat(minFinding).isNull()
    }

    @Test
    fun `governor and settings tunables do not produce findings`() {
        val bl = baseline(
            settingsSnap("fan_mode", "1"),
            TunableSnapshot(
                id = TunableId(TunableKind.SYSFS, "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"),
                previousValue = "schedutil",
                writtenAtMs = 0L,
                reason = "factory baseline",
            ),
        )
        val report = minReport(cpuPolicies = listOf(cpuPolicy(policyId = 0, maxKhz = 3_187_200)))
        // Neither a Settings key nor a governor produces a numeric finding.
        val result = BaselineDegradation.analyze(bl, report)
        assertThat(result.status).isEqualTo(DegradationStatus.INSUFFICIENT_DATA)
    }
}
