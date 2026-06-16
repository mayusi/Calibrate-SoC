package io.github.mayusi.calibratesoc.data.efficiency

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.CurvePoint
import io.github.mayusi.calibratesoc.data.autotdp.CurveResult
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.AdrenoExtrasProbe
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
 * Unit tests for [EfficiencyAdvisor].
 *
 * Pure JVM — no Android, no coroutines, no mocks. All inputs are constructed
 * inline so every decision path is covered without a device or emulator.
 *
 * Key tested scenarios:
 *  1. No sweep data → ESTIMATED plan, requiresSweep=true, no knee caps.
 *  2. Sweep with valid knee → MEASURED plan, knee cap set, draw reduction computed.
 *  3. Sweep present but knee is null (all drawMw=0) → ESTIMATED plan fallback.
 *  4. GPU floor: minLevel < maxLevel → floor = minLevel+1.
 *  5. GPU floor: minLevel == maxLevel → no floor (null).
 *  6. Draw reduction computed correctly from top vs knee point.
 */
class EfficiencyAdvisorTest {

    private val advisor = EfficiencyAdvisor()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun fakeReport(
        policies: List<CpuPolicyProbe> = listOf(fakePolicyLittle(), fakePolicyBig()),
        gpu: GpuProbe? = fakeAdrenoGpu(),
        adrenoExtras: AdrenoExtrasProbe? = fakeAdrenoExtras(),
    ) = CapabilityReport(
        device = DeviceIdentity("AYN", "AYN", "Odin3", "odin3",
            "qcom", "14", 34, "ayn"),
        soc = SoCIdentity("Qualcomm", "SM8450", GpuFamily.ADRENO),
        privilege = PrivilegeTier.VENDOR_SETTINGS,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(installed = false, running = false,
            permissionGranted = false, sysfsWriteAllowed = null),
        cpuPolicies = policies,
        gpu = gpu,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(aynGameAssistant = true, langerhansOdinTools = false,
            ayaSpace = false),
        adrenoExtras = adrenoExtras,
    )

    private fun fakePolicyLittle() = CpuPolicyProbe(
        policyId = 0,
        onlineCores = listOf(0, 1, 2, 3),
        availableFreqsKhz = listOf(300_000, 614_400, 1_017_600, 1_804_800),
        availableGovernors = listOf("schedutil"),
        currentMinKhz = 300_000,
        currentMaxKhz = 1_804_800,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(300_000, 1_804_800),
    )

    private fun fakePolicyBig() = CpuPolicyProbe(
        policyId = 4,
        onlineCores = listOf(4, 5, 6),
        availableFreqsKhz = listOf(710_400, 1_324_800, 2_073_600, 3_187_200),
        availableGovernors = listOf("schedutil"),
        currentMinKhz = 710_400,
        currentMaxKhz = 3_187_200,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(710_400, 3_187_200),
    )

    private fun fakeAdrenoGpu() = GpuProbe(
        family = GpuFamily.ADRENO,
        rootPath = "/sys/class/kgsl/kgsl-3d0",
        availableFreqsHz = listOf(257_000_000L, 390_000_000L, 490_000_000L, 598_000_000L),
        availableGovernors = listOf("msm-adreno-tz"),
        currentMinHz = 257_000_000L,
        currentMaxHz = 598_000_000L,
        currentGovernor = "msm-adreno-tz",
        powerLevelRange = LevelRange(0, 3),
    )

    private fun fakeAdrenoExtras(minLevel: Int = 0, maxLevel: Int = 3) = AdrenoExtrasProbe(
        pwrLevelFreqHz = mapOf(0 to 598_000_000L, 1 to 490_000_000L,
            2 to 390_000_000L, 3 to 257_000_000L),
        currentMinPwrLevel = minLevel,
        currentMaxPwrLevel = maxLevel,
        currentDefaultPwrLevel = 1,
        throttlingEnabled = false,
        forceClkOn = false,
        idleTimerMs = 80,
    )

