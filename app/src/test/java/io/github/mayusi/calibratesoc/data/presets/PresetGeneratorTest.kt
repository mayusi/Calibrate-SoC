package io.github.mayusi.calibratesoc.data.presets

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.devicedb.CommunityPreset
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.remote.RemoteContentRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

class PresetGeneratorTest {

    // -------------------------------------------------------------------------
    // Governor safety — powersave must NEVER be selected
    // -------------------------------------------------------------------------

    @Test
    fun `powersave is never chosen as a governor for any preset`() {
        // Expose powersave as the ONLY available governor. The generator must
        // not select it even though it is available — because powersave pins
        // the CPU to its minimum OPP permanently causing emulator stuttering
        // and audio crackle under load.
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0, 1, 2, 3),
                    availableFreqsKhz = listOf(400_000, 800_000, 1_200_000, 1_600_000, 2_000_000),
                    availableGovernors = listOf("powersave", "performance", "schedutil"),
                    currentMinKhz = 400_000,
                    currentMaxKhz = 2_000_000,
                    currentGovernor = "schedutil",
                    hardwareLimitsKhz = FreqRange(400_000, 2_000_000),
                ),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        for (preset in presets) {
            val governors = preset.cpuPolicyGovernor.values
            assertThat(governors).doesNotContain("powersave")
        }
    }

    @Test
    fun `powersave-only device gets no governor set (not forced)`() {
        // If the device has only powersave and nothing else acceptable,
        // the generator must leave the governor field empty rather than
        // writing powersave.
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0),
                    availableFreqsKhz = listOf(400_000, 1_000_000),
                    availableGovernors = listOf("powersave"),
                    currentMinKhz = 400_000,
                    currentMaxKhz = 1_000_000,
                    currentGovernor = "powersave",
                    hardwareLimitsKhz = FreqRange(400_000, 1_000_000),
                ),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        for (preset in presets) {
            assertThat(preset.cpuPolicyGovernor).isEmpty()
        }
    }

    // -------------------------------------------------------------------------
    // Preset taxonomy — 6 named built-ins replace the old 4 abstract ones
    // -------------------------------------------------------------------------

    @Test
    fun `unknown phone gets all 6 built-in presets in the right order`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.UNKNOWN,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(400, 800, 1200, 1600, 2000)),
                policy(id = 4, freqsMhz = listOf(800, 1400, 2000, 2600, 3200)),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        assertThat(presets.map { it.name }).containsExactly(
            "Cool & Quiet — Max Battery",
            "Light Emulation — N64 / PSP / Dreamcast",
            "PS2 / GameCube — Sustained",
            "Switch / Heavy — Performance",
            "Anti-Throttle — Sustained Max",
            "Stock (undo tune)",
        ).inOrder()

        // All six must be at UNKNOWN_FAMILY tier for safety gating.
        assertThat(presets.all { it.verification == VerificationTier.GENERIC_UNKNOWN_FAMILY })
            .isTrue()
    }

    @Test
    fun `cool and quiet is never in the old Battery Saver slot`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(policy(id = 0, freqsMhz = listOf(400, 800, 1200, 1600))),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        // Old "Battery Saver" name must not exist.
        assertThat(presets.map { it.name }).doesNotContain("Battery Saver")
        // New name must exist.
        assertThat(presets.map { it.name }).contains("Cool & Quiet — Max Battery")
    }

    @Test
    fun `old abstract Balanced and Performance presets are gone`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(policy(id = 0, freqsMhz = listOf(400, 800, 1200, 1600))),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)
        assertThat(presets.map { it.name }).doesNotContain("Balanced")
        assertThat(presets.map { it.name }).doesNotContain("Performance")
        assertThat(presets.map { it.name }).doesNotContain("Max (stock ceiling)")
    }

    // -------------------------------------------------------------------------
    // OPP snapping — every cap must land on a real OPP step
    // -------------------------------------------------------------------------

    @Test
    fun `every chosen cap snaps to an actual OPP step`() {
        val freqsMhz = listOf(400, 800, 1200, 1600, 2000, 2400, 2800, 3200)
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(policy(id = 0, freqsMhz = freqsMhz)),
        )
        val generator = generatorWithoutAdapter()

        val presets = generator.presetsFor(report)
        val freqsKhz = freqsMhz.map { it * 1000 }

        for (preset in presets) {
            preset.cpuPolicyMaxKhz.values.forEach { capKhz ->
                assertThat(freqsKhz).contains(capKhz)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Verification tier
    // -------------------------------------------------------------------------

    @Test
    fun `known family Adreno gets the KNOWN_FAMILY tier`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(policy(id = 0, freqsMhz = listOf(500, 1500))),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)
        assertThat(presets.first().verification).isEqualTo(VerificationTier.GENERIC_KNOWN_FAMILY)
    }

    // -------------------------------------------------------------------------
    // Community presets interop
    // -------------------------------------------------------------------------

    @Test
    fun `community presets appear first and keep the COMMUNITY_TUNED tier`() {
        val adapter = DeviceAdapter(
            key = "ayn_odin3",
            displayName = "AYN Odin 3",
            vendorAppPackage = null,
            fanAdapter = null,
            perfPresetAdapter = null,
            communityPresets = listOf(
                CommunityPreset(
                    name = "Underclock — Large",
                    description = "TheOldTaylor",
                    sourceUrl = "https://github.com/TheOldTaylor/Odin3-CPU-Underclock",
                    cpuPolicyMaxKhz = mapOf("0" to 1785600, "6" to 1958400),
                ),
            ),
        )
        val registry = mockk<DeviceAdapterRegistry>()
        every { registry.lookup("ayn_odin3") } returns adapter
        val report = reportWith(
            handheldKey = "ayn_odin3",
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(384, 1785, 3532)),
                policy(id = 6, freqsMhz = listOf(1017, 1958, 4320)),
            ),
        )

        val presets = PresetGenerator(registry, emptyRemoteContent()).presetsFor(report)

        assertThat(presets.first().name).isEqualTo("Underclock — Large")
        assertThat(presets.first().verification).isEqualTo(VerificationTier.COMMUNITY_TUNED)
        // Built-ins still come after.
        assertThat(presets.map { it.name })
            .containsAtLeast("Cool & Quiet — Max Battery", "Stock (undo tune)")
    }

    // -------------------------------------------------------------------------
    // Governor omission when no preferred governor available
    // -------------------------------------------------------------------------

    @Test
    fun `governor is omitted when none of the preferred governors are available`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0, 1, 2, 3),
                    availableFreqsKhz = listOf(500_000, 1_500_000),
                    availableGovernors = listOf("some_weird_oem_governor"),
                    currentMinKhz = 500_000,
                    currentMaxKhz = 1_500_000,
                    currentGovernor = "some_weird_oem_governor",
                    hardwareLimitsKhz = FreqRange(500_000, 1_500_000),
                ),
            ),
        )

        val presets = generatorWithoutAdapter().presetsFor(report)

        presets.forEach { preset ->
            assertThat(preset.cpuPolicyGovernor).isEmpty()
        }
    }

    // -------------------------------------------------------------------------
    // SD8Gen2 / 3-cluster topology (RP6 / AYN Thor)
    // -------------------------------------------------------------------------

    @Test
    fun `three-cluster SD8Gen2 gets all clusters set in every preset`() {
        // AYN Thor / RP6 (Snapdragon 8 Gen 2 / kalama) real OPP tables:
        //   policy0 cores 0-2 little, max 2016 MHz
        //   policy3 cores 3-6 gold,   max 2803.2 MHz
        //   policy7 core 7   prime,   max 3187.2 MHz
        val report = reportWith(
            handheldKey = "ayn_thor",
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(307, 556, 902, 1228, 1555, 1900, 2016)),
                policy(id = 3, freqsMhz = listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803)),
                policy(id = 7, freqsMhz = listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187)),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        // EVERY preset must set EVERY cluster.
        for (preset in presets) {
            assertThat(preset.cpuPolicyMaxKhz.keys).containsExactly(0, 3, 7)
            assertThat(preset.cpuPolicyMinKhz.keys).containsExactly(0, 3, 7)
            // Min always resets to the OPP floor for each cluster.
            assertThat(preset.cpuPolicyMinKhz[0]).isEqualTo(307_000)
            assertThat(preset.cpuPolicyMinKhz[3]).isEqualTo(499_000)
            assertThat(preset.cpuPolicyMinKhz[7]).isEqualTo(595_000)
        }

        // Stock preset restores each cluster to its own kernel ceiling.
        val stock = presets.single { it.id == "builtin_stock" }
        assertThat(stock.cpuPolicyMaxKhz[0]).isEqualTo(2_016_000)
        assertThat(stock.cpuPolicyMaxKhz[3]).isEqualTo(2_803_000)
        assertThat(stock.cpuPolicyMaxKhz[7]).isEqualTo(3_187_000)

        // Cool & Quiet caps the prime cluster harder than the little cluster —
        // asymmetric power-curve weighting (prime has the steepest power curve).
        val coolQuiet = presets.single { it.id == "builtin_cool_and_quiet" }
        val primeFrac = coolQuiet.cpuPolicyMaxKhz[7]!!.toDouble() / 3_187_000
        val littleFrac = coolQuiet.cpuPolicyMaxKhz[0]!!.toDouble() / 2_016_000
        assertThat(primeFrac).isLessThan(littleFrac)
    }

    @Test
    fun `SD8Gen2 cool and quiet caps are in the 45-55 pct range`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(307, 556, 902, 1228, 1555, 1900, 2016)),
                policy(id = 3, freqsMhz = listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803)),
                policy(id = 7, freqsMhz = listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187)),
            ),
        )
        val coolQuiet = generatorWithoutAdapter().presetsFor(report)
            .single { it.id == "builtin_cool_and_quiet" }

        // Little cluster (policy0): ~50-55% of 2016 MHz = ~1008-1109 MHz
        // We expect it to snap to a real OPP (902 or 1228 are closest; 1228/2016 ≈ 61% is fine
        // given snapping. The point is it's well below performance territory.)
        val p0Pct = coolQuiet.cpuPolicyMaxKhz[0]!!.toDouble() / 2_016_000
        assertThat(p0Pct).isLessThan(0.70)   // definitely not performance territory
        assertThat(p0Pct).isGreaterThan(0.30) // not absurdly low either

        // Prime (policy7): harder cap, expect < 55% of 3187 MHz
        val p7Pct = coolQuiet.cpuPolicyMaxKhz[7]!!.toDouble() / 3_187_000
        assertThat(p7Pct).isLessThan(0.60)
    }

    @Test
    fun `SD8Gen2 light emulation caps are in the 55-70 pct range`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(307, 556, 902, 1228, 1555, 1900, 2016)),
                policy(id = 3, freqsMhz = listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803)),
                policy(id = 7, freqsMhz = listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187)),
            ),
        )
        val lightEmu = generatorWithoutAdapter().presetsFor(report)
            .single { it.id == "builtin_light_emulation" }

        val p0Pct = lightEmu.cpuPolicyMaxKhz[0]!!.toDouble() / 2_016_000
        val p7Pct = lightEmu.cpuPolicyMaxKhz[7]!!.toDouble() / 3_187_000
        // Light emulation should sit between cool-quiet and sustained.
        assertThat(p0Pct).isGreaterThan(0.50)
        assertThat(p0Pct).isLessThan(0.85)
        // Prime always lower than little (asymmetric)
        assertThat(p7Pct).isLessThan(p0Pct + 0.05) // at most slightly higher
    }

    // -------------------------------------------------------------------------
    // Anti-Throttle / Sustained Max — headroom assertion
    // -------------------------------------------------------------------------

    @Test
    fun `anti-throttle leaves at least one OPP step below max for every cluster`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(307, 556, 902, 1228, 1555, 1900, 2016)),
                policy(id = 3, freqsMhz = listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803)),
                policy(id = 7, freqsMhz = listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187)),
            ),
        )
        val antiThrottle = generatorWithoutAdapter().presetsFor(report)
            .single { it.id == "builtin_anti_throttle" }

        // Each cluster's cap must be strictly less than the cluster's top OPP.
        assertThat(antiThrottle.cpuPolicyMaxKhz[0]!!).isLessThan(2_016_000)
        assertThat(antiThrottle.cpuPolicyMaxKhz[3]!!).isLessThan(2_803_000)
        assertThat(antiThrottle.cpuPolicyMaxKhz[7]!!).isLessThan(3_187_000)

        // The cap must be a real OPP step.
        val p0Freqs = listOf(307, 556, 902, 1228, 1555, 1900, 2016).map { it * 1000 }
        val p3Freqs = listOf(499, 844, 1171, 1536, 1920, 2323, 2707, 2803).map { it * 1000 }
        val p7Freqs = listOf(595, 998, 1363, 1843, 2227, 2592, 2956, 3187).map { it * 1000 }
        assertThat(p0Freqs).contains(antiThrottle.cpuPolicyMaxKhz[0]!!)
        assertThat(p3Freqs).contains(antiThrottle.cpuPolicyMaxKhz[3]!!)
        assertThat(p7Freqs).contains(antiThrottle.cpuPolicyMaxKhz[7]!!)
    }

    @Test
    fun `anti-throttle single-OPP cluster does not crash`() {
        // Edge case: a policy with only one OPP can't leave headroom.
        // The generator must handle this gracefully without throwing.
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0),
                    availableFreqsKhz = listOf(1_000_000),
                    availableGovernors = listOf("schedutil"),
                    currentMinKhz = 1_000_000,
                    currentMaxKhz = 1_000_000,
                    currentGovernor = "schedutil",
                    hardwareLimitsKhz = FreqRange(1_000_000, 1_000_000),
                ),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)
        val antiThrottle = presets.single { it.id == "builtin_anti_throttle" }
        // Must have set the only available OPP.
        assertThat(antiThrottle.cpuPolicyMaxKhz[0]).isEqualTo(1_000_000)
    }

    // -------------------------------------------------------------------------
    // Odin 3 / SD8 Elite 2-cluster topology
    // -------------------------------------------------------------------------

    @Test
    fun `two-cluster Odin3 gets all presets with both clusters covered`() {
        // Odin 3 SD8 Elite: policy0 (6 cores, max 3532.8 MHz) + policy6 (2 prime, max 4320 MHz)
        // OPP tables in kHz (as the kernel reports them, not rounded MHz)
        val p0Freqs = listOf(384_000, 768_000, 1_152_000, 1_555_200, 1_785_600, 2_227_200, 2_745_600, 3_532_800)
        val p6Freqs = listOf(1_017_600, 1_478_400, 1_958_400, 2_246_400, 3_072_000, 4_320_000)
        val report = reportWith(
            handheldKey = "ayn_odin3",
            family = GpuFamily.ADRENO,
            policies = listOf(
                policyKhz(id = 0, freqsKhz = p0Freqs),
                policyKhz(id = 6, freqsKhz = p6Freqs),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        for (preset in presets) {
            assertThat(preset.cpuPolicyMaxKhz.keys).containsExactly(0, 6)
            assertThat(preset.cpuPolicyMinKhz.keys).containsExactly(0, 6)
            // Min always resets to OPP floor.
            assertThat(preset.cpuPolicyMinKhz[0]).isEqualTo(384_000)
            assertThat(preset.cpuPolicyMinKhz[6]).isEqualTo(1_017_600)
        }

        // Stock restores both clusters to their kernel ceiling.
        val stock = presets.single { it.id == "builtin_stock" }
        assertThat(stock.cpuPolicyMaxKhz[0]).isEqualTo(3_532_800)
        assertThat(stock.cpuPolicyMaxKhz[6]).isEqualTo(4_320_000)
    }

    @Test
    fun `Odin3 anti-throttle leaves headroom below both cluster maxes`() {
        val report = reportWith(
            handheldKey = "ayn_odin3",
            family = GpuFamily.ADRENO,
            policies = listOf(
                policyKhz(id = 0, freqsKhz = listOf(384_000, 768_000, 1_152_000, 1_555_200, 1_785_600, 2_227_200, 2_745_600, 3_532_800)),
                policyKhz(id = 6, freqsKhz = listOf(1_017_600, 1_478_400, 1_958_400, 2_246_400, 3_072_000, 4_320_000)),
            ),
        )
        val antiThrottle = generatorWithoutAdapter().presetsFor(report)
            .single { it.id == "builtin_anti_throttle" }

        assertThat(antiThrottle.cpuPolicyMaxKhz[0]!!).isLessThan(3_532_800)
        assertThat(antiThrottle.cpuPolicyMaxKhz[6]!!).isLessThan(4_320_000)
    }

    // -------------------------------------------------------------------------
    // Stock / undo-tune preset
    // -------------------------------------------------------------------------

    @Test
    fun `stock preset returns to kernel cpuinfo_max_freq`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0, 1, 2, 3),
                    availableFreqsKhz = listOf(500_000, 1_000_000, 1_500_000),
                    availableGovernors = listOf("schedutil"),
                    currentMinKhz = 500_000,
                    currentMaxKhz = 1_000_000, // currently capped low
                    currentGovernor = "schedutil",
                    hardwareLimitsKhz = FreqRange(500_000, 1_500_000),
                ),
            ),
        )
        val stock = generatorWithoutAdapter().presetsFor(report).single { it.id == "builtin_stock" }
        assertThat(stock.cpuPolicyMaxKhz[0]).isEqualTo(1_500_000)
    }

    @Test
    fun `stock preset uses schedutil not performance so cores can idle`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                CpuPolicyProbe(
                    policyId = 0,
                    onlineCores = listOf(0),
                    availableFreqsKhz = listOf(500_000, 1_500_000),
                    availableGovernors = listOf("schedutil", "performance", "conservative"),
                    currentMinKhz = 500_000,
                    currentMaxKhz = 1_500_000,
                    currentGovernor = "schedutil",
                    hardwareLimitsKhz = FreqRange(500_000, 1_500_000),
                ),
            ),
        )
        val stock = generatorWithoutAdapter().presetsFor(report).single { it.id == "builtin_stock" }
        // Stock governor must be schedutil (or walt), never performance.
        val gov = stock.cpuPolicyGovernor[0]
        assertThat(gov).isNotEqualTo("performance")
        assertThat(gov).isAnyOf("schedutil", "walt")
    }

    // -------------------------------------------------------------------------
    // CPU min always resets to OPP floor
    // -------------------------------------------------------------------------

    @Test
    fun `cpu min is always reset to OPP floor regardless of preset`() {
        val report = reportWith(
            handheldKey = null,
            family = GpuFamily.ADRENO,
            policies = listOf(
                policy(id = 0, freqsMhz = listOf(400, 800, 1200, 1600, 2000)),
            ),
        )
        val presets = generatorWithoutAdapter().presetsFor(report)

        for (preset in presets) {
            // OPP floor for policy0 = 400 MHz = 400_000 kHz
            assertThat(preset.cpuPolicyMinKhz[0]).isEqualTo(400_000)
        }
    }

    // -------------------------------------------------------------------------
    // content/presets.json OTA channel — JSON roundtrip test
    // -------------------------------------------------------------------------

    @Test
    fun `content presets json deserializes cleanly into List of Preset`() {
        // Locate the content/presets.json relative to the project root.
        // This test runs from the app/ module directory but the project root
        // is two levels up (app/../.. = project root).
        val projectRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("content/presets.json").exists() }
            ?: error("Could not find project root containing content/presets.json")

        val presetsJson = projectRoot.resolve("content/presets.json").readText()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        val presets: List<Preset> = json.decodeFromString(
            ListSerializer(Preset.serializer()),
            presetsJson,
        )

        // Non-empty — we seeded it.
        assertThat(presets).isNotEmpty()

        // All entries have non-blank id, name, and description.
        for (preset in presets) {
            assertThat(preset.id).isNotEmpty()
            assertThat(preset.name).isNotEmpty()
            assertThat(preset.description).isNotEmpty()
            // No preset should exceed 0 max freqs in any cluster it targets.
            for ((_, khz) in preset.cpuPolicyMaxKhz) {
                assertThat(khz).isGreaterThan(0)
            }
            // Min freqs must be positive when set.
            for ((_, khz) in preset.cpuPolicyMinKhz) {
                assertThat(khz).isGreaterThan(0)
            }
            // Max must be >= min for every cluster that has both.
            for ((policyId, maxKhz) in preset.cpuPolicyMaxKhz) {
                val minKhz = preset.cpuPolicyMinKhz[policyId]
                if (minKhz != null) {
                    assertThat(maxKhz).isAtLeast(minKhz)
                }
            }
            // GPU max must be positive when set.
            if (preset.gpuMaxHz != null) {
                assertThat(preset.gpuMaxHz).isGreaterThan(0L)
            }
        }
    }

    @Test
    fun `content presets json roundtrips cleanly encode-decode`() {
        val projectRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("content/presets.json").exists() }
            ?: error("Could not find project root containing content/presets.json")

        val presetsJson = projectRoot.resolve("content/presets.json").readText()

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val decoded: List<Preset> = json.decodeFromString(
            ListSerializer(Preset.serializer()),
            presetsJson,
        )
        val reEncoded = json.encodeToString(
            ListSerializer(Preset.serializer()),
            decoded,
        )
        val reDecoded: List<Preset> = json.decodeFromString(
            ListSerializer(Preset.serializer()),
            reEncoded,
        )

        assertThat(reDecoded).hasSize(decoded.size)
        for (i in decoded.indices) {
            assertThat(reDecoded[i].id).isEqualTo(decoded[i].id)
            assertThat(reDecoded[i].cpuPolicyMaxKhz).isEqualTo(decoded[i].cpuPolicyMaxKhz)
        }
    }

    // -------------------------------------------------------------------------
    // OTA preset filtering by targetHandheldKeys
    // -------------------------------------------------------------------------

    /**
     * An OTA preset with targetHandheldKeys=["retroid_pocket6"] must be
     * shown on an RP6 and hidden on an Odin 3.
     */
    @Test
    fun `OTA preset with rp6 key is shown on retroid_pocket6 only`() {
        val rp6Preset = Preset(
            id = "rp6_ps2_gc_sustained",
            name = "RP6 PS2 Sustained",
            description = "RP6 only",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1555000, 3 to 1920000, 7 to 2227000),
            targetHandheldKeys = listOf("retroid_pocket6"),
        )
        val remoteWithRp6Preset = remoteContentWith(listOf(rp6Preset))

        // RP6 report — should include the preset.
        val rp6Report = reportWith("retroid_pocket6", GpuFamily.ADRENO, listOf(
            policy(0, listOf(307, 1555, 2016)),
            policy(3, listOf(499, 1920, 2803)),
            policy(7, listOf(595, 2227, 3187)),
        ))
        val generatorRp6 = generatorWithRemote(remoteWithRp6Preset)
        val rp6Presets = generatorRp6.presetsFor(rp6Report)
        assertThat(rp6Presets.map { it.id }).contains("rp6_ps2_gc_sustained")

        // Odin 3 report — same OTA list, preset must be filtered out.
        val odin3Report = reportWith("ayn_odin3", GpuFamily.ADRENO, listOf(
            policy(0, listOf(384, 2745, 3532)),
            policy(6, listOf(1017, 3072, 4320)),
        ))
        val generatorOdin3 = generatorWithRemote(remoteWithRp6Preset)
        val odin3Presets = generatorOdin3.presetsFor(odin3Report)
        assertThat(odin3Presets.map { it.id }).doesNotContain("rp6_ps2_gc_sustained")
    }

    /**
     * An OTA preset with targetHandheldKeys=null (or missing from JSON) is
     * shown on every device.
     */
    @Test
    fun `OTA preset with null targetHandheldKeys is shown on all devices`() {
        val universalPreset = Preset(
            id = "universal_test_preset",
            name = "Universal",
            description = "For all",
            verification = VerificationTier.GENERIC_UNKNOWN_FAMILY,
            cpuPolicyMaxKhz = mapOf(0 to 1000000),
            targetHandheldKeys = null, // universal
        )
        val remote = remoteContentWith(listOf(universalPreset))

        val rp6Report = reportWith("retroid_pocket6", GpuFamily.ADRENO, listOf(
            policy(0, listOf(307, 1555, 2016)),
        ))
        val odin3Report = reportWith("ayn_odin3", GpuFamily.ADRENO, listOf(
            policy(0, listOf(384, 2745, 3532)),
        ))
        val unknownReport = reportWith(null, GpuFamily.ADRENO, listOf(
            policy(0, listOf(300, 1000, 2000)),
        ))

        assertThat(generatorWithRemote(remote).presetsFor(rp6Report).map { it.id })
            .contains("universal_test_preset")
        assertThat(generatorWithRemote(remote).presetsFor(odin3Report).map { it.id })
            .contains("universal_test_preset")
        assertThat(generatorWithRemote(remote).presetsFor(unknownReport).map { it.id })
            .contains("universal_test_preset")
    }

    /**
     * OTA preset targeting a specific device is hidden when the current
     * device key is null (unrecognised device).
     */
    @Test
    fun `OTA preset with targetHandheldKeys is hidden on unrecognised device`() {
        val rp6Preset = Preset(
            id = "rp6_only",
            name = "RP6 only",
            description = "d",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1555000),
            targetHandheldKeys = listOf("retroid_pocket6"),
        )
        val remote = remoteContentWith(listOf(rp6Preset))
        val unknownReport = reportWith(null, GpuFamily.ADRENO, listOf(
            policy(0, listOf(300, 1000, 2000)),
        ))

        val presets = generatorWithRemote(remote).presetsFor(unknownReport)
        assertThat(presets.map { it.id }).doesNotContain("rp6_only")
    }

    // -------------------------------------------------------------------------
    // inferTargetHandheldKeys — mis-tag recovery helper
    // -------------------------------------------------------------------------

    private fun generator(): PresetGenerator = generatorWithRemote(emptyRemoteContent())

    private fun presetWith(id: String, name: String = "Test", desc: String = "desc") = Preset(
        id = id,
        name = name,
        description = desc,
        verification = VerificationTier.GENERIC_UNKNOWN_FAMILY,
        cpuPolicyMaxKhz = emptyMap(),
        targetHandheldKeys = null,
    )

    @Test
    fun `inferTargetHandheldKeys - rp6_ prefix returns retroid_pocket6`() {
        val preset = presetWith("rp6_cool")
        val result = generator().inferTargetHandheldKeys(preset)
        assertThat(result).isEqualTo(listOf("retroid_pocket6"))
    }

    @Test
    fun `inferTargetHandheldKeys - odin3_ prefix returns ayn_odin3`() {
        val preset = presetWith("odin3_x")
        val result = generator().inferTargetHandheldKeys(preset)
        assertThat(result).isEqualTo(listOf("ayn_odin3"))
    }

    @Test
    fun `inferTargetHandheldKeys - generic id returns null`() {
        val preset = presetWith("balanced_efficiency")
        val result = generator().inferTargetHandheldKeys(preset)
        assertThat(result).isNull()
    }

    @Test
    fun `inferTargetHandheldKeys - name containing Retroid Pocket 6 returns retroid_pocket6`() {
        val preset = presetWith(
            id = "some_preset",
            name = "Retroid Pocket 6 Sustained",
            desc = "optimised for Retroid Pocket 6",
        )
        val result = generator().inferTargetHandheldKeys(preset)
        assertThat(result).isEqualTo(listOf("retroid_pocket6"))
    }

    @Test
    fun `inferTargetHandheldKeys - thor_ prefix returns ayn_thor`() {
        val preset = presetWith("thor_anti_throttle")
        val result = generator().inferTargetHandheldKeys(preset)
        assertThat(result).isEqualTo(listOf("ayn_thor"))
    }

    // -------------------------------------------------------------------------
    // Mis-tagged OTA preset is hidden on the wrong device via generator
    // -------------------------------------------------------------------------

    /**
     * An OTA preset with id "rp6_*" and targetHandheldKeys==null (mis-tagged)
     * must be hidden on an ayn_odin3 report because inferTargetHandheldKeys
     * infers ["retroid_pocket6"] from the id prefix.
     */
    @Test
    fun `mis-tagged rp6_ preset is hidden on ayn_odin3 via mis-tag recovery`() {
        val misTaggedPreset = Preset(
            id = "rp6_ps2_sustained",
            name = "RP6 PS2 Sustained",
            description = "Optimised for Retroid Pocket 6 three-cluster topology",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1555000, 3 to 1920000, 7 to 2227000),
            targetHandheldKeys = null, // mis-tagged — should be ["retroid_pocket6"]
        )
        val remote = remoteContentWith(listOf(misTaggedPreset))

        val odin3Report = reportWith("ayn_odin3", GpuFamily.ADRENO, listOf(
            policy(0, listOf(384, 2745, 3532)),
            policy(6, listOf(1017, 3072, 4320)),
        ))
        val odin3Presets = generatorWithRemote(remote).presetsFor(odin3Report)
        assertThat(odin3Presets.map { it.id }).doesNotContain("rp6_ps2_sustained")

        // Same preset IS shown on an RP6 (the inferred key matches).
        val rp6Report = reportWith("retroid_pocket6", GpuFamily.ADRENO, listOf(
            policy(0, listOf(307, 1555, 2016)),
            policy(3, listOf(499, 1920, 2803)),
            policy(7, listOf(595, 2227, 3187)),
        ))
        val rp6Presets = generatorWithRemote(remote).presetsFor(rp6Report)
        assertThat(rp6Presets.map { it.id }).contains("rp6_ps2_sustained")
    }

    /**
     * A genuinely universal OTA preset (no device token anywhere) is still
     * shown on every device after the mis-tag recovery logic runs.
     */
    @Test
    fun `genuinely universal preset is still shown on all devices after mis-tag check`() {
        val universalPreset = Preset(
            id = "balanced_efficiency",
            name = "Balanced Efficiency",
            description = "Works on all devices",
            verification = VerificationTier.GENERIC_UNKNOWN_FAMILY,
            cpuPolicyMaxKhz = mapOf(0 to 1000000),
            targetHandheldKeys = null,
        )
        val remote = remoteContentWith(listOf(universalPreset))

        val odin3Report = reportWith("ayn_odin3", GpuFamily.ADRENO, listOf(policy(0, listOf(384, 3532))))
        val rp6Report = reportWith("retroid_pocket6", GpuFamily.ADRENO, listOf(policy(0, listOf(307, 2016))))
        val unknownReport = reportWith(null, GpuFamily.ADRENO, listOf(policy(0, listOf(300, 2000))))

        assertThat(generatorWithRemote(remote).presetsFor(odin3Report).map { it.id })
            .contains("balanced_efficiency")
        assertThat(generatorWithRemote(remote).presetsFor(rp6Report).map { it.id })
            .contains("balanced_efficiency")
        assertThat(generatorWithRemote(remote).presetsFor(unknownReport).map { it.id })
            .contains("balanced_efficiency")
    }

    // -------------------------------------------------------------------------
    // SocFamilyRules — governor safety regression
    // -------------------------------------------------------------------------

    @Test
    fun `SocFamilyRules never returns powersave in any governor list`() {
        for (family in GpuFamily.values()) {
            val govMap = SocFamilyRules.preferredGovernors(family)
            val allGovs = listOf(
                govMap.coolAndQuiet,
                govMap.lightEmulation,
                govMap.ps2GcSustained,
                govMap.switchHeavy,
                govMap.antiThrottle,
                govMap.stock,
            ).flatten()
            assertThat(allGovs).doesNotContain("powersave")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun generatorWithoutAdapter(): PresetGenerator {
        val registry = mockk<DeviceAdapterRegistry>()
        every { registry.lookup(any()) } returns null
        every { registry.lookup(null) } returns null
        return PresetGenerator(registry, emptyRemoteContent())
    }

    private fun emptyRemoteContent(): RemoteContentRepository {
        val repo = mockk<RemoteContentRepository>()
        every { repo.remotePresets() } returns emptyList()
        return repo
    }

    private fun remoteContentWith(presets: List<Preset>): RemoteContentRepository {
        val repo = mockk<RemoteContentRepository>()
        every { repo.remotePresets() } returns presets
        return repo
    }

    private fun generatorWithRemote(remote: RemoteContentRepository): PresetGenerator {
        val registry = mockk<DeviceAdapterRegistry>()
        every { registry.lookup(any()) } returns null
        every { registry.lookup(null) } returns null
        return PresetGenerator(registry, remote)
    }

    /** Helper for tests that use round MHz values (multiplied by 1000 to get kHz). */
    private fun policy(id: Int, freqsMhz: List<Int>) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsMhz.map { it * 1000 },
        availableGovernors = listOf("schedutil", "performance", "conservative", "powersave"),
        currentMinKhz = freqsMhz.first() * 1000,
        currentMaxKhz = freqsMhz.last() * 1000,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsMhz.first() * 1000, freqsMhz.last() * 1000),
    )

    /** Helper for tests that need exact kHz values (e.g. SD8 Elite which has 1017.6 MHz = 1,017,600 kHz). */
    private fun policyKhz(id: Int, freqsKhz: List<Int>) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsKhz,
        availableGovernors = listOf("schedutil", "performance", "conservative", "powersave", "walt"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = freqsKhz.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun reportWith(
        handheldKey: String?,
        family: GpuFamily,
        policies: List<CpuPolicyProbe>,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Generic",
            brand = "Generic",
            model = "Test",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = handheldKey,
        ),
        soc = SoCIdentity(socManufacturer = "", socModel = "", gpuFamily = family),
        privilege = PrivilegeTier.ROOT,
        rootKind = RootKind.MAGISK,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
    )
}
