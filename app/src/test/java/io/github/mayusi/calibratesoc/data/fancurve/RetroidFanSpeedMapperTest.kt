package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanConfig
import org.junit.Test

/**
 * Pure tests for the FanCurve → Retroid custom-SPEED mapping + the HARD safe-minimum.
 *
 * Retroid has no curve-array transaction — its FanProvider takes a single scalar — so
 * the mapper collapses a curve to ONE representative held speed (the hot-zone floor,
 * the SAFE choice) and clamps it UP to [RetroidFanConfig.SPEED_SAFE_MIN]. These tests
 * pin the representative-duty choice, the linear duty%→speed mapping (given the
 * CALIBRATE-LIVE constants), and — critically — that the fan can NEVER be set below the
 * safe minimum.
 */
class RetroidFanSpeedMapperTest {

    // ── Representative duty (hot-zone floor) ────────────────────────────────────

    @Test
    fun `representativeDutyPct uses the hot-zone floor, not the peak or the low end`() {
        // Stock curve points: (0,20)(25,20)(45,20)(65,30)(85,45)(105,60)(MAX,60).
        // bandMinDuty(95) governs at the last point <=95 (85C → 45%) and every point
        // >=95 (105C/MAX → 60%): min(45,60,60) = 45. So the representative hot-zone
        // floor is 45 — NOT the 20% low end and NOT the 60% peak.
        assertThat(RetroidFanSpeedMapper.representativeDutyPct(FanCurve.STOCK)).isEqualTo(45)
    }

    @Test
    fun `representativeDutyPct of a strong full-tilt curve is 100`() {
        val curve = FanCurve(
            listOf(
                FanCurvePoint(0, 100),
                FanCurvePoint(FanCurve.SENTINEL_TEMP_C, 100),
            ),
        )
        assertThat(RetroidFanSpeedMapper.representativeDutyPct(curve)).isEqualTo(100)
    }

    // ── duty% → speed (linear over the CALIBRATE-LIVE range) ────────────────────

    @Test
    fun `dutyPctToSpeed maps 100 percent to SPEED_MAX`() {
        assertThat(RetroidFanSpeedMapper.dutyPctToSpeed(100)).isEqualTo(RetroidFanConfig.SPEED_MAX)
    }

    @Test
    fun `dutyPctToSpeed maps a mid duty linearly then respects the floor`() {
        // 60% of the 0..25000 span = 15000, which is above the 5000 safe floor → 15000.
        assertThat(RetroidFanSpeedMapper.dutyPctToSpeed(60)).isEqualTo(15_000)
    }

    // ── HARD safe-minimum (the safety contract) ─────────────────────────────────

    @Test
    fun `dutyPctToSpeed clamps a 0 percent duty UP to the safe minimum, never lower`() {
        // 0% would map to SPEED_MIN (0), but the safe floor forbids that — clamp UP.
        assertThat(RetroidFanSpeedMapper.dutyPctToSpeed(0)).isEqualTo(RetroidFanConfig.SPEED_SAFE_MIN)
    }

    @Test
    fun `dutyPctToSpeed never returns below the safe minimum for any duty in range`() {
        for (pct in 0..100) {
            assertThat(RetroidFanSpeedMapper.dutyPctToSpeed(pct))
                .isAtLeast(RetroidFanConfig.SPEED_SAFE_MIN)
        }
    }

    @Test
    fun `dutyPctToSpeed never exceeds SPEED_MAX even for out-of-range input`() {
        assertThat(RetroidFanSpeedMapper.dutyPctToSpeed(150)).isEqualTo(RetroidFanConfig.SPEED_MAX)
        assertThat(RetroidFanSpeedMapper.dutyPctToSpeed(-50)).isEqualTo(RetroidFanConfig.SPEED_SAFE_MIN)
    }

    // ── End-to-end: curveToSpeed ────────────────────────────────────────────────

    @Test
    fun `curveToSpeed maps the stock curve to its hot-zone-floor speed`() {
        // hot-zone floor 45% → 45% of 0..25000 = 11250 (above the 5000 floor).
        assertThat(RetroidFanSpeedMapper.curveToSpeed(FanCurve.STOCK)).isEqualTo(11_250)
    }

    @Test
    fun `clampSpeed is the single source of the safe band`() {
        assertThat(RetroidFanConfig.clampSpeed(0)).isEqualTo(RetroidFanConfig.SPEED_SAFE_MIN)
        assertThat(RetroidFanConfig.clampSpeed(1_000_000)).isEqualTo(RetroidFanConfig.SPEED_MAX)
        assertThat(RetroidFanConfig.clampSpeed(12_000)).isEqualTo(12_000)
    }

    @Test
    fun `the safe minimum is a meaningful fraction of full scale (not zero)`() {
        // Guard against a future edit accidentally zeroing the floor.
        assertThat(RetroidFanConfig.SPEED_SAFE_MIN).isGreaterThan(0)
        assertThat(RetroidFanConfig.SPEED_SAFE_MIN).isLessThan(RetroidFanConfig.SPEED_MAX)
    }
}
