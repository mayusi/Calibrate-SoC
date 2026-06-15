package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [IdleChargeTrigger.decide] — the pure decision function.
 *
 * No Android runtime is required: we test only the companion object function
 * that contains all the conditional logic. The BroadcastReceiver lifecycle
 * and DataStore are NOT tested here (they require an instrumented environment).
 *
 * Rules under test:
 *   1. Toggle OFF → always null (never downclock without opt-in).
 *   2. Toggle ON + screen off → EFFICIENCY floor.
 *   3. Toggle ON + charging → EFFICIENCY floor.
 *   4. Toggle ON + screen off AND charging → EFFICIENCY floor (idempotent).
 *   5. Toggle ON + screen on AND unplugged → null (restore).
 *   6. Profile returned is exactly AutoTdpProfile.EFFICIENCY (not BALANCED).
 *   7. Returned config has no targetMilliWatts (pure efficiency mode).
 */
class IdleChargeTriggerTest {

    // ── Toggle-OFF gating ─────────────────────────────────────────────────────

    @Test
    fun `toggle off, screen on, unplugged — null`() {
        val result = IdleChargeTrigger.decide(enabled = false, screenOff = false, charging = false)
        assertThat(result).isNull()
    }

    @Test
    fun `toggle off, screen off — still null (never downclock without opt-in)`() {
        val result = IdleChargeTrigger.decide(enabled = false, screenOff = true, charging = false)
        assertThat(result).isNull()
    }

    @Test
    fun `toggle off, charging — still null`() {
        val result = IdleChargeTrigger.decide(enabled = false, screenOff = false, charging = true)
        assertThat(result).isNull()
    }

    @Test
    fun `toggle off, screen off and charging — still null`() {
        val result = IdleChargeTrigger.decide(enabled = false, screenOff = true, charging = true)
        assertThat(result).isNull()
    }

    // ── Toggle-ON: screen-off path ────────────────────────────────────────────

    @Test
    fun `toggle on, screen off, unplugged — EFFICIENCY floor`() {
        val result = IdleChargeTrigger.decide(enabled = true, screenOff = true, charging = false)
        assertThat(result).isNotNull()
        assertThat(result!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `toggle on, screen off result has no target watts`() {
        val result = IdleChargeTrigger.decide(enabled = true, screenOff = true, charging = false)
        assertThat(result).isNotNull()
        assertThat(result!!.targetMilliWatts).isNull()
    }

    // ── Toggle-ON: charging path ──────────────────────────────────────────────

    @Test
    fun `toggle on, screen on, charging — EFFICIENCY floor`() {
        val result = IdleChargeTrigger.decide(enabled = true, screenOff = false, charging = true)
        assertThat(result).isNotNull()
        assertThat(result!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    // ── Toggle-ON: screen-off AND charging (compound condition) ──────────────

    @Test
    fun `toggle on, screen off and charging — EFFICIENCY floor (compound)`() {
        val result = IdleChargeTrigger.decide(enabled = true, screenOff = true, charging = true)
        assertThat(result).isNotNull()
        assertThat(result!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    // ── Toggle-ON: screen-on AND unplugged → restore ──────────────────────────

    @Test
    fun `toggle on, screen on, unplugged — null (restore)`() {
        val result = IdleChargeTrigger.decide(enabled = true, screenOff = false, charging = false)
        assertThat(result).isNull()
    }

    // ── Profile is exactly EFFICIENCY, not BALANCED ───────────────────────────

    @Test
    fun `emitted profile is EFFICIENCY not BALANCED`() {
        val result = IdleChargeTrigger.decide(enabled = true, screenOff = true, charging = false)
        assertThat(result!!.profile).isNotEqualTo(AutoTdpProfile.BALANCED)
        assertThat(result.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    // ── Determinism: same inputs always produce same output ───────────────────

    @Test
    fun `pure function — same inputs same output on repeated calls`() {
        repeat(5) {
            val a = IdleChargeTrigger.decide(enabled = true, screenOff = true, charging = false)
            val b = IdleChargeTrigger.decide(enabled = true, screenOff = true, charging = false)
            assertThat(a).isEqualTo(b)
        }
    }
}
