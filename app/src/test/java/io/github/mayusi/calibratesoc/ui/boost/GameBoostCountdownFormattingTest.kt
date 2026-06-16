package io.github.mayusi.calibratesoc.ui.boost

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests for Game Boost time-box countdown display formatting.
 *
 * The formatting logic in [GameBoostCard]'s [TimeBoxCountdown] composable is:
 *
 *   val diff = (expiresEpochMs - nowMs).coerceAtLeast(0L)
 *   val totalSec = diff / 1_000L
 *   val min = totalSec / 60L
 *   val sec = totalSec % 60L
 *   "%02d:%02d".format(min, sec)
 *
 * These tests verify that formula — not the composable itself (which is tested
 * in UI/screenshot tests). Pure JVM, no Android or Compose dependencies.
 *
 * Covers:
 *  1. Exact formatting (zero-pad minutes and seconds).
 *  2. 30-minute time box standard case.
 *  3. Boundary: exactly one minute remaining.
 *  4. Boundary: exactly zero remaining (expired).
 *  5. Negative diff clamped to 0 (already expired).
 *  6. Maximum: 120-minute time box.
 */
class GameBoostCountdownFormattingTest {

    /** Replicates the formatting logic from TimeBoxCountdown. */
    private fun formatCountdown(expiresEpochMs: Long, nowMs: Long): String {
        val diff = (expiresEpochMs - nowMs).coerceAtLeast(0L)
        val totalSec = diff / 1_000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return "%02d:%02d".format(min, sec)
    }

    @Test
    fun thirtyMinutesExact() {
        val now = 0L
        val expires = 30 * 60 * 1_000L // 30 min
        assertThat(formatCountdown(expires, now)).isEqualTo("30:00")
    }

    @Test
    fun oneMinuteThirtySeconds() {
        val now = 0L
        val expires = (1 * 60 + 30) * 1_000L // 1m 30s
        assertThat(formatCountdown(expires, now)).isEqualTo("01:30")
    }

    @Test
    fun exactlyOneMinute() {
        val now = 0L
        val expires = 60 * 1_000L
        assertThat(formatCountdown(expires, now)).isEqualTo("01:00")
    }

    @Test
    fun exactlyOneSecond() {
        val now = 0L
        val expires = 1_000L
        assertThat(formatCountdown(expires, now)).isEqualTo("00:01")
    }

    @Test
    fun expired_showsZero() {
        val now = 10_000L
        val expires = 5_000L   // already in the past
        assertThat(formatCountdown(expires, now)).isEqualTo("00:00")
    }

    @Test
    fun zeroRemaining_showsZero() {
        val now = 1_000L
        val expires = 1_000L
        assertThat(formatCountdown(expires, now)).isEqualTo("00:00")
    }

    @Test
    fun oneHundredTwentyMinutes() {
        val now = 0L
        val expires = 120 * 60 * 1_000L
        assertThat(formatCountdown(expires, now)).isEqualTo("120:00")
    }

    @Test
    fun zeroPaddedSeconds() {
        // 5 minutes and 3 seconds should produce "05:03" (zero-padded)
        val now = 0L
        val expires = (5 * 60 + 3) * 1_000L
        assertThat(formatCountdown(expires, now)).isEqualTo("05:03")
    }

    @Test
    fun zeroPaddedMinutes() {
        // 9 minutes 59 seconds should produce "09:59"
        val now = 0L
        val expires = (9 * 60 + 59) * 1_000L
        assertThat(formatCountdown(expires, now)).isEqualTo("09:59")
    }

    @Test
    fun partialSecondTruncated() {
        // 90.9 seconds of real-time should show 01:30 (not 01:31)
        val now = 0L
        val expires = 90_900L   // 90.9 seconds in ms
        assertThat(formatCountdown(expires, now)).isEqualTo("01:30")
    }

    @Test
    fun negativeDiffClamped() {
        // Far expired (by 1 hour) — should clamp to 00:00
        val now = 3_600_000L + 500L  // 1 hour + 500ms
        val expires = 0L             // expired 1 hour ago
        assertThat(formatCountdown(expires, now)).isEqualTo("00:00")
    }

    @Test
    fun exactlyOneMinuteBeforeExpiry() {
        // Elapsed 29 minutes out of 30-minute box
        val startMs = 0L
        val expireMs = 30 * 60 * 1_000L
        val nowMs = 29 * 60 * 1_000L   // 29 minutes elapsed
        assertThat(formatCountdown(expireMs, nowMs)).isEqualTo("01:00")
    }
}
