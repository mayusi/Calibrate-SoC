package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Unit tests for [ThermalKillEvaluator].
 *
 * Covers:
 *  - No kill below threshold (single or multiple samples)
 *  - No kill on first over-threshold sample (debounce grace)
 *  - Kill declared on second consecutive over-threshold sample
 *  - Counter resets when a below-threshold sample intervenes (spike tolerance)
 *  - No zones → never kills (can't confirm danger without data)
 *  - Kill reason string format
 *  - Threshold constant is above normal sustained-gaming temps (95°C+)
 *  - Default threshold and consecutive-count values
 *  - Startup grace window (FIX 3): first [GRACE_SAMPLES] samples are never killed
 *  - Kill still fires correctly AFTER the grace window on sustained heat
 *  - Grace window is reset by [ThermalKillEvaluator.reset]
 */
class ThermalKillEvaluatorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun zone(label: String, tempC: Float) =
        ZoneTemp(zoneId = 0, label = label, tempMilliC = (tempC * 1000).toInt())

    private fun sample(vararg zones: ZoneTemp) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = emptyList(),
        perCoreLoadPct = emptyList(),
        gpuLoadPct = null,
        gpuFreqHz = null,
        zoneTempsMilliC = zones.toList(),
        ramTotalKb = 0L,
        ramAvailableKb = 0L,
        batteryTempDeciC = null,
        batteryCurrentUa = null,
        batteryVoltageUv = null,
        fanRpm = null,
    )

    private fun hotSample(tempC: Float = 110f) = sample(zone("cpu-1-1-1", tempC))
    private fun coolSample(tempC: Float = 85f)  = sample(zone("cpu-1-1-1", tempC))
    private fun emptySample() = sample()

    // ── Default constants ─────────────────────────────────────────────────────

    @Test
    fun `default kill threshold is 105 degrees C`() {
        assertThat(ThermalKillEvaluator.KILL_THRESHOLD_MILLI_C).isEqualTo(105_000)
    }

    @Test
    fun `default threshold is above normal sustained gaming range (95 C)`() {
        // 95°C is a normal sustained-load temp on SD8Gen3 under gaming. The kill
        // must not fire during that range — it should only fire above it.
        assertThat(ThermalKillEvaluator.KILL_THRESHOLD_MILLI_C).isGreaterThan(95_000)
    }

    @Test
    fun `default required consecutive count is 2`() {
        assertThat(ThermalKillEvaluator.REQUIRED_CONSECUTIVE).isEqualTo(2)
    }

    // ── No kill below threshold ────────────────────────────────────────────────

    @Test
    fun `no kill on sample below threshold`() {
        val evaluator = ThermalKillEvaluator()
        // 94°C — below 105°C kill threshold; typical sustained gaming temp
        assertThat(evaluator.evaluate(coolSample(94f))).isNull()
    }

    @Test
    fun `no kill on repeated samples below threshold`() {
        val evaluator = ThermalKillEvaluator()
        repeat(10) {
            assertThat(evaluator.evaluate(coolSample(90f))).isNull()
        }
    }

    @Test
    fun `no kill at exactly one milli-C below threshold`() {
        val evaluator = ThermalKillEvaluator()
        val justBelow = sample(ZoneTemp(0, "cpu-1-1-1", 104_999))
        assertThat(evaluator.evaluate(justBelow)).isNull()
    }

    // ── Debounce: first over-threshold sample does NOT kill ───────────────────

    @Test
    fun `first over-threshold sample returns null (debounce grace)`() {
        val evaluator = ThermalKillEvaluator()
        // 110°C is above the 105°C threshold but it is the FIRST sample.
        // The daemon must NOT abort on a single transient reading.
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
    }

    @Test
    fun `first over-threshold sample at exactly threshold returns null`() {
        val evaluator = ThermalKillEvaluator()
        val atThreshold = sample(ZoneTemp(0, "cpu-big", 105_000))
        assertThat(evaluator.evaluate(atThreshold)).isNull()
    }

    // ── Kill on second consecutive over-threshold sample ──────────────────────

    @Test
    fun `kill declared on second consecutive over-threshold sample`() {
        // graceSamples=0 so this test exercises the debounce logic without
        // having to also burn through the startup grace window.
        val evaluator = ThermalKillEvaluator(graceSamples = 0)
        // Sample 1: over threshold → debounce, no kill
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        // Sample 2: still over threshold → kill declared
        val result = evaluator.evaluate(hotSample(110f))
        assertThat(result).isNotNull()
        assertThat(result).contains("Thermal kill")
    }

    @Test
    fun `kill reason contains zone label and temperature`() {
        // graceSamples=0 — focused on kill-reason format, not grace window.
        val evaluator = ThermalKillEvaluator(graceSamples = 0)
        evaluator.evaluate(sample(zone("cpu-1-1-1", 107f)))
        val reason = evaluator.evaluate(sample(zone("cpu-1-1-1", 108f)))
        assertThat(reason).isNotNull()
        assertThat(reason).contains("cpu-1-1-1")
        // Reason should mention temperature in °C (integer division of milli-°C).
        assertThat(reason).containsMatch("10[0-9]°C")
    }

    @Test
    fun `kill reason mentions consecutive count`() {
        // graceSamples=0 — focused on kill-reason format, not grace window.
        val evaluator = ThermalKillEvaluator(graceSamples = 0)
        evaluator.evaluate(hotSample(110f))
        val reason = evaluator.evaluate(hotSample(110f))
        assertThat(reason).isNotNull()
        assertThat(reason).contains("2 consecutive")
    }

    // ── Counter reset on below-threshold sample ────────────────────────────────

    @Test
    fun `below-threshold sample after over-threshold resets counter`() {
        val evaluator = ThermalKillEvaluator()
        // Sample 1: over threshold → counter = 1
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        // Sample 2: below threshold → counter reset to 0
        assertThat(evaluator.evaluate(coolSample(90f))).isNull()
        // Sample 3: over threshold again → counter = 1 again (NOT 2), no kill
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
    }

    @Test
    fun `alternating hot and cool samples never kill (spike pattern)`() {
        val evaluator = ThermalKillEvaluator()
        // Pattern: spike, cool, spike, cool... — one-sample transients must not kill.
        repeat(20) {
            assertThat(evaluator.evaluate(hotSample(110f))).isNull()  // over threshold
            assertThat(evaluator.evaluate(coolSample(88f))).isNull()  // resets counter
        }
    }

    @Test
    fun `kill fires after 3 consecutive samples when required=3 and grace=0`() {
        // graceSamples=0 to isolate the requiredConsecutive=3 debounce logic.
        val evaluator = ThermalKillEvaluator(requiredConsecutive = 3, graceSamples = 0)
        assertThat(evaluator.evaluate(hotSample())).isNull()   // 1st: debounce (1/3)
        assertThat(evaluator.evaluate(hotSample())).isNull()   // 2nd: debounce (2/3)
        assertThat(evaluator.evaluate(hotSample())).isNotNull() // 3rd: kill!
    }

    // ── No zones → never kills ─────────────────────────────────────────────────

    @Test
    fun `no zones in sample returns null (cannot confirm danger)`() {
        val evaluator = ThermalKillEvaluator()
        // Without any zone data we cannot know if it's safe or dangerous.
        // Prefer staying alive over killing on absent data.
        repeat(5) {
            assertThat(evaluator.evaluate(emptySample())).isNull()
        }
    }

    // ── Hottest zone wins ─────────────────────────────────────────────────────

    @Test
    fun `hottest zone determines kill, not zone name`() {
        // graceSamples=0 so we can test the zone-selection logic without
        // needing to feed GRACE_SAMPLES + 2 samples in this focused test.
        val evaluator = ThermalKillEvaluator(graceSamples = 0)
        // Three zones; only one is over threshold
        val multiZoneSample = sample(
            zone("skin", 42f),
            zone("battery", 45f),
            zone("cpu-1-1-1", 107f),  // this one is the hottest
        )
        // First sample post-grace: debounce — counter = 1, no kill yet
        assertThat(evaluator.evaluate(multiZoneSample)).isNull()
        // Second consecutive sample: kill declared on cpu-1-1-1
        val result = evaluator.evaluate(multiZoneSample)
        assertThat(result).isNotNull()
        assertThat(result).contains("cpu-1-1-1")
    }

    // ── reset() clears the counter ────────────────────────────────────────────

    @Test
    fun `reset clears accumulated counter`() {
        val evaluator = ThermalKillEvaluator()
        // Accumulate 1 hot sample
        evaluator.evaluate(hotSample())
        // Reset (simulates daemon restart)
        evaluator.reset()
        // The next hot sample is now the FIRST again → no kill
        assertThat(evaluator.evaluate(hotSample())).isNull()
    }

    // ── Custom threshold ──────────────────────────────────────────────────────

    @Test
    fun `custom lower threshold fires sooner`() {
        // 95°C threshold — for testing only, not recommended for production.
        // Use graceSamples=0 so the custom-threshold test isn't masked by the grace.
        val evaluator = ThermalKillEvaluator(killThresholdMilliC = 95_000, requiredConsecutive = 2, graceSamples = 0)
        assertThat(evaluator.evaluate(sample(zone("cpu", 96f)))).isNull()  // grace (debounce, not startup)
        assertThat(evaluator.evaluate(sample(zone("cpu", 96f)))).isNotNull()  // kill
    }

    // ── Startup grace window (FIX 3) ──────────────────────────────────────────

    @Test
    fun `grace window default constant is 3`() {
        assertThat(ThermalKillEvaluator.GRACE_SAMPLES).isEqualTo(3)
    }

    @Test
    fun `no kill during grace window even on sustained over-threshold samples`() {
        // With default grace=3, first 3 hot samples must NEVER kill.
        val evaluator = ThermalKillEvaluator()
        // All three are hot — but all fall within the grace window.
        repeat(ThermalKillEvaluator.GRACE_SAMPLES) {
            assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        }
    }

    @Test
    fun `kill fires correctly after grace window on sustained heat`() {
        // Samples 1-3: grace (no kill). Samples 4 and 5: kill should fire on
        // the 2nd consecutive over-threshold sample AFTER the grace.
        val evaluator = ThermalKillEvaluator()
        // Burn through the grace window.
        repeat(ThermalKillEvaluator.GRACE_SAMPLES) {
            assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        }
        // First over-threshold post-grace: debounce counter = 1, no kill yet.
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        // Second consecutive post-grace: kill declared.
        val result = evaluator.evaluate(hotSample(110f))
        assertThat(result).isNotNull()
        assertThat(result).contains("Thermal kill")
    }

    @Test
    fun `cool sample after grace window resets consecutive counter`() {
        val evaluator = ThermalKillEvaluator()
        // Burn grace.
        repeat(ThermalKillEvaluator.GRACE_SAMPLES) { evaluator.evaluate(hotSample(110f)) }
        // One hot post-grace (counter = 1), then cool (counter reset = 0).
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        assertThat(evaluator.evaluate(coolSample(80f))).isNull()
        // Now another two hot samples: kill should fire on the 2nd.
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        assertThat(evaluator.evaluate(hotSample(110f))).isNotNull()
    }

    @Test
    fun `grace window can be customised to zero for tests that need immediate evaluation`() {
        // graceSamples=0 means the first sample is already past the grace.
        val evaluator = ThermalKillEvaluator(graceSamples = 0)
        // requiredConsecutive=2 (default): first hot → debounce; second hot → kill.
        assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        assertThat(evaluator.evaluate(hotSample(110f))).isNotNull()
    }

    @Test
    fun `reset clears both consecutive counter and sample count (restarts grace)`() {
        val evaluator = ThermalKillEvaluator()
        // Burn through the grace window AND start accumulating consecutive hits.
        repeat(ThermalKillEvaluator.GRACE_SAMPLES) { evaluator.evaluate(hotSample(110f)) }
        evaluator.evaluate(hotSample(110f)) // post-grace: consecutive = 1
        // Reset → grace restarts from zero.
        evaluator.reset()
        // The next [GRACE_SAMPLES] samples must be ignored again.
        repeat(ThermalKillEvaluator.GRACE_SAMPLES) {
            assertThat(evaluator.evaluate(hotSample(110f))).isNull()
        }
    }
}
