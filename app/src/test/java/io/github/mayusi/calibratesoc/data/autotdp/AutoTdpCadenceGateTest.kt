package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * UNIT 2 — the AutoTdpService ADAPTIVE-CADENCE DECIMATION GATE, verified at the
 * predicate level.
 *
 * The daemon's collect loop is an Android Service (verified by source reasoning, per the
 * repo convention used by AyaneoDaemonSurvivalTest). Its decimation gate is a single pure
 * arithmetic predicate; this test mirrors that EXACT predicate so the cadence contract is
 * locked deterministically:
 *
 *   effectiveHintMs = hint.coerceAtLeast(ADAPTIVE_TICK_FLOOR_MS)   // 500 ms floor
 *   skip            = lastDecisionAtMs != 0L &&
 *                     (sampleMs - lastDecisionAtMs) < effectiveHintMs
 *
 * Properties proven:
 *   - the 500 ms floor: a hint below 500 ms is clamped UP to 500 ms (battery safety);
 *   - the default calm cadence (1000 ms) with a 1 Hz stream processes EVERY sample;
 *   - the warming cadence (500 ms) with a 1 Hz stream also processes EVERY sample (the
 *     1 Hz stream never out-runs the floor, so warming never starves);
 *   - a hypothetical slower-than-1 Hz hint decimates intervening samples;
 *   - the FIRST sample (lastDecisionAtMs == 0) always processes (no startup stall).
 */
class AutoTdpCadenceGateTest {

    /** The engine's service-facing cadence mirrors are the single source of truth. */
    private val floorMs = AutoTdpEngine.ADAPTIVE_TICK_FLOOR_MS
    private val calmMs = AutoTdpEngine.CALM_TICK_MS_DEFAULT

    /** Exact replica of the service gate: returns true when the tick is PROCESSED. */
    private fun processed(hintMs: Int, sampleMs: Long, lastDecisionAtMs: Long): Boolean {
        val effectiveHintMs = hintMs.coerceAtLeast(floorMs).toLong()
        val skip = lastDecisionAtMs != 0L && (sampleMs - lastDecisionAtMs) < effectiveHintMs
        return !skip
    }

    @Test
    fun `mirrors equal the engine cadence presets`() {
        assertThat(floorMs).isEqualTo(500)
        assertThat(calmMs).isEqualTo(1000)
    }

    @Test
    fun `the first sample always processes`() {
        // lastDecisionAtMs == 0 → never skipped, regardless of hint.
        assertThat(processed(hintMs = calmMs, sampleMs = 0L, lastDecisionAtMs = 0L)).isTrue()
        assertThat(processed(hintMs = floorMs, sampleMs = 0L, lastDecisionAtMs = 0L)).isTrue()
    }

    @Test
    fun `calm 1000ms hint on a 1Hz stream processes every sample`() {
        // 1 Hz stream: samples at 1000, 2000, 3000 ms after a decision at t.
        var last = 1_000L
        for (t in listOf(2_000L, 3_000L, 4_000L)) {
            assertThat(processed(hintMs = calmMs, sampleMs = t, lastDecisionAtMs = last)).isTrue()
            last = t
        }
    }

    @Test
    fun `warming 500ms hint on a 1Hz stream still processes every sample`() {
        // The stream is 1 Hz (1000 ms gaps) ≥ the 500 ms floor, so warming never skips.
        var last = 1_000L
        for (t in listOf(2_000L, 3_000L, 4_000L)) {
            assertThat(processed(hintMs = floorMs, sampleMs = t, lastDecisionAtMs = last)).isTrue()
            last = t
        }
    }

    @Test
    fun `a sub-floor hint is clamped up to the 500ms floor`() {
        // A hostile 100 ms hint must NOT let two samples 200 ms apart both process —
        // the floor forces ≥ 500 ms spacing (so the 200 ms-later sample is skipped).
        assertThat(processed(hintMs = 100, sampleMs = 1_200L, lastDecisionAtMs = 1_000L)).isFalse()
        // A sample 500 ms later (== floor) DOES process (boundary is inclusive: not < floor).
        assertThat(processed(hintMs = 100, sampleMs = 1_500L, lastDecisionAtMs = 1_000L)).isTrue()
    }

    @Test
    fun `a slower-than-1Hz hint decimates intervening samples`() {
        // Hypothetical future calm cadence of 2000 ms: at 1 Hz, the 1000-ms-later sample is
        // skipped and the 2000-ms-later one processes.
        assertThat(processed(hintMs = 2_000, sampleMs = 2_000L, lastDecisionAtMs = 1_000L)).isFalse()
        assertThat(processed(hintMs = 2_000, sampleMs = 3_000L, lastDecisionAtMs = 1_000L)).isTrue()
    }
}
