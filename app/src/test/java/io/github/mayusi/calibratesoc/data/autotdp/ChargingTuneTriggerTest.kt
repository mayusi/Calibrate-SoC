package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ChargingTuneTrigger] pure decision functions.
 *
 * No Android runtime required — tests cover the companion object functions
 * that contain all conditional logic. The BroadcastReceiver lifecycle,
 * DataStore, and writer paths are not tested here (require instrumented env).
 *
 * Rules under test:
 *   shouldApply:
 *   1. Toggle OFF → never apply.
 *   2. Toggle ON + not charging → don't apply.
 *   3. Toggle ON + charging + gaming active → skip (don't stomp gaming session).
 *   4. Toggle ON + charging + not gaming → apply.
 *
 *   shouldRevert:
 *   5. Still charging + was applied → no revert (stay in charging-mode).
 *   6. Unplugged + was applied → revert.
 *   7. Unplugged + was NOT applied → no-op (nothing to revert).
 *   8. Still charging + was NOT applied → no-op.
 */
class ChargingTuneTriggerTest {

    // ── shouldApply: toggle-OFF gating ───────────────────────────────────────

    @Test
    fun `shouldApply — toggle off, not charging, not gaming — false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = false, charging = false, gamingActive = false),
        ).isFalse()
    }

    @Test
    fun `shouldApply — toggle off, charging, not gaming — still false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = false, charging = true, gamingActive = false),
        ).isFalse()
    }

    @Test
    fun `shouldApply — toggle off, charging, gaming — still false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = false, charging = true, gamingActive = true),
        ).isFalse()
    }

    // ── shouldApply: toggle-ON but not charging ───────────────────────────────

    @Test
    fun `shouldApply — toggle on, not charging, not gaming — false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = false, gamingActive = false),
        ).isFalse()
    }

    @Test
    fun `shouldApply — toggle on, not charging, gaming — false`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = false, gamingActive = true),
        ).isFalse()
    }

    // ── shouldApply: not-gaming gate ─────────────────────────────────────────

    @Test
    fun `shouldApply — toggle on, charging, gaming active — skip (never stomp gaming session)`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = true),
        ).isFalse()
    }

    // ── shouldApply: the happy path ───────────────────────────────────────────

    @Test
    fun `shouldApply — toggle on, charging, not gaming — true`() {
        assertThat(
            ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = false),
        ).isTrue()
    }

    // ── shouldRevert ─────────────────────────────────────────────────────────

    @Test
    fun `shouldRevert — still charging, bundle was applied — false (stay in charging-mode)`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = true, wasApplied = true),
        ).isFalse()
    }

    @Test
    fun `shouldRevert — unplugged, bundle was applied — true`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = true),
        ).isTrue()
    }

    @Test
    fun `shouldRevert — unplugged, bundle was NOT applied — false (no-op)`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = false),
        ).isFalse()
    }

    @Test
    fun `shouldRevert — still charging, bundle was NOT applied — false`() {
        assertThat(
            ChargingTuneTrigger.shouldRevert(charging = true, wasApplied = false),
        ).isFalse()
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    fun `shouldApply — pure function, same inputs same output`() {
        repeat(5) {
            val a = ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = false)
            val b = ChargingTuneTrigger.shouldApply(enabled = true, charging = true, gamingActive = false)
            assertThat(a).isEqualTo(b)
        }
    }

    @Test
    fun `shouldRevert — pure function, same inputs same output`() {
        repeat(5) {
            val a = ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = true)
            val b = ChargingTuneTrigger.shouldRevert(charging = false, wasApplied = true)
            assertThat(a).isEqualTo(b)
        }
    }
}
