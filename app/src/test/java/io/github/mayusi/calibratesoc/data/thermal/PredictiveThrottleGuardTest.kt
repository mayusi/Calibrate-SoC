package io.github.mayusi.calibratesoc.data.thermal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PredictiveThrottleGuardTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * Build a window point with a single CPU zone temperature [tempC] and
     * a big-cluster max of [clkKhz] kHz, at [tsMs] milliseconds.
     */
    private fun pt(tsMs: Long, tempC: Float, clkKhz: Int = 3_000_000) =
        PredictiveThrottleGuard.TelemetryPoint(
            timestampMs = tsMs,
            cpuZoneTempsMilliC = listOf((tempC * 1000).toInt()),
            bigClusterMaxKhz = clkKhz,
        )

    // ── Window too small ──────────────────────────────────────────────────────

    @Test
    fun `window smaller than MIN_WINDOW_POINTS returns noAction`() {
        val result = PredictiveThrottleGuard.predict(
            window = listOf(pt(0, 60f), pt(1000, 65f)), // only 2 points
            tripPointC = 90f,
            horizonSeconds = 10,
        )
        assertThat(result.actionRequired).isFalse()
        assertThat(result.willThrottleInSec).isNull()
        assertThat(result.recommendedCapKhz).isNull()
    }

    @Test
    fun `empty window returns noAction`() {
        val result = PredictiveThrottleGuard.predict(emptyList())
        assertThat(result.actionRequired).isFalse()
    }

    // ── Stable / cooling window ───────────────────────────────────────────────

    @Test
    fun `stable temperature returns noAction`() {
        // Flat at 70°C — slope ≈ 0 — well within the trip horizon.
        val window = listOf(
            pt(0,    70f),
            pt(1000, 70f),
            pt(2000, 70f),
            pt(3000, 70f),
        )
        val result = PredictiveThrottleGuard.predict(window, tripPointC = 90f, horizonSeconds = 10)
        assertThat(result.actionRequired).isFalse()
    }

    @Test
    fun `cooling window returns noAction`() {
        // Temperature falling 1°C/s — no throttle risk.
        val window = listOf(
            pt(0,    80f),
            pt(1000, 79f),
            pt(2000, 78f),
            pt(3000, 77f),
        )
        val result = PredictiveThrottleGuard.predict(window, tripPointC = 90f, horizonSeconds = 10)
        assertThat(result.actionRequired).isFalse()
    }

    // ── Rising temperature outside horizon ───────────────────────────────────

    @Test
    fun `slow rise that won't hit trip within horizon returns noAction`() {
        // Rising at 0.5°C/s from 70°C; trip at 90°C → needs 40s to reach it.
        // horizonSeconds = 10 → no action yet.
        val window = listOf(
            pt(0,    70f),
            pt(2000, 71f),
            pt(4000, 72f),
            pt(6000, 73f),
        )
        val result = PredictiveThrottleGuard.predict(window, tripPointC = 90f, horizonSeconds = 10)
        assertThat(result.actionRequired).isFalse()
    }

    // ── Rising temperature within horizon → action required ──────────────────

    @Test
    fun `fast rise within horizon produces a forecast with recommended cap`() {
        // Rising at ~3°C/s from 80°C; trip at 90°C.
        // headroom = 10°C, rate = 3°C/s → hits trip in ~3.3s < horizon 10s.
        val window = listOf(
            pt(0,    80f, clkKhz = 3_000_000),
            pt(1000, 83f, clkKhz = 3_000_000),
            pt(2000, 86f, clkKhz = 3_000_000),
            pt(3000, 89f, clkKhz = 3_000_000),
        )
        val result = PredictiveThrottleGuard.predict(window, tripPointC = 90f, horizonSeconds = 10)

        assertThat(result.actionRequired).isTrue()
        assertThat(result.willThrottleInSec).isNotNull()
        assertThat(result.willThrottleInSec!!).isAtMost(5)  // should be ~1s at 89°C baseline
        assertThat(result.recommendedCapKhz).isNotNull()
        // Cap should be less than current 3 GHz
        assertThat(result.recommendedCapKhz!!).isLessThan(3_000_000)
        // Cap must be at least 300 MHz floor
        assertThat(result.recommendedCapKhz).isAtLeast(300_000)
        // Reason string must be present
        assertThat(result.reason).isNotEmpty()
    }

    @Test
    fun `recommended cap is lower than current clock and at least floor`() {
        val window = listOf(
            pt(0,    82f, clkKhz = 2_400_000),
            pt(1000, 85f, clkKhz = 2_400_000),
            pt(2000, 88f, clkKhz = 2_400_000),
            pt(3000, 89f, clkKhz = 2_400_000),
        )
        val result = PredictiveThrottleGuard.predict(window, tripPointC = 90f, horizonSeconds = 10)
        if (result.actionRequired) {
            assertThat(result.recommendedCapKhz!!).isLessThan(2_400_000)
            assertThat(result.recommendedCapKhz!!).isAtLeast(300_000)
        }
    }

    // ── Trip already exceeded ─────────────────────────────────────────────────

    @Test
    fun `temperature already above trip point returns noAction with explanation`() {
        // Already at 92°C > 90°C trip — headroom is negative; no action from us.
        val window = listOf(
            pt(0,    90f),
            pt(1000, 91f),
            pt(2000, 92f),
            pt(3000, 92f),
        )
        val result = PredictiveThrottleGuard.predict(window, tripPointC = 90f, horizonSeconds = 10)
        // headroom <= 0 → noAction (kernel has already throttled; we should not stack)
        assertThat(result.actionRequired).isFalse()
        assertThat(result.reason).contains("°C")
    }

    // ── Linear slope helper ───────────────────────────────────────────────────

    @Test
    fun `linearSlopePerSec returns correct slope for perfect linear data`() {
        // y = 2*x → slope should be 2.0
        val xs = listOf(0.0, 1.0, 2.0, 3.0)
        val ys = listOf(0.0, 2.0, 4.0, 6.0)
        val slope = PredictiveThrottleGuard.linearSlopePerSec(xs, ys)
        assertThat(slope).isWithin(0.001).of(2.0)
    }

    @Test
    fun `linearSlopePerSec returns zero for constant series`() {
        val xs = listOf(0.0, 1.0, 2.0)
        val ys = listOf(5.0, 5.0, 5.0)
        assertThat(PredictiveThrottleGuard.linearSlopePerSec(xs, ys)).isWithin(0.001).of(0.0)
    }

    @Test
    fun `linearSlopePerSec returns zero for single point`() {
        assertThat(PredictiveThrottleGuard.linearSlopePerSec(listOf(0.0), listOf(70.0))).isEqualTo(0.0)
    }

    @Test
    fun `linearSlopePerSec returns negative for falling series`() {
        // y = -3*x
        val xs = listOf(0.0, 1.0, 2.0)
        val ys = listOf(90.0, 87.0, 84.0)
        val slope = PredictiveThrottleGuard.linearSlopePerSec(xs, ys)
        assertThat(slope).isWithin(0.001).of(-3.0)
    }

    // ── noAction factory ──────────────────────────────────────────────────────

    @Test
    fun `noAction creates a non-actionable forecast with the given reason`() {
        val f = ThrottleForecast.noAction("test reason")
        assertThat(f.actionRequired).isFalse()
        assertThat(f.willThrottleInSec).isNull()
        assertThat(f.recommendedCapKhz).isNull()
        assertThat(f.reason).isEqualTo("test reason")
    }

    // ── ThrottleForecast.actionRequired logic ─────────────────────────────────

    @Test
    fun `actionRequired is true only when both fields are non-null`() {
        assertThat(ThrottleForecast(willThrottleInSec = 5, recommendedCapKhz = 2_400_000, reason = "r").actionRequired).isTrue()
        assertThat(ThrottleForecast(willThrottleInSec = null, recommendedCapKhz = 2_400_000, reason = "r").actionRequired).isFalse()
        assertThat(ThrottleForecast(willThrottleInSec = 5, recommendedCapKhz = null, reason = "r").actionRequired).isFalse()
        assertThat(ThrottleForecast(willThrottleInSec = null, recommendedCapKhz = null, reason = "r").actionRequired).isFalse()
    }
}
