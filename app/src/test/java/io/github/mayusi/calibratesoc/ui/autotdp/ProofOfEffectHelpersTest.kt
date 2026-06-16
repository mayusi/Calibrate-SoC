package io.github.mayusi.calibratesoc.ui.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.HoldReason
import org.junit.Test

/**
 * JVM unit tests for the pure proof-of-effect helpers in AutoTdpScreen.kt.
 *
 * These encode the AutoTDP honesty contract for the "proof it's working" panel:
 *  - LOAD_BLIND_HOLDING must read as "load unreadable", NEVER as idle.
 *  - relative-time labels and heartbeat staleness are deterministic and never
 *    print a nonsense value on clock skew.
 *
 * No Android, no Compose — every function under test is a plain top-level fun.
 */
class ProofOfEffectHelpersTest {

    // ── holdReasonLabel: the mandatory load-blind ≠ idle distinction ──────────

    @Test
    fun `LOAD_BLIND_HOLDING label says unreadable, not idle`() {
        val label = holdReasonLabel(HoldReason.LOAD_BLIND_HOLDING)
        assertThat(label).contains("unreadable")
        assertThat(label.lowercase()).doesNotContain("idle")
    }

    @Test
    fun `IDLE_HOLDING label is distinct from load-blind`() {
        val idle = holdReasonLabel(HoldReason.IDLE_HOLDING)
        val blind = holdReasonLabel(HoldReason.LOAD_BLIND_HOLDING)
        assertThat(idle).isNotEqualTo(blind)
    }

    @Test
    fun `every HoldReason has a non-blank label, explainer, and accent`() {
        HoldReason.entries.forEach { reason ->
            assertThat(holdReasonLabel(reason)).isNotEmpty()
            assertThat(holdReasonExplainer(reason)).isNotEmpty()
            // accent must resolve without throwing
            holdReasonAccent(reason)
        }
    }

    @Test
    fun `LOAD_BLIND explainer explicitly states it is not idle`() {
        assertThat(holdReasonExplainer(HoldReason.LOAD_BLIND_HOLDING).lowercase())
            .contains("not idle")
    }

    // ── relativeTimeAgo ───────────────────────────────────────────────────────

    @Test
    fun `relativeTimeAgo clamps sub-second and future deltas to just now`() {
        assertThat(relativeTimeAgo(epochMs = 1_000L, nowMs = 1_500L)).isEqualTo("just now")
        // Clock skew: event in the future must not print a negative value.
        assertThat(relativeTimeAgo(epochMs = 5_000L, nowMs = 1_000L)).isEqualTo("just now")
    }

    @Test
    fun `relativeTimeAgo renders seconds, minutes, and hours`() {
        val now = 1_000_000_000L
        assertThat(relativeTimeAgo(now - 5_000L, now)).isEqualTo("5s ago")
        assertThat(relativeTimeAgo(now - 90_000L, now)).isEqualTo("1m ago")
        assertThat(relativeTimeAgo(now - 7_200_000L, now)).isEqualTo("2h ago")
    }

    // ── isHeartbeatStale ──────────────────────────────────────────────────────

    @Test
    fun `heartbeat is fresh within the threshold and stale beyond it`() {
        val now = 100_000L
        assertThat(isHeartbeatStale(now - 1_000L, now)).isFalse()
        assertThat(isHeartbeatStale(now - 2_999L, now)).isFalse()
        assertThat(isHeartbeatStale(now - 5_000L, now)).isTrue()
    }

    @Test
    fun `heartbeat freshness honours a custom threshold`() {
        val now = 100_000L
        assertThat(isHeartbeatStale(now - 4_000L, now, thresholdMs = 10_000L)).isFalse()
        assertThat(isHeartbeatStale(now - 11_000L, now, thresholdMs = 10_000L)).isTrue()
    }
}
