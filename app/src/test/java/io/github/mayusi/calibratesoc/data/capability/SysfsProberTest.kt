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

    @Test
    fun `probeGpu builds an Adreno probe from devfreq min and max when the OPP table is denied (Odin bug 3)`() {
        // Live evidence (Odin 3): /sys/class/kgsl/kgsl-3d0 EXISTS and devfreq/min_freq +
        // max_freq are app-readable (160 MHz .. 1100 MHz), but the OPP frequency table
        // (gpu_available_frequencies / devfreq/available_frequencies / freq_table_mhz)
        // AND num_pwrlevels are SELinux-denied to the app. Before the fix probeAdreno
        // returned null → gpuRootPath / gpuDevfreq* all came back null even though the
        // bounds were readable. Now it must return a probe carrying the live range.
        val root = "/sys/class/kgsl/kgsl-3d0".toPath()
        val devfreq = root / "devfreq"
        write(devfreq / "min_freq", "160000000")
        write(devfreq / "max_freq", "1100000000")
        // NOTE: deliberately NO gpu_available_frequencies, NO available_frequencies,
        // NO freq_table_mhz, NO num_pwrlevels (all denied on the Odin).

        val gpu = prober.probeGpu(GpuFamily.ADRENO)

        assertThat(gpu).isNotNull()
        assertThat(gpu!!.family).isEqualTo(GpuFamily.ADRENO)
        assertThat(gpu.rootPath).isEqualTo("/sys/class/kgsl/kgsl-3d0")
        assertThat(gpu.currentMinHz).isEqualTo(160_000_000L)
        assertThat(gpu.currentMaxHz).isEqualTo(1_100_000_000L)
        // No OPP table → empty steps list (honest), but the envelope bounds are present.
        assertThat(gpu.availableFreqsHz).isEmpty()
        assertThat(gpu.powerLevelRange).isNull()
    }

    @Test
    fun `probeGpu still returns null when the kgsl root exists but nothing is readable`() {
        // Root present but NO freqs, NO num_pwrlevels, NO usable min less-than max range.
        // (min == max is not a usable range → honest null, no fake lever.)
        val devfreq = "/sys/class/kgsl/kgsl-3d0/devfreq".toPath()
        write(devfreq / "min_freq", "300000000")
        write(devfreq / "max_freq", "300000000") // equal → not a range
        assertThat(prober.probeGpu(GpuFamily.ADRENO)).isNull()
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

    // --- readMaxCoolingCurState: THERMAL-RELEVANT filter (cross-device bug 1) ----
    //
    // Live evidence: on a cool, idle Odin 3 AND RP6 the thermal pre-empt fired EVERY
    // tick because readMaxCoolingCurState counted NON-thermal cooling devices that sit
    // pinned high — the Odin `panel0-backlight` (cur=262/max=262), an RP6 device at
    // cur=255, etc. The fix restricts the read to genuine CPU/GPU thermal throttles.

    @Test
    fun `readMaxCoolingCurState ignores the pinned-high panel backlight (Odin bug)`() {
        // The REAL Odin 3 backlight: type=panel0-backlight, cur=262, max=262.
        write("/sys/class/thermal/cooling_device35/type".toPath(), "panel0-backlight")
        write("/sys/class/thermal/cooling_device35/max_state".toPath(), "262")
        write("/sys/class/thermal/cooling_device35/cur_state".toPath(), "262")
        // The REAL thermal throttles, all at 0 on a cool idle device.
        write("/sys/class/thermal/cooling_device1/type".toPath(), "cpu-hotplug1")
        write("/sys/class/thermal/cooling_device1/max_state".toPath(), "1")
        write("/sys/class/thermal/cooling_device1/cur_state".toPath(), "0")
        write("/sys/class/thermal/cooling_device2/type".toPath(), "thermal-devfreq-gpu")
        write("/sys/class/thermal/cooling_device2/max_state".toPath(), "13")
        write("/sys/class/thermal/cooling_device2/cur_state".toPath(), "0")

        // Only the thermal throttles count → max is 0 → engine pre-empt does NOT fire.
        assertThat(prober.readMaxCoolingCurState()).isEqualTo(0)
    }

    @Test
    fun `readMaxCoolingCurState excludes battery and audio cooling devices`() {
        // Non-thermal devices that can sit non-zero during normal use.
        write("/sys/class/thermal/cooling_device30/type".toPath(), "battery")
        write("/sys/class/thermal/cooling_device30/max_state".toPath(), "30")
        write("/sys/class/thermal/cooling_device30/cur_state".toPath(), "12")
        write("/sys/class/thermal/cooling_device37/type".toPath(), "wsa2") // audio amp
        write("/sys/class/thermal/cooling_device37/max_state".toPath(), "11")
        write("/sys/class/thermal/cooling_device37/cur_state".toPath(), "5")
        // A real CPU throttle, also engaged.
        write("/sys/class/thermal/cooling_device0/type".toPath(), "thermal-cpufreq-0")
        write("/sys/class/thermal/cooling_device0/max_state".toPath(), "16")
        write("/sys/class/thermal/cooling_device0/cur_state".toPath(), "3")

        // Battery (12) and audio (5) are excluded; only the CPU throttle (3) counts.
        assertThat(prober.readMaxCoolingCurState()).isEqualTo(3)
    }

    @Test
    fun `readMaxCoolingCurState reports real CPU and GPU throttling when engaged`() {
        write("/sys/class/thermal/cooling_device1/type".toPath(), "cpu-hotplug1")
        write("/sys/class/thermal/cooling_device1/max_state".toPath(), "1")
        write("/sys/class/thermal/cooling_device1/cur_state".toPath(), "1")
        write("/sys/class/thermal/cooling_device2/type".toPath(), "gpu")
        write("/sys/class/thermal/cooling_device2/max_state".toPath(), "13")
        write("/sys/class/thermal/cooling_device2/cur_state".toPath(), "7")

        // Highest thermal-relevant cur_state wins (gpu = 7).
        assertThat(prober.readMaxCoolingCurState()).isEqualTo(7)
    }

    @Test
    fun `readMaxCoolingCurState returns null when only non-thermal devices exist`() {
        // ONLY a pinned backlight present → no thermal-relevant device → null (honest:
        // the engine falls back to die-temp signals, NOT a false "throttling").
        write("/sys/class/thermal/cooling_device35/type".toPath(), "panel0-backlight")
        write("/sys/class/thermal/cooling_device35/max_state".toPath(), "262")
        write("/sys/class/thermal/cooling_device35/cur_state".toPath(), "262")

        assertThat(prober.readMaxCoolingCurState()).isNull()
    }

    @Test
    fun `isThermalThrottleCooling includes real CPU and GPU throttle types`() {
        // Real types observed on Odin 3 / RP6 / SD8Gen2 kernels.
        listOf(
            "cpu-hotplug1", "thermal-cpufreq-0", "cpufreq-cpu0",
            "gpu", "thermal-devfreq-gpu", "tsens_tz_sensor0",
            "cpu-isolate0", "soc_cooling",
        ).forEach { type ->
            assertThat(prober.isThermalThrottleCooling(type)).isTrue()
        }
    }

    @Test
    fun `isThermalThrottleCooling excludes non-thermal cooling types`() {
        listOf(
            "panel0-backlight", "panel1-backlight", "battery",
            "charger", "charge", "wsa2", "wsa-speaker", "led-flash",
            "haptic", "vibrator", "", "   ",
        ).forEach { type ->
            assertThat(prober.isThermalThrottleCooling(type)).isFalse()
        }
    }

    // --- HIGH-3: GPU die-temp unit normalization ------------------------

    @Test
    fun `normalizeDieTempMilliC passes a sane milli-C reading unchanged`() {
        // Odin 3 observed value: 37800 = 37.8 C, already milli-C.
        assertThat(prober.normalizeDieTempMilliC(37_800)).isEqualTo(37_800)
        assertThat(prober.normalizeDieTempMilliC(95_000)).isEqualTo(95_000)
    }

    @Test
    fun `normalizeDieTempMilliC scales deci-C up to milli-C`() {
        // 378 deci-C = 37.8 C → 37800 milli-C (the blinding case: /1000 would give 0 C).
        assertThat(prober.normalizeDieTempMilliC(378)).isEqualTo(37_800)
        // 950 deci-C = 95 C.
        assertThat(prober.normalizeDieTempMilliC(950)).isEqualTo(95_000)
    }

    @Test
    fun `normalizeDieTempMilliC scales whole-C up to milli-C`() {
        assertThat(prober.normalizeDieTempMilliC(38)).isEqualTo(38_000)
        assertThat(prober.normalizeDieTempMilliC(95)).isEqualTo(95_000)
    }

    @Test
    fun `normalizeDieTempMilliC scales micro-C down to milli-C`() {
        // 37_800_000 micro-C = 37.8 C → /1000 = 37800 milli-C (would otherwise be 37800 C).
        assertThat(prober.normalizeDieTempMilliC(37_800_000)).isEqualTo(37_800)
    }

    @Test
    fun `normalizeDieTempMilliC rejects implausible readings as null`() {
        assertThat(prober.normalizeDieTempMilliC(0)).isNull()         // dead/zero sensor
        assertThat(prober.normalizeDieTempMilliC(5)).isNull()         // too low even as whole-C
        assertThat(prober.normalizeDieTempMilliC(200_000)).isNull()   // 200 C milli — off-scale
        assertThat(prober.normalizeDieTempMilliC(150)).isNull()       // 150 C whole — off-scale
    }

    @Test
    fun `readGpuDieTempMilliC normalizes a deci-C node and never returns a 0 C blind value`() {
        // A device reporting deci-C: raw 378 must become 37800 milli-C, NOT 0 C.
        write("/sys/class/kgsl/kgsl-3d0/temp".toPath(), "378")
        val v = prober.readGpuDieTempMilliC("/sys/class/kgsl/kgsl-3d0")
        assertThat(v).isEqualTo(37_800)
    }

    @Test
    fun `readGpuDieTempMilliC returns null for an implausible node so zones win`() {
        write("/sys/class/kgsl/kgsl-3d0/temp".toPath(), "0")
        assertThat(prober.readGpuDieTempMilliC("/sys/class/kgsl/kgsl-3d0")).isNull()
    }

    @Test
    fun `readGpuDieTempMilliC returns null when no GPU root`() {
        assertThat(prober.readGpuDieTempMilliC(null)).isNull()
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
    fun `probeSchedBoostInterface detects uclamp from the FLOAT format (cross-device bug 2)`() {
        // Live evidence: on the Odin 3 AND RP6, `cat
        // /dev/cpuctl/top-app/cpu.uclamp.min` returns "0.00" (a FLOAT, app-readable),
        // yet uclampAvailable came back false. The detector must accept the float.
        write("/dev/cpuctl/top-app/cpu.uclamp.min".toPath(), "0.00")
        assertThat(prober.probeSchedBoostInterface()).isEqualTo(SchedBoostInterface.UCLAMP)
    }

    @Test
    fun `probeSchedBoostInterface accepts a non-zero uclamp float`() {
        write("/dev/cpuctl/top-app/cpu.uclamp.min".toPath(), "37.50")
        assertThat(prober.probeSchedBoostInterface()).isEqualTo(SchedBoostInterface.UCLAMP)
    }

    @Test
    fun `probeSchedBoostInterface returns NONE when neither present`() {
        assertThat(prober.probeSchedBoostInterface()).isEqualTo(SchedBoostInterface.NONE)
    }

    @Test
    fun `probeSchedBoostValues reads uclamp float percentages and rounds to Int`() {
        // cgroup cpu.uclamp.{min,max} are floats; the integer-only read would lose them.
        write("/dev/cpuctl/top-app/cpu.uclamp.min".toPath(), "0.00")
        write("/dev/cpuctl/top-app/cpu.uclamp.max".toPath(), "100.00")
        write("/dev/cpuctl/foreground/cpu.uclamp.min".toPath(), "37.50")
        write("/dev/cpuctl/foreground/cpu.uclamp.max".toPath(), "100.00")

        val values = prober.probeSchedBoostValues(
            iface = SchedBoostInterface.UCLAMP,
            slices = listOf("top-app", "foreground", "background"),
        )

        // background has no node → dropped honestly.
        assertThat(values).hasSize(2)
        val topApp = values.first { it.slice == "top-app" }
        assertThat(topApp.boostOrUclampMin).isEqualTo(0)
        assertThat(topApp.preferIdleOrUclampMax).isEqualTo(100)
        val fg = values.first { it.slice == "foreground" }
        assertThat(fg.boostOrUclampMin).isEqualTo(38) // 37.50 rounds to 38
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

    // --- Privileged-read fallback (Odin 3 EACCES nodes) ----------------------
    //
    // Root cause proven off-device: on the Odin 3 the app UID cannot read the cgroup
    // uclamp slice or the kgsl devfreq bounds directly (EACCES — Okio's read goes straight
    // to FileInputStream, no stat, exactly like `cat`, but the app's SELinux domain is
    // denied where the `shell` / PServer-root domain is allowed). The probe LOGIC was
    // right; the read CHANNEL was wrong. These tests model that exact split: the node is
    // ABSENT from the (app-visible) FakeFileSystem, but a privileged reader supplies the
    // root-readable value — and the probe must then populate the field.

    @Test
    fun `probeSchedBoostInterface detects uclamp via privileged fallback when app read is denied`() {
        // App-direct read denied → node NOT in the app-visible FS at all. The cgroup mount
        // point exists (so /dev/cpuctl is "there") but cpu.uclamp.min is unreadable to us.
        fs.createDirectories("/dev/cpuctl/top-app".toPath())
        val priv = FakePrivilegedReader(
            mapOf("/dev/cpuctl/top-app/cpu.uclamp.min" to "0.00"),
        )
        val proberWithPriv = SysfsProber(fs, priv)

        // Without the privileged fallback this would be NONE (the bug); with it, UCLAMP.
        assertThat(proberWithPriv.probeSchedBoostInterface())
            .isEqualTo(SchedBoostInterface.UCLAMP)
    }

    @Test
    fun `probeGpu populates devfreq envelope via privileged fallback when app read is denied`() {
        // kgsl root exists (some sibling node is app-readable so fs.exists(root) is true),
        // but devfreq/min_freq + max_freq are EACCES to the app → absent from the app FS.
        // The privileged reader returns the Odin 3 values (160 MHz .. 1100 MHz).
        val root = "/sys/class/kgsl/kgsl-3d0".toPath()
        write(root / "devfreq" / "governor", "msm-adreno-tz") // makes root + devfreq exist
        val priv = FakePrivilegedReader(
            mapOf(
                "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq" to "160000000",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq" to "1100000000",
            ),
        )
        val proberWithPriv = SysfsProber(fs, priv)

        val gpu = proberWithPriv.probeGpu(GpuFamily.ADRENO)

        assertThat(gpu).isNotNull()
        assertThat(gpu!!.rootPath).isEqualTo("/sys/class/kgsl/kgsl-3d0")
        assertThat(gpu.currentMinHz).isEqualTo(160_000_000L)
        assertThat(gpu.currentMaxHz).isEqualTo(1_100_000_000L)
    }

    @Test
    fun `TdpCaps from yields uclampAvailable and populated devfreq from the privileged-read probes`() {
        // End-to-end: the two privileged-fallback probes feed a CapabilityReport, and
        // TdpCaps.from(report) must then expose uclampAvailable=true plus a real GPU
        // devfreq envelope — the exact fields that came back false/null at runtime on the
        // Odin 3. Proves the fix flows all the way to what the AutoTDP engine consumes.
        fs.createDirectories("/dev/cpuctl/top-app".toPath())
        val root = "/sys/class/kgsl/kgsl-3d0".toPath()
        write(root / "devfreq" / "governor", "msm-adreno-tz")
        val priv = FakePrivilegedReader(
            mapOf(
                "/dev/cpuctl/top-app/cpu.uclamp.min" to "0.00",
                "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq" to "160000000",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq" to "1100000000",
            ),
        )
        val proberWithPriv = SysfsProber(fs, priv)

        val gpu = proberWithPriv.probeGpu(GpuFamily.ADRENO)
        val schedIface = proberWithPriv.probeSchedBoostInterface()

        // Build a minimal CapabilityReport carrying ONLY the two probed fields under test
        // (every other field is a benign default) and run the real TdpCaps mapping.
        val report = io.github.mayusi.calibratesoc.data.capability.CapabilityReport(
            device = io.github.mayusi.calibratesoc.data.capability.DeviceIdentity(
                manufacturer = "AYN", brand = "AYN", model = "Odin3", device = "odin3",
                hardware = "qcom", androidVersion = "14", sdkInt = 34, knownHandheldKey = null,
            ),
            soc = io.github.mayusi.calibratesoc.data.capability.SoCIdentity(
                "Qualcomm", "CQ8725S", GpuFamily.ADRENO,
            ),
            privilege = io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.NONE,
            rootKind = io.github.mayusi.calibratesoc.data.capability.RootKind.NONE,
            shizuku = io.github.mayusi.calibratesoc.data.capability.ShizukuStatus(false, false, false, null),
            cpuPolicies = emptyList(),
            gpu = gpu,
            thermalZones = emptyList(),
            fan = null,
            vendorApps = io.github.mayusi.calibratesoc.data.capability.VendorAppPresence(false, false, false),
            schedBoostInterface = schedIface,
        )
        val caps = io.github.mayusi.calibratesoc.data.autotdp.TdpCaps.from(report)

        assertThat(caps.uclampAvailable).isTrue()
        assertThat(caps.gpuRootPath).isEqualTo("/sys/class/kgsl/kgsl-3d0")
        assertThat(caps.gpuDevfreqFloorHz).isEqualTo(160_000_000L)
        assertThat(caps.gpuDevfreqCeilHz).isEqualTo(1_100_000_000L)
    }

    @Test
    fun `probeGpu keeps honest null when neither app nor privileged read can reach the bounds`() {
        // RP6 case: kgsl root exists but the devfreq bounds are SELinux-denied to BOTH the
        // app AND PServer. The privileged reader returns null for those paths → the probe
        // must NOT fabricate a lever; it stays null.
        val root = "/sys/class/kgsl/kgsl-3d0".toPath()
        write(root / "devfreq" / "governor", "msm-adreno-tz")
        val priv = FakePrivilegedReader(emptyMap()) // privileged read also denied
        val proberWithPriv = SysfsProber(fs, priv)

        assertThat(proberWithPriv.probeGpu(GpuFamily.ADRENO)).isNull()
    }

    // --- Helpers --------------------------------------------------------

    private fun write(path: okio.Path, contents: String) {
        path.parent?.let { fs.createDirectories(it) }
        fs.write(path) { writeUtf8(contents) }
    }

    /**
     * Test double for [PrivilegedSysfsReader] backed by a fixed path→value map. Models the
     * PServer-root `cat` channel: returns a value only for nodes the privileged context can
     * read (everything else → null, the honest "unreadable" result).
     */
    private class FakePrivilegedReader(
        private val values: Map<String, String>,
    ) : PrivilegedSysfsReader(pServerWriter = null) {
        // catOrNull is fully overridden, so the null PServerWriter is never dereferenced.
        override fun catOrNull(path: String): String? = values[path]
    }
}
