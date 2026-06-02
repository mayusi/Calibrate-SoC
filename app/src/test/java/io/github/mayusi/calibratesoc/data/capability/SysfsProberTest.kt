package io.github.mayusi.calibratesoc.data.capability

import com.google.common.truth.Truth.assertThat
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Before
import org.junit.Test

class SysfsProberTest {

    private lateinit var fs: FakeFileSystem
    private lateinit var prober: SysfsProber

    @Before
    fun setUp() {
        fs = FakeFileSystem()
        prober = SysfsProber(fs)
    }

    // --- CPU policies ---------------------------------------------------

    @Test
    fun `probeCpuPolicies returns empty when no cpufreq tree`() {
        val policies = prober.probeCpuPolicies()
        assertThat(policies).isEmpty()
    }

    @Test
    fun `probeCpuPolicies parses a single GKI-style policy`() {
        val root = "/sys/devices/system/cpu/cpufreq/policy0".toPath()
        write(root / "related_cpus", "0 1 2 3")
        write(root / "scaling_available_frequencies", "300000 614400 1017600 1804800")
        write(root / "scaling_available_governors", "schedutil performance powersave")
        write(root / "scaling_min_freq", "300000")
        write(root / "scaling_max_freq", "1804800")
        write(root / "scaling_governor", "schedutil")
        write(root / "cpuinfo_min_freq", "300000")
        write(root / "cpuinfo_max_freq", "1804800")

        val policies = prober.probeCpuPolicies()

        assertThat(policies).hasSize(1)
        val p = policies.first()
        assertThat(p.policyId).isEqualTo(0)
        assertThat(p.onlineCores).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(p.availableFreqsKhz).containsExactly(300_000, 614_400, 1_017_600, 1_804_800).inOrder()
        assertThat(p.availableGovernors).containsExactly("schedutil", "performance", "powersave")
        assertThat(p.currentMinKhz).isEqualTo(300_000)
        assertThat(p.currentMaxKhz).isEqualTo(1_804_800)
        assertThat(p.currentGovernor).isEqualTo("schedutil")
        assertThat(p.hardwareLimitsKhz).isEqualTo(FreqRange(300_000, 1_804_800))
    }

    @Test
    fun `probeCpuPolicies sorts multiple policies by id`() {
        // Snapdragon 8 Gen 2 topology: policy0 (LITTLE), policy4 (BIG), policy7 (PRIME).
        listOf(0, 4, 7).forEach { id ->
            val dir = "/sys/devices/system/cpu/cpufreq/policy$id".toPath()
            write(dir / "scaling_available_frequencies", "1000000 2000000")
            write(dir / "scaling_min_freq", "1000000")
            write(dir / "scaling_max_freq", "2000000")
            write(dir / "scaling_governor", "schedutil")
        }

        val ids = prober.probeCpuPolicies().map { it.policyId }
        assertThat(ids).containsExactly(0, 4, 7).inOrder()
    }

    @Test
    fun `probeCpuPolicies falls back to legacy per-cpu layout`() {
        // No /cpufreq/policy* tree, but per-cpu /cpufreq dirs exist.
        listOf(0, 1).forEach { cpu ->
            val dir = "/sys/devices/system/cpu/cpu$cpu/cpufreq".toPath()
            write(dir / "related_cpus", "0 1")
            write(dir / "scaling_available_frequencies", "500000 1500000")
            write(dir / "scaling_min_freq", "500000")
            write(dir / "scaling_max_freq", "1500000")
            write(dir / "scaling_governor", "ondemand")
        }

        val policies = prober.probeCpuPolicies()

        assertThat(policies).hasSize(1)
        assertThat(policies.first().onlineCores).containsExactly(0, 1).inOrder()
        assertThat(policies.first().currentGovernor).isEqualTo("ondemand")
    }

    // --- GPU ------------------------------------------------------------

    @Test
    fun `probeGpu returns null on UNKNOWN family without paths`() {
        assertThat(prober.probeGpu(GpuFamily.UNKNOWN)).isNull()
    }

    @Test
    fun `probeGpu finds Adreno paths`() {
        val root = "/sys/class/kgsl/kgsl-3d0".toPath()
        write(root / "gpu_available_frequencies", "330000000 596000000 950000000")
        write(root / "num_pwrlevels", "3")
        val devfreq = root / "devfreq"
        write(devfreq / "available_governors", "msm-adreno-tz performance")
        write(devfreq / "governor", "msm-adreno-tz")
        write(devfreq / "min_freq", "330000000")
        write(devfreq / "max_freq", "950000000")

        val gpu = prober.probeGpu(GpuFamily.ADRENO)

        assertThat(gpu).isNotNull()
        assertThat(gpu!!.family).isEqualTo(GpuFamily.ADRENO)
        assertThat(gpu.availableFreqsHz).containsExactly(330_000_000L, 596_000_000L, 950_000_000L).inOrder()
        assertThat(gpu.currentGovernor).isEqualTo("msm-adreno-tz")
        assertThat(gpu.powerLevelRange).isEqualTo(LevelRange(0, 2))
    }

    // --- Thermal --------------------------------------------------------

    @Test
    fun `probeThermalZones classifies common types`() {
        write("/sys/class/thermal/thermal_zone0/type".toPath(), "cpu-0-0-usr")
        write("/sys/class/thermal/thermal_zone0/temp".toPath(), "42000")
        write("/sys/class/thermal/thermal_zone1/type".toPath(), "gpu-usr")
        write("/sys/class/thermal/thermal_zone1/temp".toPath(), "55000")
        write("/sys/class/thermal/thermal_zone2/type".toPath(), "battery")
        write("/sys/class/thermal/thermal_zone2/temp".toPath(), "30000")
        write("/sys/class/thermal/thermal_zone3/type".toPath(), "skin-therm")
        write("/sys/class/thermal/thermal_zone3/temp".toPath(), "35000")

        val zones = prober.probeThermalZones()

        assertThat(zones).hasSize(4)
        assertThat(zones.map { it.role }).containsExactly(
            ThermalRole.CPU, ThermalRole.GPU, ThermalRole.BATTERY, ThermalRole.SKIN,
        ).inOrder()
        assertThat(zones[0].currentTempMilliC).isEqualTo(42_000)
    }

    @Test
    fun `probeThermalZones is empty when path missing`() {
        assertThat(prober.probeThermalZones()).isEmpty()
    }

    // --- Fan -----------------------------------------------------------

    @Test
    fun `probeGenericPwmFan returns null without hwmon`() {
        assertThat(prober.probeGenericPwmFan()).isNull()
    }

    @Test
    fun `probeGenericPwmFan finds first hwmon node with pwm1`() {
        write("/sys/class/hwmon/hwmon0/pwm1".toPath(), "128")
        write("/sys/class/hwmon/hwmon0/fan1_input".toPath(), "2400")

        val fan = prober.probeGenericPwmFan()

        assertThat(fan).isNotNull()
        assertThat(fan!!.source).isEqualTo(FanSource.HWMON_PWM)
        assertThat(fan.controlPath).endsWith("hwmon0/pwm1")
        assertThat(fan.currentRpm).isEqualTo(2400)
        assertThat(fan.supportsCurve).isTrue()
    }

    // --- Helpers --------------------------------------------------------

    private fun write(path: okio.Path, contents: String) {
        path.parent?.let { fs.createDirectories(it) }
        fs.write(path) { writeUtf8(contents) }
    }
}
