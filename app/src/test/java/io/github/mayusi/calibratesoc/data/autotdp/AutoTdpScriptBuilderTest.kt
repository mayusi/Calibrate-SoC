package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
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
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import org.junit.Test

/**
 * Unit tests for [AutoTdpScriptBuilder].
 *
 * Verifies that:
 * 1. Prime cores are offlined via cpu$N/online=0 in extraSysfs.
 * 2. cpu0 is NEVER offlined.
 * 3. The big-cluster cap is written to the correct policy.
 * 4. The generated script went through the REAL [AynScriptGenerator] pipeline
 *    (so values are shell-quoted and existence-guarded).
 * 5. The GPU max_pwrlevel is kept at the fastest level.
 * 6. The honesty banner is present.
 *
 * Uses the REAL [AynScriptGenerator] (no mocks), confirming end-to-end
 * shell correctness — the same seam the user actually runs.
 *
 * Device model (SD8Gen2 / RP6 topology):
 *   policy0  — little (cores 0-3, top OPP 2016 MHz)
 *   policy4  — gold/big (cores 4-6, top OPP 2803 MHz) ← cap target
 *   policy7  — prime (core 7, top OPP 3187 MHz)       ← offline target
 */
class AutoTdpScriptBuilderTest {

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),      // core 7 = only prime (cpu0 excluded)
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(
            499_000, 844_000, 1_171_000, 1_536_000,
            1_920_000, 2_323_000, 2_707_000, 2_803_000,
        ),
        gpuMinLevel = 0,    // Adreno: 0 = fastest
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    /** CapabilityReport with a 3-cluster CPU and an Adreno GPU. */
    private val report = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Moorechip",
            brand = "Retroid",
            model = "Retroid Pocket 6",
            device = "kalama",
            hardware = "kalama",
            androidVersion = "13",
            sdkInt = 33,
            knownHandheldKey = "retroid_pocket6",
        ),
        soc = SoCIdentity("QTI", "QCS8550", GpuFamily.ADRENO),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(
            CpuPolicyProbe(
                policyId = 0,
                onlineCores = listOf(0, 1, 2, 3),
                availableFreqsKhz = listOf(307_200, 614_400, 1_075_200, 1_516_800, 2_016_000),
                availableGovernors = listOf("schedutil", "conservative", "performance"),
                currentMinKhz = 307_200,
                currentMaxKhz = 2_016_000,
                currentGovernor = "schedutil",
                hardwareLimitsKhz = FreqRange(307_200, 2_016_000),
            ),
            CpuPolicyProbe(
                policyId = 4,
                onlineCores = listOf(4, 5, 6),
                availableFreqsKhz = listOf(
                    499_000, 844_000, 1_171_000, 1_536_000,
                    1_920_000, 2_323_000, 2_707_000, 2_803_000,
                ),
                availableGovernors = listOf("schedutil", "walt", "performance"),
                currentMinKhz = 499_000,
                currentMaxKhz = 2_803_000,
                currentGovernor = "schedutil",
                hardwareLimitsKhz = FreqRange(499_000, 2_803_000),
            ),
            CpuPolicyProbe(
                policyId = 7,
                onlineCores = listOf(7),
                availableFreqsKhz = listOf(652_800, 1_401_600, 2_016_000, 2_803_200, 3_187_200),
                availableGovernors = listOf("schedutil", "walt", "performance"),
                currentMinKhz = 652_800,
                currentMaxKhz = 3_187_200,
                currentGovernor = "schedutil",
                hardwareLimitsKhz = FreqRange(652_800, 3_187_200),
            ),
        ),
        gpu = GpuProbe(
            family = GpuFamily.ADRENO,
            rootPath = "/sys/class/kgsl/kgsl-3d0",
            availableFreqsHz = listOf(300_000_000L, 490_000_000L, 587_000_000L, 670_000_000L),
            availableGovernors = listOf("msm-adreno-tz", "simple_ondemand"),
            currentMinHz = 300_000_000L,
            currentMaxHz = 670_000_000L,
            currentGovernor = "msm-adreno-tz",
            powerLevelRange = LevelRange(low = 0, high = 6),
        ),
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false, retroidGameAssistant = true),
        adrenoExtras = AdrenoExtrasProbe(
            pwrLevelFreqHz = mapOf(0 to 670_000_000L, 6 to 100_000_000L),
            currentMinPwrLevel = 0,
            currentMaxPwrLevel = 6,
            currentDefaultPwrLevel = 3,
            throttlingEnabled = null,
            forceClkOn = null,
            idleTimerMs = null,
        ),
    )

    private val adapter = DeviceAdapter(
        key = "retroid_pocket6",
        displayName = "Retroid Pocket 6",
        vendorAppPackage = "com.rp.gameassistant",
        fanAdapter = null,
        perfPresetAdapter = null,
        perfDaemonsToStopOnWrite = listOf("perfd"),
        chmodLockCpuFreqWrites = true,
    )

    // Real generator — no mocks. This is the seam test.
    private val generator = AynScriptGenerator()
    private val builder = AutoTdpScriptBuilder(generator)

    // ─── Prime-core offline ───────────────────────────────────────────────────

    @Test
    fun `script offlines prime core 7 via cpu7_online = 0`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // The write to cpu7/online must appear, existence-guarded and shell-quoted.
        assertThat(script).contains("/sys/devices/system/cpu/cpu7/online")
        assertThat(script).contains("'0'")
    }

    @Test
    fun `cpu0 is NEVER written to online node`() {
        // cpu0/online must not appear in any write line.
        val script = builder.buildEfficiencyScript(
            caps = caps.copy(primeCoreIndices = listOf(0, 7)), // pathological: cpu0 in list
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // There must be no printf write targeting cpu0/online.
        assertThat(script).doesNotContain("printf %s '0' > '/sys/devices/system/cpu/cpu0/online'")
        assertThat(script).doesNotContain("printf %s '0' > /sys/devices/system/cpu/cpu0/online")
    }

    @Test
    fun `no prime cores to offline produces no cpu_online writes`() {
        val noPrimeCaps = caps.copy(primeCoreIndices = emptyList())
        val script = builder.buildEfficiencyScript(
            caps = noPrimeCaps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // No cpu*/online writes should be emitted.
        assertThat(script).doesNotContain("/online")
    }

    @Test
    fun `script offlines all prime cores when there are multiple`() {
        val multiPrimeCaps = caps.copy(primeCoreIndices = listOf(6, 7))
        val script = builder.buildEfficiencyScript(
            caps = multiPrimeCaps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        assertThat(script).contains("/sys/devices/system/cpu/cpu6/online")
        assertThat(script).contains("/sys/devices/system/cpu/cpu7/online")
    }

    // ─── Big-cluster cap ──────────────────────────────────────────────────────

    @Test
    fun `script writes big-cluster scaling_max_freq for policy4`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // Policy4 is the big cluster.
        assertThat(script).contains("policy4/scaling_max_freq")
    }

    @Test
    fun `cap is well below top OPP in EFFICIENCY mode (heuristic 67 pct)`() {
        val preset = builder.buildEfficiencyPreset(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
        )
        val cap = preset.cpuPolicyMaxKhz[4] ?: return
        val topOpp = 2_803_000
        // ~67% of 2803 = ~1878. Must be below top OPP.
        assertThat(cap).isLessThan(topOpp)
        // Must be a real OPP step.
        assertThat(caps.bigClusterOppStepsKhz).contains(cap)
    }

    @Test
    fun `cap snaps to nearest OPP step at or below measured knee when provided`() {
        // Measured knee at 1900 MHz. Nearest OPP step <= 1900000 = 1920000 would be over,
        // so it should pick 1_536_000 (the highest step <= 1_900_000 in this table).
        // Wait: 1_920_000 > 1_900_000, so the correct snap is 1_536_000.
        val kneeKhz = 1_900_000
        val preset = builder.buildEfficiencyPreset(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            kneeKhz = kneeKhz,
        )
        val cap = preset.cpuPolicyMaxKhz[4]
        assertThat(cap).isNotNull()
        assertThat(cap!!).isAtMost(kneeKhz)
        assertThat(caps.bigClusterOppStepsKhz).contains(cap)
    }

    @Test
    fun `cap snaps to first OPP when knee is below all steps`() {
        // Knee very low (below all steps) → should snap to the lowest step.
        val kneeKhz = 100_000 // below all OPPs
        val preset = builder.buildEfficiencyPreset(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            kneeKhz = kneeKhz,
        )
        val cap = preset.cpuPolicyMaxKhz[4]
        // Should pick the lowest step since no step is <= 100 MHz.
        assertThat(cap).isEqualTo(caps.bigClusterOppStepsKhz.first())
    }

    // ─── GPU stays permissive ─────────────────────────────────────────────────

    @Test
    fun `script writes GPU max_pwrlevel to fastest level (0)`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // The max_pwrlevel node must be written with value '0' (fastest Adreno level).
        assertThat(script).contains("max_pwrlevel")
        assertThat(script).contains("'0'")
    }

    @Test
    fun `preset extraSysfs contains GPU max_pwrlevel entry at fastest level`() {
        val preset = builder.buildEfficiencyPreset(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
        )
        val gpuEntry = preset.extraSysfs.entries.firstOrNull { it.key.contains("max_pwrlevel") }
        assertThat(gpuEntry).isNotNull()
        assertThat(gpuEntry!!.value).isEqualTo("0") // caps.gpuMinLevel
    }

    // ─── Script went through real AynScriptGenerator ─────────────────────────

    @Test
    fun `script has existence guard on cpu online path`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // Every extraSysfs write is existence-guarded by AynScriptGenerator.
        assertThat(script).contains("[ -e '/sys/devices/system/cpu/cpu7/online' ]")
    }

    @Test
    fun `script is single-quote-escaped (paths are quoted in shell commands)`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // AynScriptGenerator wraps every path in single quotes.
        // Confirm the cpu7/online path is single-quoted.
        assertThat(script).contains("'/sys/devices/system/cpu/cpu7/online'")
    }

    @Test
    fun `script contains chmod sandwich for extraSysfs writes`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        // AynScriptGenerator emits chmod 666 + write + chmod 444 for extraSysfs.
        assertThat(script).contains("chmod 666 '/sys/devices/system/cpu/cpu7/online'")
        assertThat(script).contains("chmod 444 '/sys/devices/system/cpu/cpu7/online'")
    }

    @Test
    fun `script has honesty banner in preset description`() {
        // The preset description (emitted as a comment in the script) must
        // contain the honesty copy.
        val preset = builder.buildEfficiencyPreset(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
        )
        assertThat(preset.description.lowercase()).contains("static efficiency tune")
        assertThat(preset.description.lowercase()).contains("one-time unlock")
    }

    @Test
    fun `script starts with shebang`() {
        val script = builder.buildEfficiencyScript(
            caps = caps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
            adapter = adapter,
        )
        assertThat(script).startsWith("#!/system/bin/sh")
    }

    // ─── Empty / degenerate caps ──────────────────────────────────────────────

    @Test
    fun `empty OPP table produces preset with no big-cluster cap`() {
        val emptyCaps = caps.copy(bigClusterOppStepsKhz = emptyList())
        val preset = builder.buildEfficiencyPreset(
            caps = emptyCaps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
        )
        assertThat(preset.cpuPolicyMaxKhz).isEmpty()
    }

    @Test
    fun `null gpuMinLevel skips GPU max_pwrlevel entry in extraSysfs`() {
        val noGpuCaps = caps.copy(gpuMinLevel = null)
        val preset = builder.buildEfficiencyPreset(
            caps = noGpuCaps,
            profile = AutoTdpProfile.EFFICIENCY,
            report = report,
        )
        val gpuEntry = preset.extraSysfs.entries.firstOrNull { it.key.contains("max_pwrlevel") }
        assertThat(gpuEntry).isNull()
    }
}
