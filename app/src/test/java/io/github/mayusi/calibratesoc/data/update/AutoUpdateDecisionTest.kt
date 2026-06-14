package io.github.mayusi.calibratesoc.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for [AutoUpdateDecision] — the stateless helper that decides
 * (a) whether a daily check is due, and (b) whether the banner should be shown.
 *
 * No Android runtime, no coroutines, no DataStore — the logic is deliberately
 * extracted so it can be verified here cheaply and exhaustively.
 *
 * Coverage:
 *   isDue:      first run (lastCheckMs=0), within 24h, exactly at 24h, past 24h.
 *   shouldShow: snooze active, snooze expired, same tag dismissed, different tag,
 *               no prior dismiss (dismissedTag=null), compound cases.
 */
class AutoUpdateDecisionTest {

    // ── isDue ─────────────────────────────────────────────────────────────────

    @Test
    fun `isDue returns true when never checked before (lastCheckMs is zero)`() {
        val now = System.currentTimeMillis()
        assertThat(AutoUpdateDecision.isDue(nowMs = now, lastCheckMs = 0L)).isTrue()
    }

    @Test
    fun `isDue returns false when checked less than 24 hours ago`() {
        val now = 1_000_000_000_000L
        val lastCheck = now - (23L * 60 * 60 * 1000)   // 23h ago
        assertThat(AutoUpdateDecision.isDue(nowMs = now, lastCheckMs = lastCheck)).isFalse()
    }

    @Test
    fun `isDue returns true when exactly 24 hours have passed`() {
        val now = 1_000_000_000_000L
        val lastCheck = now - (24L * 60 * 60 * 1000)   // exactly 24h ago
        assertThat(AutoUpdateDecision.isDue(nowMs = now, lastCheckMs = lastCheck)).isTrue()
    }

    @Test
    fun `isDue returns true when more than 24 hours have passed`() {
        val now = 1_000_000_000_000L
        val lastCheck = now - (48L * 60 * 60 * 1000)   // 48h ago
        assertThat(AutoUpdateDecision.isDue(nowMs = now, lastCheckMs = lastCheck)).isTrue()
    }

    @Test
    fun `isDue returns false when checked just 1 ms ago`() {
        val now = 1_000_000_000_000L
        assertThat(AutoUpdateDecision.isDue(nowMs = now, lastCheckMs = now - 1)).isFalse()
    }

    // ── shouldShow ────────────────────────────────────────────────────────────

    @Test
    fun `shouldShow returns true when no snooze and no dismissed tag`() {
        val now = 1_000_000_000_000L
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = 0L,          // no snooze
                dismissedTag = null,         // nothing dismissed
                incomingTag = "v1.2.0",
            )
        ).isTrue()
    }

    @Test
    fun `shouldShow returns false while snooze is active`() {
        val now = 1_000_000_000_000L
        val snoozedUntil = now + (3L * 24 * 60 * 60 * 1000)  // snooze expires 3 days from now
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = snoozedUntil,
                dismissedTag = null,
                incomingTag = "v1.2.0",
            )
        ).isFalse()
    }

    @Test
    fun `shouldShow returns true when snooze has just expired`() {
        val now = 1_000_000_000_000L
        val snoozedUntil = now - 1L   // expired 1ms ago
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = snoozedUntil,
                dismissedTag = null,
                incomingTag = "v1.2.0",
            )
        ).isTrue()
    }

    @Test
    fun `shouldShow returns false when incoming tag was dismissed`() {
        val now = 1_000_000_000_000L
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = 0L,
                dismissedTag = "v1.2.0",
                incomingTag = "v1.2.0",    // same tag — user already dismissed this one
            )
        ).isFalse()
    }

    @Test
    fun `shouldShow returns true when a newer tag arrives after a previous dismissal`() {
        val now = 1_000_000_000_000L
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = 0L,
                dismissedTag = "v1.2.0",   // user dismissed v1.2.0
                incomingTag = "v1.3.0",    // but v1.3.0 is genuinely new → show it
            )
        ).isTrue()
    }

    @Test
    fun `shouldShow returns false when both snooze is active and tag is dismissed`() {
        val now = 1_000_000_000_000L
        val snoozedUntil = now + (7L * 24 * 60 * 60 * 1000)
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = snoozedUntil,
                dismissedTag = "v1.2.0",
                incomingTag = "v1.2.0",
            )
        ).isFalse()
    }

    @Test
    fun `shouldShow returns true when dismissedTag is null and no snooze`() {
        // dismissedTag null means user has never dismissed anything
        val now = 1_000_000_000_000L
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = 0L,
                dismissedTag = null,
                incomingTag = "v0.9.0",
            )
        ).isTrue()
    }

    @Test
    fun `shouldShow handles remindAfterMs exactly equal to nowMs as not-yet-expired`() {
        // The condition is nowMs > remindAfterMs (strict), so == means still snoozed.
        val now = 1_000_000_000_000L
        assertThat(
            AutoUpdateDecision.shouldShow(
                nowMs = now,
                remindAfterMs = now,
                dismissedTag = null,
                incomingTag = "v1.0.0",
            )
        ).isFalse()
    }
}
