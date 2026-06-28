package io.github.mayusi.calibratesoc.data.autotdp.adaptive

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.GoalParams
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import org.junit.Test

/**
 * UNIT 1 (ADAPTIVE MODE) — proof tests for the intent model + the pure policy.
 *
 * These assert the §3.3 design numbers for every preset, the normalization contract,
 * and the three-way beyond-stock gate. They are PURE JVM tests — no Robolectric, no
 * Android — exactly as [AdaptivePolicy] is a pure function of the weights.
 *
 * The expected setpoint numbers are computed by hand from the design coefficients and
 * pinned here; if a coefficient in [AdaptivePolicy] drifts, these break loudly.
 */
class AdaptivePolicyTest {

    // ─── Device fixture (SD8Gen2 / RP6 topology, mirrors AutoTdpGoalWiringTest) ──────
    // Caps are not consulted for the weight-driven outputs; any valid envelope works.
    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(500_000, 1_000_000, 1_500_000, 2_000_000),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = 160_000_000L,
        gpuDevfreqCeilHz = 1_100_000_000L,
        gpuDevfreqStepsHz = listOf(160_000_000L, 550_000_000L, 1_100_000_000L),
        gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    private fun resolve(
        preset: AdaptivePreset,
        optIn: Boolean = false,
        probe: Boolean = false,
    ): AdaptiveSetpoints =
        AdaptivePolicy.resolve(preset.intent, caps, optIn, probe)

    // ════════════════════════════════════════════════════════════════════════════
    //  Per-preset resolved setpoints (§3.3 numbers)
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun balanced_resolvesToTheCanonicalDefaultSetpoints() {
        val sp = resolve(AdaptivePreset.BALANCED)

        assertThat(sp.cpuGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(sp.cpuGoalParams.tempCeilingC).isEqualTo(90)
        assertThat(sp.gpuBand.low).isEqualTo(45)
        assertThat(sp.gpuBand.high).isEqualTo(65)
        assertThat(sp.gpuFloorFraction).isWithin(0.001f).of(0.2875f)
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.OFF)
        assertThat(sp.gpuSoftTempC).isEqualTo(88)
        assertThat(sp.ddrBias).isEqualTo(DdrBias.NORMAL)
    }

