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
import io.github.mayusi.calibratesoc.data.capability.SchedBoostInterface
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import org.junit.Test

/**
 * Unit tests for [TdpCaps.from].
 *
 * Verifies that prime-core detection, OPP-step sorting, GPU level extraction,
 * and min-online-core floor derivation all work correctly from a fake
 * [CapabilityReport].
 */
class TdpCapsTest {

    // ─── Helper builders ───────────────────────────────────────────────────────

    private fun policy(
        id: Int,
        freqsMhz: List<Int>,
        onlineCores: List<Int> = listOf(id),
    ) = CpuPolicyProbe(
        policyId = id,
        onlineCores = onlineCores,
        availableFreqsKhz = freqsMhz.map { it * 1000 },
        availableGovernors = listOf("schedutil"),
        currentMinKhz = freqsMhz.first() * 1000,
        currentMaxKhz = freqsMhz.last() * 1000,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsMhz.first() * 1000, freqsMhz.last() * 1000),
    )

    private fun reportWith(
        policies: List<CpuPolicyProbe>,
        gpuMinLevel: Int? = null,
        gpuMaxLevel: Int? = null,
    ): CapabilityReport {
        val adrenoExtras = if (gpuMinLevel != null || gpuMaxLevel != null) {
            AdrenoExtrasProbe(
                pwrLevelFreqHz = emptyMap(),
                currentMinPwrLevel = gpuMinLevel,
                currentMaxPwrLevel = gpuMaxLevel,
                currentDefaultPwrLevel = null,
                throttlingEnabled = null,
                forceClkOn = null,
                idleTimerMs = null,
            )
        } else null

        val gpu = if (gpuMinLevel != null) {
            GpuProbe(
                family = GpuFamily.ADRENO,
                rootPath = "/sys/class/kgsl/kgsl-3d0",
                availableFreqsHz = listOf(180_000_000L, 490_000_000L, 670_000_000L),
                availableGovernors = listOf("msm-adreno-tz"),
                currentMinHz = 180_000_000L,
                currentMaxHz = 670_000_000L,
                currentGovernor = "msm-adreno-tz",
                powerLevelRange = LevelRange(gpuMinLevel, gpuMaxLevel ?: 6),
            )
        } else null

        return CapabilityReport(
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
            privilege = PrivilegeTier.ROOT,
            rootKind = RootKind.MAGISK,
            shizuku = ShizukuStatus(false, false, false, null),
            cpuPolicies = policies,
            gpu = gpu,
            thermalZones = emptyList(),
            fan = null,
            vendorApps = VendorAppPresence(false, false, false),
            adrenoExtras = adrenoExtras,
        )
    }

    // ─── 3-cluster topology (SD8Gen2) ──────────────────────────────────────────

    @Test
    fun `3-cluster device prime cores are top cluster minus cpu0`() {
        // policy0 = little (cores 0-3),  top 2016 MHz
        // policy4 = gold   (cores 4-6),  top 2803 MHz
        // policy7 = prime  (core 7 only), top 3187 MHz
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(307, 556, 902, 1228, 1555, 1900, 2016), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803), onlineCores = listOf(4, 5, 6)),
                policy(7, listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187), onlineCores = listOf(7)),
            )
        )

        val caps = TdpCaps.from(report)

        // Core 7 is the only prime core; cpu0 must not be included.
        assertThat(caps.primeCoreIndices).containsExactly(7)
        assertThat(caps.primeCoreIndices).doesNotContain(0)
    }

    @Test
    fun `3-cluster device big policy id is gold policy`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(307, 2016), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(499, 2803), onlineCores = listOf(4, 5, 6)),
                policy(7, listOf(595, 3187), onlineCores = listOf(7)),
            )
        )
        val caps = TdpCaps.from(report)

        // Big policy = gold (policy4), not prime (policy7) or little (policy0).
        assertThat(caps.bigPolicyId).isEqualTo(4)
    }

    @Test
    fun `3-cluster device OPP steps are sorted ascending`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(307, 2016), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(2803, 499, 1920, 844), onlineCores = listOf(4, 5, 6)), // unsorted
                policy(7, listOf(595, 3187), onlineCores = listOf(7)),
            )
        )
        val caps = TdpCaps.from(report)

        val steps = caps.bigClusterOppStepsKhz
        assertThat(steps).isInOrder()
        assertThat(steps.first()).isLessThan(steps.last())
    }

    // ─── 2-cluster topology (Odin 3 / SD8 Elite) ──────────────────────────────

    @Test
    fun `2-cluster device prime cores are high-opp policy cores excluding cpu0`() {
        // policy0 = cores 0-5 (little), top 3532 MHz
        // policy6 = cores 6-7 (prime),  top 4320 MHz
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384, 3532), onlineCores = listOf(0, 1, 2, 3, 4, 5)),
                policy(6, listOf(1017, 4320), onlineCores = listOf(6, 7)),
            )
        )
        val caps = TdpCaps.from(report)

        // Prime = policy6's cores = [6, 7]; cpu0 not there, so no exclusion needed.
        assertThat(caps.primeCoreIndices).containsExactly(6, 7)
        assertThat(caps.primeCoreIndices).doesNotContain(0)
    }

    @Test
    fun `2-cluster device big policy id is prime policy cap target`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384, 3532), onlineCores = listOf(0, 1, 2, 3, 4, 5)),
                policy(6, listOf(1017, 4320), onlineCores = listOf(6, 7)),
            )
        )
        val caps = TdpCaps.from(report)

        // On a 2-cluster device, the single high-OPP policy (policy6) is both prime
        // and the big-cluster cap target.
        assertThat(caps.bigPolicyId).isEqualTo(6)
    }

    // ─── Single-cluster (corner case) ────────────────────────────────────────

    @Test
    fun `single-cluster device prime cores exclude cpu0 from the only policy`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))
            )
        )
        val caps = TdpCaps.from(report)

        // cpu0 is excluded; remaining cores in the only policy are prime candidates.
        assertThat(caps.primeCoreIndices).doesNotContain(0)
        // Cores 1, 2, 3 should be prime on a single-cluster device.
        assertThat(caps.primeCoreIndices).containsExactly(1, 2, 3)
    }

    // ─── cpu0 never in prime cores ────────────────────────────────────────────

    @Test
    fun `cpu0 is never included in prime core indices`() {
        // Verify across multiple topology types.
        val topologies = listOf(
            // 3-cluster
            listOf(
                policy(0, listOf(307, 2016), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(499, 2803), onlineCores = listOf(4, 5, 6)),
                policy(7, listOf(595, 3187), onlineCores = listOf(7)),
            ),
            // 2-cluster: cpu0 in the same policy as other low-cluster cores
            listOf(
                policy(0, listOf(384, 2000), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(800, 3500), onlineCores = listOf(4, 5, 6, 7)),
            ),
            // 1-cluster
            listOf(
                policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3)),
            ),
            // Edge: cpu0 is in the top-OPP policy (pathological but possible)
            listOf(
                policy(0, listOf(384, 3000), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(800, 2000), onlineCores = listOf(4, 5, 6, 7)),
            ),
        )

        for (policies in topologies) {
            val caps = TdpCaps.from(reportWith(policies))
            assertThat(caps.primeCoreIndices).doesNotContain(0)
        }
    }

    // ─── GPU power level extraction ───────────────────────────────────────────

    @Test
    fun `gpu min and max power levels populated from AdrenoExtrasProbe`() {
        val report = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))),
            gpuMinLevel = 0,
            gpuMaxLevel = 6,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.gpuMinLevel).isEqualTo(0)
        assertThat(caps.gpuMaxLevel).isEqualTo(6)
    }

    @Test
    fun `gpu levels are null when no AdrenoExtrasProbe`() {
        val report = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))),
            gpuMinLevel = null,
            gpuMaxLevel = null,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.gpuMinLevel).isNull()
        assertThat(caps.gpuMaxLevel).isNull()
    }

    // ─── GPU devfreq envelope (cross-device bug 3) ────────────────────────────
    //
    // On the Odin 3 the OPP table is SELinux-denied but devfreq/min_freq=160 MHz and
    // max_freq=1100 MHz ARE readable. SysfsProber.probeAdreno now returns a GpuProbe
    // with an EMPTY availableFreqsHz but currentMin/MaxHz set; TdpCaps.from must
    // populate the devfreq floor/ceil + rootPath from those live bounds (the steps
    // list stays empty honestly, so the devfreq STEP lever is skipped — but the
    // envelope is present so gpuRootPath is no longer null).

    @Test
    fun `gpu devfreq envelope populates from live min and max when OPP table is empty`() {
        val base = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))),
            gpuMinLevel = 0,
            gpuMaxLevel = 6,
        )
        // Replace the GPU with the Odin-shaped probe: empty OPP table, live bounds set.
        val report = base.copy(
            gpu = GpuProbe(
                family = GpuFamily.ADRENO,
                rootPath = "/sys/class/kgsl/kgsl-3d0",
                availableFreqsHz = emptyList(), // OPP table denied
                availableGovernors = emptyList(),
                currentMinHz = 160_000_000L,
                currentMaxHz = 1_100_000_000L,
                currentGovernor = "msm-adreno-tz",
                powerLevelRange = null,
            ),
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.gpuRootPath).isEqualTo("/sys/class/kgsl/kgsl-3d0")
        assertThat(caps.gpuDevfreqFloorHz).isEqualTo(160_000_000L)
        assertThat(caps.gpuDevfreqCeilHz).isEqualTo(1_100_000_000L)
        // No OPP table → no discrete steps (the STEP lever is honestly skipped).
        assertThat(caps.gpuDevfreqStepsHz).isEmpty()
    }

    // ─── uclamp availability (cross-device bug 2) ─────────────────────────────

    @Test
    fun `uclampAvailable is true when schedBoostInterface is UCLAMP`() {
        // SysfsProber now reports UCLAMP from a readable cpu.uclamp.min (incl. "0.00").
        // TdpCaps must surface that as uclampAvailable so the UCLAMP lever can engage.
        val report = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))),
        ).copy(schedBoostInterface = SchedBoostInterface.UCLAMP)
        assertThat(TdpCaps.from(report).uclampAvailable).isTrue()
    }

    @Test
    fun `uclampAvailable is false when schedBoostInterface is NONE`() {
        val report = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))),
        ).copy(schedBoostInterface = SchedBoostInterface.NONE)
        assertThat(TdpCaps.from(report).uclampAvailable).isFalse()
    }

    @Test
    fun `gpu devfreq envelope and root are null when the GPU was not probed`() {
        val report = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3))),
            gpuMinLevel = null, // → gpu == null in reportWith
            gpuMaxLevel = null,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.gpuRootPath).isNull()
        assertThat(caps.gpuDevfreqFloorHz).isNull()
        assertThat(caps.gpuDevfreqCeilHz).isNull()
        assertThat(caps.gpuDevfreqStepsHz).isEmpty()
    }

    // ─── Min-online-core floor ────────────────────────────────────────────────

    @Test
    fun `min online cores is at least 2`() {
        val report = reportWith(
            policies = listOf(policy(0, listOf(500, 2000), onlineCores = listOf(0, 1)))
        )
        val caps = TdpCaps.from(report)
        assertThat(caps.minOnlineCores).isAtLeast(2)
    }

    @Test
    fun `min online cores is half of total on 8-core device`() {
        // 8 cores total → min = max(2, 8/2) = 4
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(500, 2000), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, listOf(800, 3000), onlineCores = listOf(4, 5, 6, 7)),
            )
        )
        val caps = TdpCaps.from(report)
        assertThat(caps.totalOnlineCores).isEqualTo(8)
        assertThat(caps.minOnlineCores).isEqualTo(4)
    }

    // ─── OPP steps sorted and non-empty where policy exists ──────────────────

    @Test
    fun `big cluster OPP steps are sorted ascending`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(307, 2016), onlineCores = listOf(0, 1, 2, 3)),
                // Deliberately out-of-order freqs:
                policy(4, listOf(2803, 499, 1920), onlineCores = listOf(4, 5, 6)),
                policy(7, listOf(595, 3187), onlineCores = listOf(7)),
            )
        )
        val caps = TdpCaps.from(report)
        assertThat(caps.bigClusterOppStepsKhz).isInOrder()
    }
}
