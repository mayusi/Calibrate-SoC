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

    // --- CPU governor tunables ------------------------------------------

    @Test
    fun `probeCpuGovernorTunables discovers schedutil params`() {
        val policyDir = "/sys/devices/system/cpu/cpufreq/policy0".toPath()
        write(policyDir / "scaling_governor", "schedutil")
        write(policyDir / "scaling_available_frequencies", "300000 1804800")
        write(policyDir / "scaling_min_freq", "300000")
        write(policyDir / "scaling_max_freq", "1804800")
        // Governor sub-dir
        val govDir = policyDir / "schedutil"
        write(govDir / "rate_limit_us", "500")
        write(govDir / "hispeed_load", "90")
        write(govDir / "hispeed_freq", "1804800")

        val policies = prober.probeCpuPolicies()
        val tunables = prober.probeCpuGovernorTunables(policies)

        assertThat(tunables).hasSize(1)
        val pt = tunables.first()
        assertThat(pt.governor).isEqualTo("schedutil")
        assertThat(pt.tunables).containsKey("rate_limit_us")
        assertThat(pt.tunables["rate_limit_us"]).isEqualTo("500")
        assertThat(pt.tunables["hispeed_load"]).isEqualTo("90")
    }

    @Test
    fun `probeCpuGovernorTunables returns empty when governor dir absent`() {
        val policyDir = "/sys/devices/system/cpu/cpufreq/policy0".toPath()
        write(policyDir / "scaling_governor", "performance")
        write(policyDir / "scaling_available_frequencies", "300000 1804800")
        write(policyDir / "scaling_min_freq", "300000")
        write(policyDir / "scaling_max_freq", "1804800")
        // No governor sub-dir created.

        val policies = prober.probeCpuPolicies()
        val tunables = prober.probeCpuGovernorTunables(policies)

        assertThat(tunables).isEmpty()
    }

    // --- CPU time-in-state ----------------------------------------------

    @Test
    fun `probeCpuTimeInState parses freq-jiffies pairs`() {
        val policyDir = "/sys/devices/system/cpu/cpufreq/policy0".toPath()
        write(policyDir / "scaling_available_frequencies", "300000 1804800")
        write(policyDir / "scaling_min_freq", "300000")
        write(policyDir / "scaling_max_freq", "1804800")
        write(policyDir / "scaling_governor", "schedutil")
        val statsDir = policyDir / "stats"
        write(statsDir / "time_in_state", "300000 12345\n1804800 6789\n")

        val policies = prober.probeCpuPolicies()
        val tis = prober.probeCpuTimeInState(policies)

        assertThat(tis).hasSize(1)
        val probe = tis.first()
        assertThat(probe.policyId).isEqualTo(0)
        assertThat(probe.entries).hasSize(2)
        assertThat(probe.entries[0].freqKhz).isEqualTo(300_000)
        assertThat(probe.entries[0].jiffies).isEqualTo(12_345L)
        assertThat(probe.entries[1].freqKhz).isEqualTo(1_804_800)
    }

    // --- Thermal extras (mode + trip points) ---------------------------

    @Test
    fun `probeThermalExtras reads mode and trip points`() {
        val zoneDir = "/sys/class/thermal/thermal_zone0".toPath()
        write(zoneDir / "type", "cpu-0-0-usr")
        write(zoneDir / "temp", "42000")
        write(zoneDir / "mode", "enabled")
        write(zoneDir / "trip_point_0_temp", "75000")
        write(zoneDir / "trip_point_0_type", "passive")
        write(zoneDir / "trip_point_1_temp", "95000")
        write(zoneDir / "trip_point_1_type", "critical")

        val zones = prober.probeThermalZones()
        val extras = prober.probeThermalExtras(zones)

        assertThat(extras).hasSize(1)
        val e = extras.first()
        assertThat(e.mode).isEqualTo("enabled")
        assertThat(e.tripPoints).hasSize(2)
        assertThat(e.tripPoints[0].index).isEqualTo(0)
        assertThat(e.tripPoints[0].tempMilliC).isEqualTo(75_000)
        assertThat(e.tripPoints[0].type).isEqualTo("passive")
        assertThat(e.tripPoints[1].tempMilliC).isEqualTo(95_000)
    }

    @Test
    fun `probeThermalExtras handles missing mode file gracefully`() {
        val zoneDir = "/sys/class/thermal/thermal_zone0".toPath()
        write(zoneDir / "type", "gpu-usr")
        write(zoneDir / "temp", "55000")
        // No mode file — simulates a zone that doesn't support mode switching.

        val zones = prober.probeThermalZones()
        val extras = prober.probeThermalExtras(zones)

        assertThat(extras).hasSize(1)
        assertThat(extras.first().mode).isNull()
        assertThat(extras.first().tripPoints).isEmpty()
    }

    // --- Cooling devices ------------------------------------------------

    @Test
    fun `probeCoolingDevices enumerates cooling device entries`() {
        write("/sys/class/thermal/cooling_device0/type".toPath(), "thermal-cpufreq-0")
        write("/sys/class/thermal/cooling_device0/max_state".toPath(), "16")
        write("/sys/class/thermal/cooling_device0/cur_state".toPath(), "0")
        write("/sys/class/thermal/cooling_device1/type".toPath(), "gpu-cdev")
        write("/sys/class/thermal/cooling_device1/max_state".toPath(), "8")
        write("/sys/class/thermal/cooling_device1/cur_state".toPath(), "2")

        val devices = prober.probeCoolingDevices()

        assertThat(devices).hasSize(2)
        assertThat(devices[0].id).isEqualTo(0)
        assertThat(devices[0].type).isEqualTo("thermal-cpufreq-0")
        assertThat(devices[0].maxState).isEqualTo(16)
        assertThat(devices[0].currentState).isEqualTo(0)
        assertThat(devices[1].id).isEqualTo(1)
        assertThat(devices[1].currentState).isEqualTo(2)
    }

    @Test
    fun `probeCoolingDevices returns empty when no cooling devices`() {
        assertThat(prober.probeCoolingDevices()).isEmpty()
    }

    // --- devfreq devices ------------------------------------------------

    @Test
    fun `probeDevfreqDevices enumerates bus devfreq entries`() {
        val cpubwDir = "/sys/class/devfreq/qcom,cpubw".toPath()
        write(cpubwDir / "cur_freq", "7980000")
        write(cpubwDir / "min_freq", "762000")
        write(cpubwDir / "max_freq", "7980000")
        write(cpubwDir / "governor", "bw_hwmon")
        write(cpubwDir / "available_governors", "bw_hwmon simple_ondemand")

        val devices = prober.probeDevfreqDevices()

        assertThat(devices).hasSize(1)
        val d = devices.first()
        assertThat(d.deviceName).isEqualTo("qcom,cpubw")
        assertThat(d.curFreqHz).isEqualTo(7_980_000L)
        assertThat(d.currentGovernor).isEqualTo("bw_hwmon")
        assertThat(d.availableGovernors).containsExactly("bw_hwmon", "simple_ondemand")
    }

    @Test
    fun `probeDevfreqDevices excludes GPU kgsl entry`() {
        // kgsl-3d0 is GPU devfreq — should be excluded to avoid double-counting.
        write("/sys/class/devfreq/kgsl-3d0/cur_freq".toPath(), "596000000")
        write("/sys/class/devfreq/kgsl-3d0/min_freq".toPath(), "330000000")
        write("/sys/class/devfreq/kgsl-3d0/max_freq".toPath(), "950000000")
        write("/sys/class/devfreq/kgsl-3d0/governor".toPath(), "msm-adreno-tz")
        write("/sys/class/devfreq/kgsl-3d0/available_governors".toPath(), "msm-adreno-tz performance")

        val devices = prober.probeDevfreqDevices()

        assertThat(devices).isEmpty()
    }

    // --- Block devices --------------------------------------------------

    @Test
    fun `probeBlockDevices reads scheduler and queue parameters`() {
        val queueDir = "/sys/block/sda/queue".toPath()
        write(queueDir / "scheduler", "none [mq-deadline] kyber")
        write(queueDir / "read_ahead_kb", "128")
        write(queueDir / "nr_requests", "64")

        val devices = prober.probeBlockDevices()

        assertThat(devices).hasSize(1)
        val d = devices.first()
        assertThat(d.deviceName).isEqualTo("sda")
        assertThat(d.currentScheduler).isEqualTo("mq-deadline")
        assertThat(d.availableSchedulers).containsExactly("none", "mq-deadline", "kyber")
        assertThat(d.readAheadKb).isEqualTo(128)
        assertThat(d.nrRequests).isEqualTo(64)
    }

    @Test
    fun `probeBlockDevices excludes non-storage block devices`() {
        // loop devices should not appear
        write("/sys/block/loop0/queue/scheduler".toPath(), "[none]")
        write("/sys/block/loop0/queue/read_ahead_kb".toPath(), "0")
        write("/sys/block/loop0/queue/nr_requests".toPath(), "16")

        val devices = prober.probeBlockDevices()

        assertThat(devices).isEmpty()
    }

    // --- VM sysctls -----------------------------------------------------

    @Test
    fun `probeVmSysctls reads all four values`() {
        val vmDir = "/proc/sys/vm".toPath()
        write(vmDir / "swappiness", "60")
        write(vmDir / "vfs_cache_pressure", "100")
        write(vmDir / "dirty_ratio", "20")
        write(vmDir / "dirty_background_ratio", "10")

        val vm = prober.probeVmSysctls()

        assertThat(vm).isNotNull()
        assertThat(vm!!.swappiness).isEqualTo(60)
        assertThat(vm.vfsCachePressure).isEqualTo(100)
        assertThat(vm.dirtyRatio).isEqualTo(20)
        assertThat(vm.dirtyBackgroundRatio).isEqualTo(10)
    }

    @Test
    fun `probeVmSysctls returns null when proc path missing`() {
        assertThat(prober.probeVmSysctls()).isNull()
    }

    // --- SchedTune / uclamp interface detection -------------------------

    @Test
    fun `probeSchedBoostInterface detects stune`() {
        write("/dev/stune/top-app/schedtune.boost".toPath(), "0")
        // Just needs the directory to exist; create the top-app dir.
        val result = prober.probeSchedBoostInterface()
        assertThat(result).isEqualTo(SchedBoostInterface.STUNE)
    }

    @Test
    fun `probeSchedBoostInterface detects uclamp when stune absent`() {
        write("/dev/cpuctl/top-app/cpu.uclamp.min".toPath(), "0")
        val result = prober.probeSchedBoostInterface()
        assertThat(result).isEqualTo(SchedBoostInterface.UCLAMP)
    }

    @Test
    fun `probeSchedBoostInterface returns NONE when neither present`() {
        assertThat(prober.probeSchedBoostInterface()).isEqualTo(SchedBoostInterface.NONE)
    }

    @Test
    fun `probeSchedBoostValues reads stune boost per slice`() {
        write("/dev/stune/top-app/schedtune.boost".toPath(), "20")
        write("/dev/stune/top-app/schedtune.prefer_idle".toPath(), "1")
        write("/dev/stune/foreground/schedtune.boost".toPath(), "10")
        write("/dev/stune/foreground/schedtune.prefer_idle".toPath(), "0")

        val values = prober.probeSchedBoostValues(
            iface = SchedBoostInterface.STUNE,
            slices = listOf("top-app", "foreground", "background"),
        )

        // background dir does not exist — should be absent from results
        assertThat(values).hasSize(2)
        val topApp = values.first { it.slice == "top-app" }
        assertThat(topApp.boostOrUclampMin).isEqualTo(20)
        assertThat(topApp.preferIdleOrUclampMax).isEqualTo(1)
    }

    // --- Input boost ----------------------------------------------------

    @Test
    fun `probeInputBoost reads params when module present`() {
        val paramsDir = "/sys/module/cpu_boost/parameters".toPath()
        write(paramsDir / "input_boost_freq", "0:1209600 4:0 7:0")
        write(paramsDir / "input_boost_ms", "40")

        val boost = prober.probeInputBoost()

        assertThat(boost).isNotNull()
        assertThat(boost!!.inputBoostFreqRaw).isEqualTo("0:1209600 4:0 7:0")
        assertThat(boost.inputBoostMs).isEqualTo(40)
    }

    @Test
    fun `probeInputBoost returns null when module absent`() {
        assertThat(prober.probeInputBoost()).isNull()
    }

    // --- GPU governor tunables ------------------------------------------

    @Test
    fun `probeGpuGovernorTunables discovers msm-adreno-tz tunables`() {
        val gpuRoot = "/sys/class/kgsl/kgsl-3d0".toPath()
        write(gpuRoot / "gpu_available_frequencies", "330000000 596000000 950000000")
        write(gpuRoot / "num_pwrlevels", "3")
        val devfreq = gpuRoot / "devfreq"
        write(devfreq / "governor", "msm-adreno-tz")
        write(devfreq / "available_governors", "msm-adreno-tz performance")
        write(devfreq / "min_freq", "330000000")
        write(devfreq / "max_freq", "950000000")
        // Governor tunables sub-dir
        val govDir = devfreq / "msm-adreno-tz"
        write(govDir / "upthreshold", "70")
        write(govDir / "downdifferential", "20")

        val gpu = prober.probeGpu(GpuFamily.ADRENO)
        val tunables = prober.probeGpuGovernorTunables(gpu)

        assertThat(tunables).hasSize(2)
        val up = tunables.first { it.name == "upthreshold" }
        assertThat(up.currentValue).isEqualTo("70")
        assertThat(up.governor).isEqualTo("msm-adreno-tz")
    }

    // --- related_cpus ordering (B15 regression) --------------------------

    @Test
    fun `probeCpuPolicies returns onlineCores in sorted order from unordered related_cpus`() {
        // related_cpus written in non-ascending order — result must still be sorted.
        val root = "/sys/devices/system/cpu/cpufreq/policy4".toPath()
        write(root / "related_cpus", "7 4 5 6")
        write(root / "scaling_available_frequencies", "1000000 2000000")
        write(root / "scaling_min_freq", "1000000")
        write(root / "scaling_max_freq", "2000000")
        write(root / "scaling_governor", "schedutil")

        val policies = prober.probeCpuPolicies()

        assertThat(policies).hasSize(1)
        // parseRelatedCpus previously called .sorted() twice (redundant second sort in
        // parseRelatedCpus); after removing the outer sort the result must still be ordered.
        assertThat(policies.first().onlineCores).containsExactly(4, 5, 6, 7).inOrder()
    }

    // --- Helpers --------------------------------------------------------

    private fun write(path: okio.Path, contents: String) {
        path.parent?.let { fs.createDirectories(it) }
        fs.write(path) { writeUtf8(contents) }
    }
}