    @Test
    fun maxPerformance_pushesEverythingToTheFront() {
        val sp = resolve(AdaptivePreset.MAX_PERFORMANCE)

        assertThat(sp.cpuGoal).isEqualTo(GoalProfile.MAX_FPS)
        assertThat(sp.cpuGoalParams.tempCeilingC).isEqualTo(91)
        // center 73, width 19 → 64..82
        assertThat(sp.gpuBand.low).isEqualTo(64)
        assertThat(sp.gpuBand.high).isEqualTo(82)
        assertThat(sp.gpuFloorFraction).isWithin(0.001f).of(0.48f)
        // perf 0.60 ≥ 0.55 but no opt-in/probe → WITHIN_VENDOR (not BEYOND_STOCK).
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.WITHIN_VENDOR)
        assertThat(sp.gpuSoftTempC).isEqualTo(89)
        assertThat(sp.ddrBias).isEqualTo(DdrBias.HIGH)
    }

    @Test
    fun maxPerformance_withOptInAndProbe_unlocksBeyondStock() {
        val sp = resolve(AdaptivePreset.MAX_PERFORMANCE, optIn = true, probe = true)
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.BEYOND_STOCK)
    }

    @Test
    fun performance_isFastButStabilityShiftsTheGoalOneNotch() {
        val sp = resolve(AdaptivePreset.PERFORMANCE)

        // perfMinusBatt 0.35 → MAX_FPS-band tier, but wStability 0.30 nudges one notch.
        assertThat(sp.cpuGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(sp.cpuGoalParams.tempCeilingC).isEqualTo(92)
        // center 66, width 20 → 56..76
        assertThat(sp.gpuBand.low).isEqualTo(56)
        assertThat(sp.gpuBand.high).isEqualTo(76)
        assertThat(sp.gpuFloorFraction).isWithin(0.001f).of(0.3975f)
        // perf 0.45 ≥ 0.40 (not ≥ 0.55) → WITHIN_VENDOR
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.WITHIN_VENDOR)
        assertThat(sp.gpuSoftTempC).isEqualTo(89)
        assertThat(sp.ddrBias).isEqualTo(DdrBias.HIGH)
    }

    @Test
    fun efficiency_savesPowerStaysSmoothAndCool() {
        val sp = resolve(AdaptivePreset.EFFICIENCY)

        assertThat(sp.cpuGoal).isEqualTo(GoalProfile.COOL_QUIET)
        assertThat(sp.cpuGoalParams.tempCeilingC).isEqualTo(90)
        // center 45 (55 - 10.5, half-up), width 19 → 36..54
        assertThat(sp.gpuBand.low).isEqualTo(36)
        assertThat(sp.gpuBand.high).isEqualTo(54)
        assertThat(sp.gpuFloorFraction).isWithin(0.001f).of(0.205f)
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.OFF)
        assertThat(sp.gpuSoftTempC).isEqualTo(88)
        assertThat(sp.ddrBias).isEqualTo(DdrBias.LOW)
    }

    @Test
    fun maxBattery_stretchesTheChargeClocksStayLow() {
        val sp = resolve(AdaptivePreset.MAX_BATTERY)

        assertThat(sp.cpuGoal).isEqualTo(GoalProfile.BATTERY_SAVER)
        assertThat(sp.cpuGoalParams.tempCeilingC).isEqualTo(90)
        // center 37, width 18 → 28..46
        assertThat(sp.gpuBand.low).isEqualTo(28)
        assertThat(sp.gpuBand.high).isEqualTo(46)
        assertThat(sp.gpuFloorFraction).isWithin(0.001f).of(0.15f)
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.OFF)
        assertThat(sp.gpuSoftTempC).isEqualTo(88)
        assertThat(sp.ddrBias).isEqualTo(DdrBias.LOW)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Intent normalization
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun normalize_allZeroDegradesToBalanced() {
        val norm = AdaptiveIntent(0f, 0f, 0f, 0f).normalized()
        assertThat(norm).isEqualTo(AdaptivePreset.BALANCED.intent)
    }

    @Test
    fun normalize_renormalizesToSumOne() {
        // Un-normalized vector (sums to 8). Ratios must be preserved, sum → 1.
        val norm = AdaptiveIntent(4f, 2f, 1f, 1f).normalized()
        assertThat(norm.rawSum).isWithin(1e-5f).of(1f)
        assertThat(norm.wPerformance).isWithin(1e-5f).of(0.5f)
        assertThat(norm.wBattery).isWithin(1e-5f).of(0.25f)
        assertThat(norm.wStability).isWithin(1e-5f).of(0.125f)
        assertThat(norm.wThermalHeadroom).isWithin(1e-5f).of(0.125f)
    }

    @Test
    fun normalize_floorsNegativeWeightsToZero() {
        val norm = AdaptiveIntent(-1f, 1f, 0f, 0f).normalized()
        // Negative perf floored to 0 → all weight on battery.
        assertThat(norm.wPerformance).isEqualTo(0f)
        assertThat(norm.wBattery).isWithin(1e-5f).of(1f)
        assertThat(norm.rawSum).isWithin(1e-5f).of(1f)
    }

    @Test
    fun resolve_normalizesRawIntentBeforeMapping() {
        // A raw (×4) BALANCED vector must resolve identically to the normalized one.
        val raw = AdaptiveIntent(1f, 1f, 1f, 1f) // sums to 4
        val fromRaw = AdaptivePolicy.resolve(raw, caps, false, false)
        val fromPreset = resolve(AdaptivePreset.BALANCED)
        assertThat(fromRaw).isEqualTo(fromPreset)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Beyond-stock GPU-OC is gated by ALL THREE conditions
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun beyondStock_requiresPerfAndOptInAndProbe() {
        // High-perf intent (perf 0.60 ≥ 0.55) so only opt-in + probe gate the tier.
        val hot = AdaptivePreset.MAX_PERFORMANCE.intent

        // All three present → BEYOND_STOCK.
        assertThat(AdaptivePolicy.resolve(hot, caps, true, true).gpuOcTier)
            .isEqualTo(GpuOcTier.BEYOND_STOCK)
        // Missing probe → falls back to WITHIN_VENDOR.
        assertThat(AdaptivePolicy.resolve(hot, caps, true, false).gpuOcTier)
            .isEqualTo(GpuOcTier.WITHIN_VENDOR)
        // Missing opt-in → falls back to WITHIN_VENDOR.
        assertThat(AdaptivePolicy.resolve(hot, caps, false, true).gpuOcTier)
            .isEqualTo(GpuOcTier.WITHIN_VENDOR)
        // Neither → WITHIN_VENDOR.
        assertThat(AdaptivePolicy.resolve(hot, caps, false, false).gpuOcTier)
            .isEqualTo(GpuOcTier.WITHIN_VENDOR)
    }

    @Test
    fun beyondStock_notUnlockedWhenPerfTooLow_evenWithOptInAndProbe() {
        // PERFORMANCE perf is 0.45 (< 0.55) → can never reach BEYOND_STOCK.
        val sp = AdaptivePolicy.resolve(AdaptivePreset.PERFORMANCE.intent, caps, true, true)
        assertThat(sp.gpuOcTier).isEqualTo(GpuOcTier.WITHIN_VENDOR)
        // And a low-perf intent stays OFF regardless of opt-in/probe.
        val cool = AdaptivePolicy.resolve(AdaptivePreset.EFFICIENCY.intent, caps, true, true)
        assertThat(cool.gpuOcTier).isEqualTo(GpuOcTier.OFF)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Invariants that hold across the whole spectrum
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun tempCeiling_neverExceedsSafetyAndStaysInBand() {
        for (preset in AdaptivePreset.entries) {
            val sp = resolve(preset)
            assertThat(sp.cpuGoalParams.tempCeilingC).isIn(GoalParams.TEMP_CEILING_MIN_C..GoalParams.TEMP_CEILING_MAX_C)
        }
    }

    @Test
    fun gpuBand_edgesAreOrderedAndClamped() {
        for (preset in AdaptivePreset.entries) {
            val band = resolve(preset).gpuBand
            assertThat(band.low).isLessThan(band.high)
            assertThat(band.low).isAtLeast(20)
            assertThat(band.high).isAtMost(95)
        }
    }

    @Test
    fun gpuFloorFraction_alwaysInBounds() {
        for (preset in AdaptivePreset.entries) {
            val frac = resolve(preset).gpuFloorFraction
            assertThat(frac).isAtLeast(0.15f)
            assertThat(frac).isAtMost(0.90f)
        }
    }

    @Test
    fun presetIntents_areAlreadyNormalized() {
        for (preset in AdaptivePreset.entries) {
            assertThat(preset.intent.rawSum).isWithin(1e-5f).of(1f)
        }
    }
}