    private fun fakeTdpCaps(
        bigOppSteps: List<Int> = listOf(710_400, 1_324_800, 2_073_600, 3_187_200),
        gpuMinLevel: Int? = 0,
        gpuMaxLevel: Int? = 3,
    ) = TdpCaps(
        primeCoreIndices = listOf(4, 5, 6),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = bigOppSteps,
        gpuMinLevel = gpuMinLevel,
        gpuMaxLevel = gpuMaxLevel,
        minOnlineCores = 2,
        totalOnlineCores = 7,
    )

    private fun fakeCurveResult(knee: CurvePoint?, points: List<CurvePoint>): CurveResult =
        CurveResult(points = points, knee = knee, summary = "fake")

    // ─── No sweep data ────────────────────────────────────────────────────────

    @Test
    fun `buildPlan with no sweep returns ESTIMATED plan with requiresSweep true`() {
        val report = fakeReport()
        val caps   = fakeTdpCaps()

        val plan = advisor.buildPlan(report, caps, curveResult = null)

        assertThat(plan.drawEstimateSource).isEqualTo(EstimateSource.ESTIMATED)
        assertThat(plan.requiresSweep).isTrue()
        assertThat(plan.bigClusterKneeCap).isNull()
        assertThat(plan.primeClusterKneeCap).isNull()
        assertThat(plan.estimatedDrawReductionPct)
            .isEqualTo(EfficiencyAdvisor.STATIC_DRAW_REDUCTION_ESTIMATE_PCT)
    }

