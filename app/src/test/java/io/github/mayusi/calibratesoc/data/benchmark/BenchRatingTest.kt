package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FanProbe
import io.github.mayusi.calibratesoc.data.capability.FanSource
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import org.junit.Test

class BenchRatingTest {

    // ─── SoC class mapping ────────────────────────────────────────────

    @Test
    fun `8 Gen 2 maps to FLAGSHIP`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.FLAGSHIP)
    }

    @Test
    fun `8 Gen 3 maps to FLAGSHIP`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 3")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.FLAGSHIP)
    }

    @Test
    fun `8 Elite maps to FLAGSHIP`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Elite")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.FLAGSHIP)
    }

    @Test
    fun `Dimensity 9300 maps to FLAGSHIP`() {
        val report = reportWithSoc("MediaTek", "Dimensity 9300")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.FLAGSHIP)
    }

    @Test
    fun `G3x Gen 2 maps to UPPER_MID`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon G3x Gen 2")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.UPPER_MID)
    }

    @Test
    fun `778G maps to UPPER_MID`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 778G")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.UPPER_MID)
    }

    @Test
    fun `Helio G99 maps to MID`() {
        val report = reportWithSoc("MediaTek", "Helio G99")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.MID)
    }

    @Test
    fun `garbage SoC string maps to UNKNOWN`() {
        val report = reportWithSoc("", "")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.UNKNOWN)
    }

    // ─── RAW codenames (what real devices actually report) — these only
    //     classify correctly when benchClass resolves the friendly name
    //     via SocFriendlyNames first. Regression guard. ───────────────

    @Test
    fun `RP6 and Thor raw codename QCS8550 maps to FLAGSHIP`() {
        // ro.soc.model on RP6/Thor is the raw "QCS8550", NOT "8 Gen 2".
        val report = reportWithSoc("QTI", "QCS8550")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.FLAGSHIP)
    }

    @Test
    fun `Odin 3 raw codename CQ8725S maps to FLAGSHIP`() {
        // ro.soc.model on Odin 3 is "CQ8725S" (Snapdragon 8 Elite).
        val report = reportWithSoc("ayn", "CQ8725S")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.FLAGSHIP)
    }

    @Test
    fun `SG8275 raw codename maps to UPPER_MID`() {
        // Snapdragon G3x Gen 2 ships as SG8275.
        val report = reportWithSoc("QTI", "SG8275")
        assertThat(BenchRating.benchClass(report)).isEqualTo(BenchRating.BenchClass.UPPER_MID)
    }

    // ─── rating() word at cpuCeilingPct thresholds ───────────────────

    @Test
    fun `cpuCeilingPct 1_0 yields Full power`() {
        val (run, report) = runWithCeilingPct(1.0)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Full power")
        assertThat(rating.color).isEqualTo(BenchRating.RatingColor.TERTIARY)
    }

    @Test
    fun `cpuCeilingPct 0_97 yields Full power`() {
        val (run, report) = runWithCeilingPct(0.97)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Full power")
    }

    @Test
    fun `cpuCeilingPct 0_96 yields Strong`() {
        val (run, report) = runWithCeilingPct(0.96)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Strong")
        assertThat(rating.color).isEqualTo(BenchRating.RatingColor.PRIMARY)
    }

    @Test
    fun `cpuCeilingPct 0_80 yields Strong`() {
        val (run, report) = runWithCeilingPct(0.80)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Strong")
    }

    @Test
    fun `cpuCeilingPct 0_79 yields Tuned down`() {
        val (run, report) = runWithCeilingPct(0.79)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Tuned down")
        assertThat(rating.color).isEqualTo(BenchRating.RatingColor.SECONDARY)
    }

    @Test
    fun `cpuCeilingPct 0_55 yields Tuned down`() {
        val (run, report) = runWithCeilingPct(0.55)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Tuned down")
    }

    @Test
    fun `cpuCeilingPct 0_54 yields Heavily underclocked`() {
        val (run, report) = runWithCeilingPct(0.54)
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Heavily underclocked")
        assertThat(rating.color).isEqualTo(BenchRating.RatingColor.OUTLINE)
    }

    @Test
    fun `cpuCeilingPct null yields Completed with no word`() {
        // Build a run with no matching policy ceilings
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        val run = completedRun(
            snapshotPolicies = listOf(SystemSnapshot.PolicySnapshot(0, 800_000, 3_187_200, "schedutil")),
            reportPolicies = emptyList(), // no ceiling match
        )
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isEqualTo("Completed")
        assertThat(rating.cpuCeilingPct).isNull()
    }

    // ─── aborted run ─────────────────────────────────────────────────

    @Test
    fun `aborted run yields null word and non-null abortReason`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        val run = BenchRun(
            id = 1L,
            name = "test",
            flavor = BenchFlavor.FULL,
            startedAtMs = 0L,
            durationMs = 10_000L,
            snapshot = SystemSnapshot(
                capturedAtMs = 0L,
                deviceModel = "Test",
                socModel = "Snapdragon 8 Gen 2",
                androidVersion = "14",
                privilegeTier = "NONE",
                cpuPolicies = emptyList(),
                gpuMinHz = null,
                gpuMaxHz = null,
                gpuGovernor = null,
                appVersion = "0.1.5",
            ),
            kernels = KernelScores(cpuIntegerSingle = 1000L),
            throttleSamples = emptyList(),
            outcome = BenchOutcome.ABORTED_TEMP,
        )
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isNull()
        assertThat(rating.abortReason).isNotNull()
        assertThat(rating.abortReason).contains("thermal")
    }

    // ─── B2 honest rename: ABORTED_BATTERY_TEMP ──────────────────────

    @Test
    fun `ABORTED_BATTERY_TEMP abortReason mentions battery hot not low battery`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        val run = BenchRun(
            id = 2L,
            name = "test",
            flavor = BenchFlavor.FULL,
            startedAtMs = 0L,
            durationMs = 10_000L,
            snapshot = SystemSnapshot(
                capturedAtMs = 0L,
                deviceModel = "Test",
                socModel = "Snapdragon 8 Gen 2",
                androidVersion = "14",
                privilegeTier = "NONE",
                cpuPolicies = emptyList(),
                gpuMinHz = null,
                gpuMaxHz = null,
                gpuGovernor = null,
                appVersion = "0.1.9",
            ),
            kernels = KernelScores(cpuIntegerSingle = 1000L),
            throttleSamples = emptyList(),
            outcome = BenchOutcome.ABORTED_BATTERY_TEMP,
        )
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isNull()
        assertThat(rating.abortReason).isNotNull()
        // Must say "hot" — not the old misleading "low battery".
        assertThat(rating.abortReason).ignoringCase().contains("hot")
        assertThat(rating.abortReason).ignoringCase().doesNotContain("low battery")
    }

    @Test
    fun `legacy ABORTED_BATTERY also shows hot not low battery`() {
        // Rows stored before the rename must not display "low battery" either.
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        val run = BenchRun(
            id = 3L,
            name = "test",
            flavor = BenchFlavor.FULL,
            startedAtMs = 0L,
            durationMs = 10_000L,
            snapshot = SystemSnapshot(
                capturedAtMs = 0L,
                deviceModel = "Test",
                socModel = "Snapdragon 8 Gen 2",
                androidVersion = "14",
                privilegeTier = "NONE",
                cpuPolicies = emptyList(),
                gpuMinHz = null,
                gpuMaxHz = null,
                gpuGovernor = null,
                appVersion = "0.1.8",
            ),
            kernels = KernelScores(cpuIntegerSingle = 1000L),
            throttleSamples = emptyList(),
            outcome = BenchOutcome.ABORTED_BATTERY,
        )
        val rating = BenchRating.rate(run, report)
        assertThat(rating.abortReason).isNotNull()
        assertThat(rating.abortReason).ignoringCase().doesNotContain("low battery")
    }

    // ─── B3: ABORTED_BATTERY_LOW (charge level, not temperature) ─────

    @Test
    fun `ABORTED_BATTERY_LOW label mentions low and under 15 percent`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        val run = BenchRun(
            id = 4L,
            name = "test",
            flavor = BenchFlavor.FULL,
            startedAtMs = 0L,
            durationMs = 10_000L,
            snapshot = SystemSnapshot(
                capturedAtMs = 0L,
                deviceModel = "Test",
                socModel = "Snapdragon 8 Gen 2",
                androidVersion = "14",
                privilegeTier = "NONE",
                cpuPolicies = emptyList(),
                gpuMinHz = null,
                gpuMaxHz = null,
                gpuGovernor = null,
                appVersion = "0.1.14",
            ),
            kernels = KernelScores(cpuIntegerSingle = 1000L),
            throttleSamples = emptyList(),
            outcome = BenchOutcome.ABORTED_BATTERY_LOW,
        )
        val rating = BenchRating.rate(run, report)
        assertThat(rating.word).isNull()
        assertThat(rating.abortReason).isNotNull()
        // Must be distinct from the "too hot" thermal label.
        assertThat(rating.abortReason).ignoringCase().doesNotContain("hot")
        assertThat(rating.abortReason).ignoringCase().contains("low")
        assertThat(rating.abortReason).contains("15%")
    }

    @Test
    fun `ABORTED_BATTERY_LOW and ABORTED_BATTERY_TEMP have distinct labels`() {
        val report = reportWithSoc("Qualcomm", "Snapdragon 8 Gen 2")
        fun runWith(outcome: BenchOutcome) = BenchRun(
            id = 5L, name = "test", flavor = BenchFlavor.FULL,
            startedAtMs = 0L, durationMs = 10_000L,
            snapshot = SystemSnapshot(
                capturedAtMs = 0L, deviceModel = "Test",
                socModel = "Snapdragon 8 Gen 2", androidVersion = "14",
                privilegeTier = "NONE", cpuPolicies = emptyList(),
                gpuMinHz = null, gpuMaxHz = null, gpuGovernor = null,
                appVersion = "0.1.14",
            ),
            kernels = KernelScores(), throttleSamples = emptyList(),
            outcome = outcome,
        )
        val lowLabel = BenchRating.rate(runWith(BenchOutcome.ABORTED_BATTERY_LOW), report).abortReason
        val hotLabel = BenchRating.rate(runWith(BenchOutcome.ABORTED_BATTERY_TEMP), report).abortReason
        assertThat(lowLabel).isNotEqualTo(hotLabel)
        assertThat(lowLabel).isNotNull()
        assertThat(hotLabel).isNotNull()
    }

    // ─── Helper builders ──────────────────────────────────────────────

    private fun reportWithSoc(
        manufacturer: String,
        model: String,
        policies: List<CpuPolicyProbe> = emptyList(),
    ) = CapabilityReport(
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
        soc = SoCIdentity(
            socManufacturer = manufacturer,
            socModel = model,
            gpuFamily = GpuFamily.ADRENO,
        ),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
    )

    /**
     * Build a (BenchRun, CapabilityReport) pair where cpuCeilingPct
     * equals [targetPct]. Uses a single policy where:
     *   - hardware ceiling = 3_187_200 kHz
     *   - snapshot maxKhz = ceiling * targetPct
     */
    private fun runWithCeilingPct(targetPct: Double): Pair<BenchRun, CapabilityReport> {
        val ceilingKhz = 3_187_200
        val snapshotMaxKhz = (ceilingKhz * targetPct).toInt()

        val report = reportWithSoc(
            manufacturer = "Qualcomm",
            model = "Snapdragon 8 Gen 2",
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0, 1, 2, 3),
                    availableFreqsKhz = listOf(1_000_000, 2_000_000, ceilingKhz),
                    availableGovernors = listOf("schedutil"),
                    currentMinKhz = 300_000,
                    currentMaxKhz = snapshotMaxKhz,
                    currentGovernor = "schedutil",
                    hardwareLimitsKhz = FreqRange(300_000, ceilingKhz),
                ),
            ),
        )

        val run = completedRun(
            snapshotPolicies = listOf(
                SystemSnapshot.PolicySnapshot(0, 300_000, snapshotMaxKhz, "schedutil"),
            ),
            reportPolicies = report.cpuPolicies,
        )
        return run to report
    }

    private fun completedRun(
        snapshotPolicies: List<SystemSnapshot.PolicySnapshot>,
        reportPolicies: List<CpuPolicyProbe>,
    ): BenchRun = BenchRun(
        id = 1L,
        name = "test",
        flavor = BenchFlavor.STANDARD,
        startedAtMs = 0L,
        durationMs = 120_000L,
        snapshot = SystemSnapshot(
            capturedAtMs = 0L,
            deviceModel = "Test",
            socModel = "Snapdragon 8 Gen 2",
            androidVersion = "14",
            privilegeTier = "NONE",
            cpuPolicies = snapshotPolicies,
            gpuMinHz = null,
            gpuMaxHz = null,
            gpuGovernor = null,
            appVersion = "0.1.5",
        ),
        kernels = KernelScores(cpuIntegerSingle = 50_000L),
        throttleSamples = emptyList(),
        outcome = BenchOutcome.COMPLETED,
    )
}
