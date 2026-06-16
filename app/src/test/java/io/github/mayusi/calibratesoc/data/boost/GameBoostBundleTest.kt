package io.github.mayusi.calibratesoc.data.boost

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.AdrenoExtrasProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.DevfreqDeviceProbe
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.LevelRange
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import org.junit.Test

/**
 * Unit tests for [GameBoostBundle] — the pure brute-max write-bundle composer.
 *
 * Verifies the bundle pins the RIGHT nodes to the RIGHT (ceiling) values, keeps
 * cpu0 online (never offlines), never disables GPU thermal throttling, never uses
 * force_clk_on, and honestly skips nodes that aren't present on the firmware.
 *
 * Device model (Odin 3 / SD8Gen3-style, GPU devfreq 160..1100 MHz writable):
 *   policy0 — little (cores 0-3, top 2016 MHz)
 *   policy4 — gold/big (cores 4-6, top 2803 MHz)
 *   policy7 — prime (core 7, top 3187 MHz) ← also pinned to its own ceiling
 */
class GameBoostBundleTest {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    private val gpuDevfreqHz = listOf(
        160_000_000L, 305_000_000L, 451_000_000L, 547_000_000L,
        650_000_000L, 800_000_000L, 950_000_000L, 1_100_000_000L,
    )

    private fun policy(
        id: Int,
        cores: List<Int>,
        opps: List<Int>,
        governors: List<String> = listOf("schedutil", "performance"),
    ) = CpuPolicyProbe(
        policyId = id,
        onlineCores = cores,
        availableFreqsKhz = opps,
        availableGovernors = governors,
        currentMinKhz = opps.first(),
        currentMaxKhz = opps.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(opps.first(), opps.last()),
    )