    @Test
    fun `buildPlan no sweep - summaryText mentions running the sweep`() {
        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = null)
        assertThat(plan.summaryText.lowercase()).contains("sweep")
    }

    // ─── Measured plan from curve ─────────────────────────────────────────────

    @Test
    fun `buildPlan with valid knee returns MEASURED plan`() {
        val knee = CurvePoint.of(capKhz = 2_073_600, perfScore = 9000.0, drawMw = 3_500L)
        val points = listOf(
            CurvePoint.of(capKhz = 1_324_800, perfScore = 6_000.0, drawMw = 2_200L),
            knee,
            CurvePoint.of(capKhz = 3_187_200, perfScore = 12_000.0, drawMw = 6_500L),
        )
        val curve = fakeCurveResult(knee = knee, points = points)

        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = curve)

        assertThat(plan.drawEstimateSource).isEqualTo(EstimateSource.MEASURED)
        assertThat(plan.requiresSweep).isFalse()
        assertThat(plan.bigClusterKneeCap).isEqualTo(2_073_600)
    }

    @Test
    fun `buildPlan measured - draw reduction computed from knee vs top-OPP point`() {
        // top point: 3_187_200 kHz, draw 6_500 mW
        // knee point: 2_073_600 kHz, draw 3_500 mW
        // reduction = (6500 - 3500) / 6500 * 100 = 46.1% → 46
        val knee = CurvePoint.of(capKhz = 2_073_600, perfScore = 9_000.0, drawMw = 3_500L)
        val topPoint = CurvePoint.of(capKhz = 3_187_200, perfScore = 12_000.0, drawMw = 6_500L)
        val curve = fakeCurveResult(knee = knee, points = listOf(
            CurvePoint.of(capKhz = 1_324_800, perfScore = 6_000.0, drawMw = 2_200L),
            knee,
            topPoint,
        ))

        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = curve)

        // (6500 - 3500) / 6500 * 100 = 46.15... → truncated to int = 46
        assertThat(plan.estimatedDrawReductionPct).isEqualTo(46)
    }

    @Test
    fun `buildPlan measured - no draw reduction when knee IS the top point`() {
        // Edge case: knee and top point are the same OPP — no reduction to compute.
        val knee = CurvePoint.of(capKhz = 3_187_200, perfScore = 12_000.0, drawMw = 5_000L)
        val curve = fakeCurveResult(knee = knee, points = listOf(
            CurvePoint.of(capKhz = 2_073_600, perfScore = 9_000.0, drawMw = 4_000L),
            knee,
        ))

        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = curve)

        // No higher-cap point exists → draw reduction null (we can't compare).
        assertThat(plan.estimatedDrawReductionPct).isNull()
    }

    // ─── Knee null / zero drawMw → fallback to ESTIMATED ────────────────────

    @Test
    fun `buildPlan with null knee falls back to ESTIMATED`() {
        // Curve was run but all points have drawMw=0 (device was charging).
        val points = listOf(
            CurvePoint.of(capKhz = 1_804_800, perfScore = 8_000.0, drawMw = 0L),
            CurvePoint.of(capKhz = 3_187_200, perfScore = 12_000.0, drawMw = 0L),
        )
        val curve = fakeCurveResult(knee = null, points = points)

        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = curve)

        assertThat(plan.drawEstimateSource).isEqualTo(EstimateSource.ESTIMATED)
        assertThat(plan.requiresSweep).isTrue()
        assertThat(plan.bigClusterKneeCap).isNull()
    }

    @Test
    fun `buildPlan with knee drawMw zero falls back to ESTIMATED`() {
        // findKnee should never produce a drawMw=0 knee, but be defensive.
        val badKnee = CurvePoint(capKhz = 2_073_600, perfScore = 9_000.0,
            drawMw = 0L, perfPerWatt = 0.0)
        val curve = fakeCurveResult(knee = badKnee, points = listOf(badKnee))

        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = curve)

        assertThat(plan.drawEstimateSource).isEqualTo(EstimateSource.ESTIMATED)
        assertThat(plan.bigClusterKneeCap).isNull()
    }

    // ─── GPU floor ────────────────────────────────────────────────────────────

    @Test
    fun `buildPlan GPU floor is minLevel + 1 when range allows it`() {
        // gpuMinLevel=0, gpuMaxLevel=3 → floor = 1
        val caps = fakeTdpCaps(gpuMinLevel = 0, gpuMaxLevel = 3)
        val plan = advisor.buildPlan(fakeReport(), caps, curveResult = null)
        assertThat(plan.gpuPowerLevelFloor).isEqualTo(1)
    }

    @Test
    fun `buildPlan GPU floor is null when minLevel equals maxLevel`() {
        // Single power level — no cap to apply.
        val extras = fakeAdrenoExtras(minLevel = 2, maxLevel = 2)
        val caps = fakeTdpCaps(gpuMinLevel = 2, gpuMaxLevel = 2)
        val plan = advisor.buildPlan(fakeReport(adrenoExtras = extras), caps, curveResult = null)
        assertThat(plan.gpuPowerLevelFloor).isNull()
    }

    @Test
    fun `buildPlan GPU floor is null when no Adreno GPU`() {
        val caps = fakeTdpCaps(gpuMinLevel = null, gpuMaxLevel = null)
        val plan = advisor.buildPlan(fakeReport(adrenoExtras = null), caps, curveResult = null)
        assertThat(plan.gpuPowerLevelFloor).isNull()
    }

    @Test
    fun `buildPlan GPU floor does not exceed maxLevel`() {
        // gpuMinLevel=2, gpuMaxLevel=3 → floor = min(3, 3) = 3
        val caps = fakeTdpCaps(gpuMinLevel = 2, gpuMaxLevel = 3)
        val plan = advisor.buildPlan(fakeReport(), caps, curveResult = null)
        assertThat(plan.gpuPowerLevelFloor).isEqualTo(3)
    }

    // ─── Summary text ─────────────────────────────────────────────────────────

    @Test
    fun `buildPlan measured - summary mentions knee MHz`() {
        val knee = CurvePoint.of(capKhz = 2_073_600, perfScore = 9_000.0, drawMw = 4_000L)
        val curve = fakeCurveResult(knee = knee, points = listOf(
            knee,
            CurvePoint.of(capKhz = 3_187_200, perfScore = 12_000.0, drawMw = 7_000L),
        ))
        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = curve)

        // knee at 2_073_600 kHz = 2073 MHz
        assertThat(plan.summaryText).contains("2073")
    }

    @Test
    fun `buildPlan estimated - summary does NOT claim a specific MHz as measured`() {
        val plan = advisor.buildPlan(fakeReport(), fakeTdpCaps(), curveResult = null)
        // Must say it's not measured
        assertThat(plan.summaryText.lowercase()).doesNotContain("measured at")
    }

    // ─── EstimateSource semantics ─────────────────────────────────────────────

    @Test
    fun `EstimateSource MEASURED is not ESTIMATED`() {
        assertThat(EstimateSource.MEASURED).isNotEqualTo(EstimateSource.ESTIMATED)
    }
}
