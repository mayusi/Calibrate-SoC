package io.github.mayusi.calibratesoc.data.monitor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for [computeBatteryEstimate] and [smoothedPowerMilliW].
 *
 * No Android APIs involved — these functions are intentionally kept free
 * of framework dependencies so they can be verified here without
 * Robolectric overhead.
 */
class BatteryEstimateTest {

    // ─── computeBatteryEstimate ──────────────────────────────────────────

    @Test
    fun `normal discharge with live voltage produces LIVE_DRAW with positive hours`() {
        // 30 000 mAh remaining at 3.8 V → 114 Wh; at 8 W → 14.25 h
        val chargeUah = 30_000_000L          // 30 000 000 µAh = 30 000 mAh
        val voltageV = 3.8
        val powerMw = 8_000L                 // 8 W

        val result = computeBatteryEstimate(
            chargeCounterUah = chargeUah,
            nominalVoltageV = voltageV,
            smoothedPowerMilliW = powerMw,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.LIVE_DRAW)
        assertThat(result.hoursRemaining).isNotNull()
        // 30 Ah * 3.8 V = 114 Wh; 114 / 8 = 14.25 h
        assertThat(result.hoursRemaining!!).isWithin(0.01).of(14.25)
        assertThat(result.watts).isWithin(0.001).of(8.0)
    }

    @Test
    fun `normal discharge falls back to 3_85 V when voltage absent`() {
        // 6 000 000 µAh (6000 mAh) at fallback 3.85 V → 23.1 Wh; at 5 W → 4.62 h
        val chargeUah = 6_000_000L
        val powerMw = 5_000L

        val result = computeBatteryEstimate(
            chargeCounterUah = chargeUah,
            nominalVoltageV = FALLBACK_VOLTAGE_V,   // 3.85 V
            smoothedPowerMilliW = powerMw,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.LIVE_DRAW)
        // 6 Ah * 3.85 V = 23.1 Wh; 23.1 / 5 = 4.62 h
        assertThat(result.hoursRemaining!!).isWithin(0.01).of(4.62)
    }

    @Test
    fun `charging (zero draw) yields CHARGING and null hoursRemaining`() {
        val result = computeBatteryEstimate(
            chargeCounterUah = 5_000_000L,
            nominalVoltageV = 3.85,
            smoothedPowerMilliW = 0L,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.CHARGING)
        assertThat(result.hoursRemaining).isNull()
        assertThat(result.watts).isWithin(0.0001).of(0.0)
    }

    @Test
    fun `negative draw (charging) yields CHARGING and null hoursRemaining`() {
        val result = computeBatteryEstimate(
            chargeCounterUah = 4_000_000L,
            nominalVoltageV = 3.85,
            smoothedPowerMilliW = -2_000L,    // negative = sign-inverted current (charging)
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.CHARGING)
        assertThat(result.hoursRemaining).isNull()
        assertThat(result.watts!!).isLessThan(0.0)
    }

    @Test
    fun `null charge counter yields INSUFFICIENT_DATA`() {
        val result = computeBatteryEstimate(
            chargeCounterUah = null,
            nominalVoltageV = 3.85,
            smoothedPowerMilliW = 7_000L,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.INSUFFICIENT_DATA)
        assertThat(result.hoursRemaining).isNull()
        // watts is still surfaced when power data is available
        assertThat(result.watts).isWithin(0.001).of(7.0)
    }

    @Test
    fun `null power draw yields INSUFFICIENT_DATA even with charge counter`() {
        val result = computeBatteryEstimate(
            chargeCounterUah = 5_000_000L,
            nominalVoltageV = 3.85,
            smoothedPowerMilliW = null,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.INSUFFICIENT_DATA)
        assertThat(result.hoursRemaining).isNull()
        assertThat(result.watts).isNull()
    }

    @Test
    fun `both inputs null yields INSUFFICIENT_DATA with null watts`() {
        val result = computeBatteryEstimate(
            chargeCounterUah = null,
            nominalVoltageV = 3.85,
            smoothedPowerMilliW = null,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.INSUFFICIENT_DATA)
        assertThat(result.hoursRemaining).isNull()
        assertThat(result.watts).isNull()
    }

