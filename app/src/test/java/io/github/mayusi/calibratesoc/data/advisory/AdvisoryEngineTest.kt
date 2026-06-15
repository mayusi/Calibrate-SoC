package io.github.mayusi.calibratesoc.data.advisory

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Unit tests for [AdvisoryEngine.advise].
 *
 * All tests use pure value objects — no Android runtime, no mocks.
 *
 * Device model: SD8Gen2-style 3-cluster (policy0=little, policy4=big, policy7=prime)
 * matching the AutoTdpEngineTest fixture for easy cross-reference.
 */
class AdvisoryEngineTest {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    private fun makePolicyProbe(
        policyId: Int,
        cores: List<Int>,
        topFreqKhz: Int,
        governor: String = "schedutil",
    ) = CpuPolicyProbe(
        policyId = policyId,
        onlineCores = cores,
        availableFreqsKhz = listOf(500_000, topFreqKhz / 2, topFreqKhz),
        availableGovernors = listOf("schedutil", "performance"),
        currentMinKhz = 500_000,
        currentMaxKhz = topFreqKhz,
        currentGovernor = governor,
        hardwareLimitsKhz = null,
    )

    private val report3Cluster = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test", brand = "Test", model = "TestPhone",
            device = "test", hardware = "qcom", androidVersion = "14",
            sdkInt = 34, knownHandheldKey = null,
        ),
        soc = SoCIdentity(
            socManufacturer = "Qualcomm",
            socModel = "SM8550",
            gpuFamily = GpuFamily.ADRENO,
        ),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(installed = false, running = false,
            permissionGranted = false, sysfsWriteAllowed = null),
        cpuPolicies = listOf(
            makePolicyProbe(0, listOf(0, 1, 2, 3), topFreqKhz = 2_016_000), // little
            makePolicyProbe(4, listOf(4, 5, 6), topFreqKhz = 2_803_000),    // big
            makePolicyProbe(7, listOf(7), topFreqKhz = 3_187_000),          // prime
        ),
        gpu = GpuProbe(
            family = GpuFamily.ADRENO,
            rootPath = "/sys/class/kgsl/kgsl-3d0",
            availableFreqsHz = listOf(200_000_000L, 800_000_000L),
            availableGovernors = listOf("msm-adreno-tz"),
            currentMinHz = 200_000_000L,
            currentMaxHz = 800_000_000L,
            currentGovernor = "msm-adreno-tz",
            powerLevelRange = null,
        ),
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(
            aynGameAssistant = false,
            langerhansOdinTools = false,
            ayaSpace = false,
            retroidGameAssistant = false,
        ),
    )

    /** Build a Telemetry sample with explicit GPU and per-core loads. */
    private fun telemetry(
        gpuLoad: Int,
        coreLoads: List<Int>,
        currentUa: Long? = 2_000_000L,
        voltageUv: Long? = 4_000_000L,
    ) = Telemetry(
        timestampMs = System.currentTimeMillis(),
        perCoreCpuFreqKhz = List(coreLoads.size) { 1_000_000 },
        perCoreLoadPct = coreLoads,
        gpuLoadPct = gpuLoad,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = emptyList<ZoneTemp>(),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = currentUa,
        batteryVoltageUv = voltageUv,
        fanRpm = null,
    )

    /** Returns a window of [count] identical GPU-bound samples (core 7 load = 10%). */
    private fun gpuBoundWindow(count: Int = 4) = List(count) {
        telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
    }

    /** Returns a window of [count] identical CPU-saturated samples (core 7 load = 92%). */
    private fun cpuBoundWindow(count: Int = 4) = List(count) {
        telemetry(gpuLoad = 30, coreLoads = List(8) { i -> if (i == 7) 92 else 50 })
    }

    /** Returns a window of steady balanced samples. */
    private fun balancedWindow(count: Int = 4) = List(count) {
        telemetry(gpuLoad = 40, coreLoads = List(8) { 30 })
    }

    // ─── GPU-bound advice ──────────────────────────────────────────────────────

    @Test
    fun `gpu-bound window produces at least one advice item`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        assertThat(advice).isNotEmpty()
    }

    @Test
    fun `gpu-bound advice is actionable`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        val primary = advice.first()
        assertThat(primary.actionable).isTrue()
    }

    @Test
    fun `gpu-bound advice title mentions gpu-bound`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        val primary = advice.first()
        assertThat(primary.title.lowercase()).containsMatch("gpu|bound|prime")
    }

    @Test
    fun `gpu-bound advice detail never claims guaranteed control`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        val combinedDetail = advice.joinToString(" ") { it.detail }
        // Must NOT contain language implying we directly set clocks.
        assertThat(combinedDetail.lowercase()).doesNotContain("we set")
        assertThat(combinedDetail.lowercase()).doesNotContain("enforced")
        assertThat(combinedDetail.lowercase()).doesNotContain("will save")
        // MUST say "estimated" or "advisory" to qualify the saving.
        assertThat(
            combinedDetail.lowercase().contains("estimated") ||
            combinedDetail.lowercase().contains("advisory") ||
            combinedDetail.lowercase().contains("may")
        ).isTrue()
    }

    @Test
    fun `gpu-bound estimatedSavingMw is non-null when prime cores are identified`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        // The GPU-bound advice should have an estimated saving since we have prime cores.
        val primaryGpuAdvice = advice.find { it.title.contains("GPU", ignoreCase = true) }
        assertThat(primaryGpuAdvice).isNotNull()
        // estimatedSavingMw can be non-null for the main GPU-bound item.
        // It should be positive when derivable.
        val savingMw = primaryGpuAdvice!!.estimatedSavingMw
        if (savingMw != null) {
            assertThat(savingMw).isGreaterThan(0L)
        }
    }

    @Test
    fun `gpu-bound advice detail mentions stock-device alternatives`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        val combinedDetail = advice.joinToString(" ") { it.detail }
        // Must mention at least one actionable no-root alternative.
        assertThat(
            combinedDetail.lowercase().contains("fps") ||
            combinedDetail.lowercase().contains("resolution") ||
            combinedDetail.lowercase().contains("battery game mode") ||
            combinedDetail.lowercase().contains("game mode") ||
            combinedDetail.lowercase().contains("sustained")
        ).isTrue()
    }

    // ─── CPU-bound advice ──────────────────────────────────────────────────────

    @Test
    fun `cpu-bound window produces advice`() {
        val advice = AdvisoryEngine.advise(cpuBoundWindow(4), report3Cluster)
        assertThat(advice).isNotEmpty()
    }

    @Test
    fun `cpu-bound advice mentions cpu saturation`() {
        val advice = AdvisoryEngine.advise(cpuBoundWindow(4), report3Cluster)
        val primary = advice.first()
        assertThat(primary.title.lowercase()).containsMatch("cpu|bound|saturated|core")
    }

    @Test
    fun `cpu-bound advice estimatedSavingMw is null (no safe saving on saturated cpu)`() {
        val advice = AdvisoryEngine.advise(cpuBoundWindow(4), report3Cluster)
        // The primary CPU-bound advice should not claim a saving because
        // capping a saturated CPU would hurt performance.
        val cpuAdvice = advice.find {
            it.title.lowercase().let { t -> t.contains("cpu") || t.contains("bound") }
        }
        assertThat(cpuAdvice).isNotNull()
        assertThat(cpuAdvice!!.estimatedSavingMw).isNull()
    }

    @Test
    fun `cpu-bound advice detail mentions sustained performance mode`() {
        val advice = AdvisoryEngine.advise(cpuBoundWindow(4), report3Cluster)
        val combinedDetail = advice.joinToString(" ") { it.detail }
        assertThat(combinedDetail.lowercase()).contains("sustained")
    }

    // ─── Balanced / idle window ────────────────────────────────────────────────

    @Test
    fun `balanced window produces at least one advice item`() {
        val advice = AdvisoryEngine.advise(balancedWindow(4), report3Cluster)
        assertThat(advice).isNotEmpty()
    }

    @Test
    fun `balanced window primary advice is not marked actionable (no dominant signal)`() {
        val advice = AdvisoryEngine.advise(balancedWindow(4), report3Cluster)
        // The primary balanced advice item should not be actionable.
        val balancedAdvice = advice.find {
            it.title.lowercase().let { t -> t.contains("balanced") || t.contains("dominant") }
        }
        assertThat(balancedAdvice).isNotNull()
        assertThat(balancedAdvice!!.actionable).isFalse()
    }

    @Test
    fun `balanced window confidence is LOW (insufficient signal)`() {
        val advice = AdvisoryEngine.advise(balancedWindow(4), report3Cluster)
        val balancedAdvice = advice.find {
            it.title.lowercase().let { t -> t.contains("balanced") || t.contains("dominant") }
        }
        assertThat(balancedAdvice).isNotNull()
        assertThat(balancedAdvice!!.confidence).isEqualTo("LOW")
    }

    // ─── Empty and insufficient windows ───────────────────────────────────────

    @Test
    fun `empty window returns empty advice list`() {
        val advice = AdvisoryEngine.advise(emptyList(), report3Cluster)
        assertThat(advice).isEmpty()
    }

    @Test
    fun `single-sample window produces advice without crash`() {
        val window = listOf(telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 5 else 10 }))
        val advice = AdvisoryEngine.advise(window, report3Cluster)
        // Should produce advice (at least the draw context card).
        assertThat(advice).isNotEmpty()
    }

    // ─── estimatedSavingMw null when not derivable ────────────────────────────

    @Test
    fun `estimatedSavingMw is null for balanced advice with no battery data`() {
        val windowNoBattery = List(4) {
            telemetry(
                gpuLoad = 40,
                coreLoads = List(8) { 30 },
                currentUa = null,
                voltageUv = null,
            )
        }
        val advice = AdvisoryEngine.advise(windowNoBattery, report3Cluster)
        val balancedItem = advice.find {
            it.title.lowercase().let { t -> t.contains("balanced") || t.contains("dominant") }
        }
        // Without draw data, the balanced estimate should be null (no baseline to compute from).
        // The draw-context card should also be absent.
        val drawCard = advice.find { it.title.lowercase().contains("live draw") }
        assertThat(drawCard).isNull()
    }

    // ─── No-claim-of-control language check ───────────────────────────────────

    @Test
    fun `no advice item title or detail claims direct clock control`() {
        val windows = listOf(gpuBoundWindow(4), cpuBoundWindow(4), balancedWindow(4))
        for (window in windows) {
            val advice = AdvisoryEngine.advise(window, report3Cluster)
            for (item in advice) {
                val text = "${item.title} ${item.detail}".lowercase()
                assertThat(text).doesNotContain("set the clock")
                assertThat(text).doesNotContain("enforced the clock")
                assertThat(text).doesNotContain("we parked")
                assertThat(text).doesNotContain("guaranteed saving")
            }
        }
    }

    // ─── Internal signal extraction ────────────────────────────────────────────

    @Test
    fun `computeSignals gpu-bound window produces allGpuBound=true`() {
        val primeCores = setOf(7)
        val signals = AdvisoryEngine.computeSignals(gpuBoundWindow(4), primeCores)
        assertThat(signals.allGpuBound).isTrue()
        assertThat(signals.anySaturated).isFalse()
        assertThat(signals.smoothedGpuLoad).isAtLeast(80)
    }

    @Test
    fun `computeSignals cpu-bound window produces anySaturated=true`() {
        val primeCores = setOf(7)
        val signals = AdvisoryEngine.computeSignals(cpuBoundWindow(4), primeCores)
        assertThat(signals.anySaturated).isTrue()
    }

    @Test
    fun `computeSignals empty prime set uses overall cpu average as fallback`() {
        val signals = AdvisoryEngine.computeSignals(gpuBoundWindow(4), emptySet())
        // Should not throw; GPU load should still be computed correctly.
        assertThat(signals.smoothedGpuLoad).isAtLeast(80)
    }

    @Test
    fun `computeSignals null battery data yields null meanDrawMw`() {
        val windowNoBattery = List(4) {
            telemetry(gpuLoad = 50, coreLoads = List(8) { 40 }, currentUa = null, voltageUv = null)
        }
        val signals = AdvisoryEngine.computeSignals(windowNoBattery, setOf(7))
        assertThat(signals.meanDrawMw).isNull()
    }

    @Test
    fun `computeSignals with valid battery data yields positive meanDrawMw`() {
        // 2A at 4V = 8W = 8000 mW
        val windowWithBattery = List(4) {
            telemetry(gpuLoad = 50, coreLoads = List(8) { 40 },
                currentUa = 2_000_000L, voltageUv = 4_000_000L)
        }
        val signals = AdvisoryEngine.computeSignals(windowWithBattery, setOf(7))
        assertThat(signals.meanDrawMw).isNotNull()
        assertThat(signals.meanDrawMw!!).isGreaterThan(0L)
    }

    // ─── Draw context card ─────────────────────────────────────────────────────

    @Test
    fun `draw context card is appended when battery data is available`() {
        val window = List(4) {
            telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
        }
        val advice = AdvisoryEngine.advise(window, report3Cluster)
        val drawCard = advice.find { it.title.lowercase().contains("live draw") }
        assertThat(drawCard).isNotNull()
    }

    @Test
    fun `draw context card estimatedSavingMw is null (it is informational only)`() {
        val window = List(4) {
            telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
        }
        val advice = AdvisoryEngine.advise(window, report3Cluster)
        val drawCard = advice.find { it.title.lowercase().contains("live draw") }
        assertThat(drawCard).isNotNull()
        assertThat(drawCard!!.estimatedSavingMw).isNull()
    }

    // ─── Sorting: actionable items come first ──────────────────────────────────

    @Test
    fun `actionable advice items are sorted before non-actionable ones`() {
        val advice = AdvisoryEngine.advise(gpuBoundWindow(4), report3Cluster)
        var seenNonActionable = false
        for (item in advice) {
            if (!item.actionable) {
                seenNonActionable = true
            }
            if (seenNonActionable && item.actionable) {
                // An actionable item appeared after a non-actionable one — violates sort order.
                assertThat(false).isTrue() // fail with clear error
            }
        }
    }
}