    private val report = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYN", brand = "AYN", model = "Odin 3", device = "pineapple",
            hardware = "pineapple", androidVersion = "14", sdkInt = 34,
            knownHandheldKey = "ayn_odin3",
        ),
        soc = SoCIdentity("QTI", "SM8650", GpuFamily.ADRENO),
        privilege = PrivilegeTier.ROOT,
        rootKind = RootKind.MAGISK,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(
            policy(0, listOf(0, 1, 2, 3), listOf(307_200, 1_075_200, 1_516_800, 2_016_000)),
            policy(4, listOf(4, 5, 6), listOf(499_000, 1_171_000, 1_920_000, 2_803_000)),
            policy(7, listOf(7), listOf(652_800, 2_016_000, 2_803_200, 3_187_200)),
        ),
        gpu = GpuProbe(
            family = GpuFamily.ADRENO,
            rootPath = "/sys/class/kgsl/kgsl-3d0",
            availableFreqsHz = gpuDevfreqHz,
            availableGovernors = listOf("msm-adreno-tz", "performance", "simple_ondemand"),
            currentMinHz = 160_000_000L,
            currentMaxHz = 1_100_000_000L,
            currentGovernor = "msm-adreno-tz",
            powerLevelRange = LevelRange(low = 0, high = 7),
        ),
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(aynGameAssistant = true, false, false),
        adrenoExtras = AdrenoExtrasProbe(
            pwrLevelFreqHz = mapOf(0 to 1_100_000_000L, 7 to 160_000_000L),
            currentMinPwrLevel = 0,
            currentMaxPwrLevel = 7,
            currentDefaultPwrLevel = 4,
            throttlingEnabled = true,
            forceClkOn = false,
            idleTimerMs = 80,
        ),
        devfreqDevices = listOf(
            DevfreqDeviceProbe(
                deviceName = "qcom,cpubw",
                curFreqHz = 547_000_000L,
                minFreqHz = 200_000_000L,
                maxFreqHz = 2_092_000_000L,
                currentGovernor = "bw_hwmon",
                availableGovernors = listOf("bw_hwmon", "performance"),
            ),
        ),
    )

    private val FAN_KEY = "fan_mode"

    // ─── CPU pins ──────────────────────────────────────────────────────────────

    @Test
    fun `every CPU policy max is pinned to its ceiling OPP`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        fun maxOpVal(policyId: Int) = ops.firstOrNull {
            it.id.target == "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq"
        }?.value
        assertThat(maxOpVal(0)).isEqualTo("2016000")
        assertThat(maxOpVal(4)).isEqualTo("2803000")
        assertThat(maxOpVal(7)).isEqualTo("3187200")
    }

    @Test
    fun `every CPU policy min floor is a real OPP step strictly below the ceiling`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        for (policy in report.cpuPolicies) {
            val minVal = ops.firstOrNull {
                it.id.target == "/sys/devices/system/cpu/cpufreq/policy${policy.policyId}/scaling_min_freq"
            }?.value?.toInt()
            assertThat(minVal).isNotNull()
            // Must be a real OPP step and strictly below the ceiling (no min == max).
            assertThat(policy.availableFreqsKhz).contains(minVal)
            assertThat(minVal!!).isLessThan(policy.availableFreqsKhz.max())
        }
    }

    @Test
    fun `CPU governors set to performance where available`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        val govOps = ops.filter { it.id.target.endsWith("/scaling_governor") }
        assertThat(govOps).isNotEmpty()
        assertThat(govOps.all { it.value == "performance" }).isTrue()
    }

    @Test
    fun `cpu0 is never offlined by the boost bundle`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        // Boost NEVER writes any cpu online node — it keeps every core online.
        assertThat(ops.none { it.id.target.endsWith("/online") }).isTrue()
        assertThat(ops.none { it.id.target.contains("/cpu0/online") }).isTrue()
    }

    // ─── GPU pins ──────────────────────────────────────────────────────────────

    @Test
    fun `GPU max and min pwrlevel both pinned to fastest level 0`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        val maxLvl = ops.firstOrNull { it.id.target == "/sys/class/kgsl/kgsl-3d0/max_pwrlevel" }
        val minLvl = ops.firstOrNull { it.id.target == "/sys/class/kgsl/kgsl-3d0/min_pwrlevel" }
        assertThat(maxLvl?.value).isEqualTo("0")
        assertThat(minLvl?.value).isEqualTo("0")
    }

    @Test
    fun `GPU devfreq min and max both pinned to the ceiling`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        val devMax = ops.firstOrNull { it.id.target == "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq" }
        val devMin = ops.firstOrNull { it.id.target == "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq" }
        assertThat(devMax?.value).isEqualTo("1100000000")
        assertThat(devMin?.value).isEqualTo("1100000000")
    }

    @Test
    fun `GPU idle_timer is set large and force_clk_on is NEVER written`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        val idle = ops.firstOrNull { it.id.target.endsWith("/idle_timer") }
        assertThat(idle?.value?.toInt()).isAtLeast(5_000)
        // force_clk_on cooks the device — must never appear.
        assertThat(ops.none { it.id.target.endsWith("/force_clk_on") }).isTrue()
    }

    @Test
    fun `GPU thermal throttling is NEVER disabled (safety, not faked)`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        // Boost trades heat+battery for FPS, never safety: the throttling gate stays armed.
        assertThat(ops.none { it.id.target.endsWith("/throttling") }).isTrue()
    }

    // ─── Fan ───────────────────────────────────────────────────────────────────

    @Test
    fun `fan_mode set to Sport when a fan key is supplied and enabled`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY, setFanSport = true)
        val fanOp = ops.firstOrNull { it.id.kind == TunableKind.SETTINGS_SYSTEM && it.id.target == FAN_KEY }
        assertThat(fanOp?.value).isEqualTo(GameBoostBundle.FAN_MODE_SPORT.toString())
    }

    @Test
    fun `no fan op when fan key is null (honest skip)`() {
        val ops = GameBoostBundle.build(report, fanModeKey = null)
        assertThat(ops.none { it.id.kind == TunableKind.SETTINGS_SYSTEM }).isTrue()
    }

    @Test
    fun `no fan op when setFanSport is false`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY, setFanSport = false)
        assertThat(ops.none { it.id.kind == TunableKind.SETTINGS_SYSTEM }).isTrue()
    }

    // ─── Bus / DDR devfreq ───────────────────────────────────────────────────────

    @Test
    fun `bus devfreq min and max both pinned to ceiling`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        val busMax = ops.firstOrNull { it.id.target == "/sys/class/devfreq/qcom,cpubw/max_freq" }
        val busMin = ops.firstOrNull { it.id.target == "/sys/class/devfreq/qcom,cpubw/min_freq" }
        assertThat(busMax?.value).isEqualTo("2092000000")
        assertThat(busMin?.value).isEqualTo("2092000000")
    }

    // ─── Honest skips on absent hardware ─────────────────────────────────────────

    @Test
    fun `no GPU present produces no GPU ops`() {
        val noGpu = report.copy(gpu = null, adrenoExtras = null)
        val ops = GameBoostBundle.build(noGpu, fanModeKey = FAN_KEY)
        assertThat(ops.none { it.id.target.contains("kgsl") }).isTrue()
    }

    @Test
    fun `Mali GPU (no pwrlevels) skips pwrlevel and idle_timer ops`() {
        val mali = report.copy(
            gpu = report.gpu!!.copy(family = GpuFamily.MALI, powerLevelRange = null, rootPath = "/sys/class/devfreq/gpu.mali"),
            adrenoExtras = null,
        )
        val ops = GameBoostBundle.build(mali, fanModeKey = FAN_KEY)
        assertThat(ops.none { it.id.target.endsWith("/max_pwrlevel") }).isTrue()
        assertThat(ops.none { it.id.target.endsWith("/min_pwrlevel") }).isTrue()
        assertThat(ops.none { it.id.target.endsWith("/idle_timer") }).isTrue()
        // But Mali devfreq min/max (frame consistency) still pin.
        assertThat(ops.any { it.id.target == "/sys/class/devfreq/gpu.mali/devfreq/max_freq" }).isTrue()
    }

    @Test
    fun `policy with empty OPP table is skipped honestly`() {
        val degenerate = report.copy(
            cpuPolicies = report.cpuPolicies.map {
                if (it.policyId == 0) it.copy(availableFreqsKhz = emptyList()) else it
            },
        )
        val ops = GameBoostBundle.build(degenerate, fanModeKey = FAN_KEY)
        assertThat(ops.none { it.id.target.contains("/policy0/") }).isTrue()
        // Other policies still pinned.
        assertThat(ops.any { it.id.target.contains("/policy4/scaling_max_freq") }).isTrue()
    }

    @Test
    fun `no devfreq devices produces no bus ops`() {
        val noBus = report.copy(devfreqDevices = emptyList())
        val ops = GameBoostBundle.build(noBus, fanModeKey = FAN_KEY)
        assertThat(ops.none { it.id.target == "/sys/class/devfreq/qcom,cpubw/max_freq" }).isTrue()
    }

    // ─── All ops route through TunableWriter (SYSFS / SETTINGS_SYSTEM kinds) ──────

    @Test
    fun `all ops are SYSFS or SETTINGS_SYSTEM kinds (route through TunableWriter)`() {
        val ops = GameBoostBundle.build(report, fanModeKey = FAN_KEY)
        assertThat(ops).isNotEmpty()
        assertThat(
            ops.all { it.id.kind == TunableKind.SYSFS || it.id.kind == TunableKind.SETTINGS_SYSTEM }
        ).isTrue()
    }
}