    @Test
    fun `Odin3 scenario - 7838 mAh at 10 W gives expected hours`() {
        // Odin3 design capacity: 7838 mAh. With 50% charge remaining = ~3919 mAh.
        // At 3.9 V live voltage: 3.919 Ah * 3.9 V = ~15.28 Wh.
        // At 10 W: ~1.528 h
        val chargeUah = 3_919_000L           // ~50% of 7838 mAh in µAh
        val voltageV = 3.9
        val powerMw = 10_000L

        val result = computeBatteryEstimate(
            chargeCounterUah = chargeUah,
            nominalVoltageV = voltageV,
            smoothedPowerMilliW = powerMw,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.LIVE_DRAW)
        assertThat(result.hoursRemaining!!).isWithin(0.02).of(1.528)
    }

    @Test
    fun `sub-hour estimate stays positive and finite`() {
        // 500 mAh remaining at 3.85 V → 1.925 Wh; at 5 W → 0.385 h (~23 min)
        val result = computeBatteryEstimate(
            chargeCounterUah = 500_000L,
            nominalVoltageV = 3.85,
            smoothedPowerMilliW = 5_000L,
        )

        assertThat(result.basis).isEqualTo(EstimateBasis.LIVE_DRAW)
        assertThat(result.hoursRemaining!!).isGreaterThan(0.0)
        assertThat(result.hoursRemaining!!).isWithin(0.005).of(0.385)
    }

    // ─── smoothedPowerMilliW ─────────────────────────────────────────────

    @Test
    fun `empty history returns null`() {
        assertThat(smoothedPowerMilliW(emptyList())).isNull()
    }

    @Test
    fun `single sample with valid power returns that value`() {
        val t = makeTelemetry(currentUa = 2_000_000L, voltageUv = 4_000_000L)
        // 2 A * 4 V = 8 W = 8000 mW
        val result = smoothedPowerMilliW(listOf(t))
        assertThat(result).isNotNull()
        // The batteryDrawMilliW uses µA * µV / 1_000_000_000 → 8_000_000 / 1_000 = 8000
        assertThat(result).isEqualTo(8_000L)
    }

    @Test
    fun `samples with null power are excluded from average`() {
        val goodSample = makeTelemetry(currentUa = 3_000_000L, voltageUv = 4_000_000L)
        // 3 A * 4 V = 12 W
        val nullSample = makeTelemetry(currentUa = null, voltageUv = null)
        val result = smoothedPowerMilliW(listOf(nullSample, goodSample, nullSample))
        // Only the good sample contributes; result is 12_000 mW
        assertThat(result).isEqualTo(12_000L)
    }

    @Test
    fun `average across multiple samples`() {
        val s1 = makeTelemetry(currentUa = 2_000_000L, voltageUv = 4_000_000L)  // 8 W
        val s2 = makeTelemetry(currentUa = 4_000_000L, voltageUv = 4_000_000L)  // 16 W
        val result = smoothedPowerMilliW(listOf(s1, s2))
        // Average = (8000 + 16000) / 2 = 12000
        assertThat(result).isEqualTo(12_000L)
    }

    @Test
    fun `tail size is respected - uses only last N samples`() {
        // 3 old samples at 2 W, 2 fresh samples at 10 W; tailSize=2 → avg 10 W
        val old = makeTelemetry(currentUa = 500_000L, voltageUv = 4_000_000L)   // 2 W
        val fresh = makeTelemetry(currentUa = 2_500_000L, voltageUv = 4_000_000L) // 10 W
        val history = listOf(old, old, old, fresh, fresh)
        val result = smoothedPowerMilliW(history, tailSize = 2)
        assertThat(result).isEqualTo(10_000L)
    }

    @Test
    fun `all-null history returns null`() {
        val nullSamples = List(5) { makeTelemetry(currentUa = null, voltageUv = null) }
        assertThat(smoothedPowerMilliW(nullSamples)).isNull()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun makeTelemetry(
        currentUa: Long?,
        voltageUv: Long?,
    ) = Telemetry(
        timestampMs = System.currentTimeMillis(),
        perCoreCpuFreqKhz = emptyList(),
        perCoreLoadPct = emptyList(),
        gpuLoadPct = null,
        gpuFreqHz = null,
        zoneTempsMilliC = emptyList(),
        ramTotalKb = 0L,
        ramAvailableKb = 0L,
        batteryTempDeciC = null,
        batteryCurrentUa = currentUa,
        batteryVoltageUv = voltageUv,
        fanRpm = null,
    )
}
