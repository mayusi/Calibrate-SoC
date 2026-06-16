package io.github.mayusi.calibratesoc.data.thermal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ThrottleGuardActuator] — the pure apply/revert/suppress logic that
 * turns a [ThrottleForecast] into at most one big-cluster scaling_max_freq write.
 *
 * Confirms:
 *  - applies the recommended cap when the forecast says act,
 *  - reverts to stock when the forecast clears,
 *  - never applies (and reverts any active cap) while suppressed,
 *  - never writes the same cap twice (idempotent),
 *  - the cap write targets the big policy's scaling_max_freq.
 */
class ThrottleGuardActuatorTest {

    private val bigPolicyId = 4
    private val stockCeiling = 2_803_000
    private val maxFreqPath = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"

    private fun act() = ThrottleGuardActuator(bigPolicyId, stockCeiling)

    private fun forecastAct(capKhz: Int) = ThrottleForecast(
        willThrottleInSec = 6,
        recommendedCapKhz = capKhz,
        reason = "imminent",
    )

    private val forecastClear = ThrottleForecast.noAction("stable")

    // ─── Apply ───────────────────────────────────────────────────────────────────

    @Test
    fun `applies recommended cap to big policy scaling_max_freq when forecast acts`() {
        val a = act()
        val r = a.decide(forecastAct(2_323_000), suppressed = false)
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.id.target).isEqualTo(maxFreqPath)
        assertThat(r.write.value).isEqualTo("2323000")
        assertThat(r.activeCapKhz).isEqualTo(2_323_000)
        assertThat(a.activeCapKhz).isEqualTo(2_323_000)
    }

    @Test
    fun `does not re-write the same cap on a repeated forecast`() {
        val a = act()
        a.decide(forecastAct(2_323_000), suppressed = false) // applies
        val r = a.decide(forecastAct(2_323_000), suppressed = false) // same
        assertThat(r.write).isNull()
        assertThat(r.activeCapKhz).isEqualTo(2_323_000)
    }

    @Test
    fun `tightens the cap further when the forecast lowers it`() {
        val a = act()
        a.decide(forecastAct(2_323_000), suppressed = false)
        val r = a.decide(forecastAct(1_920_000), suppressed = false)
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.value).isEqualTo("1920000")
        assertThat(a.activeCapKhz).isEqualTo(1_920_000)
    }

    @Test
    fun `recommended cap at or above stock ceiling is treated as no cap`() {
        val a = act()
        val r = a.decide(forecastAct(stockCeiling + 100_000), suppressed = false)
        assertThat(r.write).isNull()
        assertThat(a.activeCapKhz).isNull()
    }

    // ─── Revert ──────────────────────────────────────────────────────────────────

    @Test
    fun `reverts to stock ceiling when the forecast clears`() {
        val a = act()
        a.decide(forecastAct(1_920_000), suppressed = false) // applied
        val r = a.decide(forecastClear, suppressed = false) // clear
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.id.target).isEqualTo(maxFreqPath)
        assertThat(r.write.value).isEqualTo(stockCeiling.toString())
        assertThat(r.activeCapKhz).isNull()
        assertThat(a.activeCapKhz).isNull()
    }

    @Test
    fun `clear forecast with no active cap writes nothing`() {
        val a = act()
        val r = a.decide(forecastClear, suppressed = false)
        assertThat(r.write).isNull()
        assertThat(r.activeCapKhz).isNull()
    }

    // ─── Suppression (AutoTDP / Game Boost active) ───────────────────────────────

    @Test
    fun `never applies a cap while suppressed`() {
        val a = act()
        val r = a.decide(forecastAct(1_920_000), suppressed = true)
        assertThat(r.write).isNull()
        assertThat(a.activeCapKhz).isNull()
    }

    @Test
    fun `reverts an active cap when suppression begins`() {
        val a = act()
        a.decide(forecastAct(1_920_000), suppressed = false) // applied
        val r = a.decide(forecastAct(1_536_000), suppressed = true) // now suppressed
        // Must revert to stock and ignore the new recommendation.
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.value).isEqualTo(stockCeiling.toString())
        assertThat(a.activeCapKhz).isNull()
    }

    @Test
    fun `suppressed with no active cap writes nothing`() {
        val a = act()
        val r = a.decide(forecastAct(1_920_000), suppressed = true)
        // First call under suppression: nothing to revert, nothing to apply.
        assertThat(r.write).isNull()
    }

    @Test
    fun `resumes capping after suppression lifts`() {
        val a = act()
        a.decide(forecastAct(1_920_000), suppressed = true) // suppressed, no-op
        val r = a.decide(forecastAct(1_920_000), suppressed = false) // resumes
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.value).isEqualTo("1920000")
        assertThat(a.activeCapKhz).isEqualTo(1_920_000)
    }

    // ─── HIGH-2: OPP-snap + 40% hard floor ───────────────────────────────────────

    // A real OPP table whose top is the stockCeiling; 40% of 2_803_000 = 1_121_200,
    // so the hard floor snaps to 1_420_800 (first OPP >= 40%).
    private val opps = listOf(
        307_200, 614_400, 1_017_600, 1_420_800, 1_804_800,
        2_208_000, 2_400_000, stockCeiling,
    )

    private fun actWithOpps() = ThrottleGuardActuator(bigPolicyId, stockCeiling, opps)

    @Test
    fun `snaps a between-OPP recommendation down to a real OPP`() {
        val a = actWithOpps()
        // 2_300_000 lands between 2_208_000 and 2_400_000 → snap DOWN to 2_208_000 so the
        // kernel won't silently clamp and activeCapKhz stays == reality.
        val r = a.decide(forecastAct(2_300_000), suppressed = false)
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.value).isEqualTo("2208000")
        assertThat(a.activeCapKhz).isEqualTo(2_208_000)
    }

    @Test
    fun `never caps below the 40 percent hard floor`() {
        val a = actWithOpps()
        // A forecast recommending a collapse to ~384 MHz (the DEFECT-A class) must be raised
        // to the shared 40%-of-top-OPP hard floor (1_420_800), never written through.
        val r = a.decide(forecastAct(384_000), suppressed = false)
        assertThat(r.write).isNotNull()
        assertThat(r.write!!.value).isEqualTo("1420800")
        assertThat(a.activeCapKhz).isEqualTo(1_420_800)
    }

    @Test
    fun `snapped cap is always a real OPP step`() {
        val a = actWithOpps()
        val r = a.decide(forecastAct(1_950_000), suppressed = false)
        val written = r.write!!.value.toInt()
        assertThat(opps).contains(written)
    }
}
