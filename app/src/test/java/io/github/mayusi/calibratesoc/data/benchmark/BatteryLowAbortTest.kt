package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [shouldAbortLowBattery].
 *
 * All cases use the pure predicate directly — no Android dependencies, no
 * mocks needed.
 */
class BatteryLowAbortTest {

    // ── null percent → never abort ────────────────────────────────────

    @Test
    fun `null percent not charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = null, charging = false)).isFalse()
    }

    @Test
    fun `null percent charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = null, charging = true)).isFalse()
    }

    @Test
    fun `null percent null charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = null, charging = null)).isFalse()
    }

    // ── percent >= 15 → never abort ───────────────────────────────────

    @Test
    fun `percent 15 not charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = 15, charging = false)).isFalse()
    }

    @Test
    fun `percent 50 not charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = 50, charging = false)).isFalse()
    }

    @Test
    fun `percent 100 not charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = 100, charging = false)).isFalse()
    }

    @Test
    fun `percent 15 charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = 15, charging = true)).isFalse()
    }

    // ── percent < 15 and charging → do NOT abort ─────────────────────

    @Test
    fun `percent 14 charging true returns false`() {
        assertThat(shouldAbortLowBattery(percent = 14, charging = true)).isFalse()
    }

    @Test
    fun `percent 0 charging true returns false`() {
        assertThat(shouldAbortLowBattery(percent = 0, charging = true)).isFalse()
    }

    // ── percent < 15 and NOT charging (or unknown) → abort ───────────

    @Test
    fun `percent 14 not charging returns true`() {
        assertThat(shouldAbortLowBattery(percent = 14, charging = false)).isTrue()
    }

    @Test
    fun `percent 0 not charging returns true`() {
        assertThat(shouldAbortLowBattery(percent = 0, charging = false)).isTrue()
    }

    @Test
    fun `percent 14 charging null returns true`() {
        // Unknown charging state + confirmed low charge → abort (safer option).
        assertThat(shouldAbortLowBattery(percent = 14, charging = null)).isTrue()
    }

    @Test
    fun `percent 1 charging null returns true`() {
        assertThat(shouldAbortLowBattery(percent = 1, charging = null)).isTrue()
    }

    // ── boundary: exactly 15 is safe ─────────────────────────────────

    @Test
    fun `percent exactly 15 null charging returns false`() {
        assertThat(shouldAbortLowBattery(percent = 15, charging = null)).isFalse()
    }
}
