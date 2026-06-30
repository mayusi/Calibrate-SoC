package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanConfig
import org.junit.Test

/**
 * Honesty tests for the Retroid live-fan readout model (BUG 2).
 *
 * On RP6 the FanProvider binder (txn 2) returns the configured custom-SPEED SETPOINT on
 * the ~25000 scale — NOT the governor's live actual duty. The stock default is 25000, so
 * naively computing `setpoint / SPEED_MAX` yields a fabricated 100% while the real idle
 * fan is ~20%. These tests pin the honest behaviour: the value is flagged as a SETPOINT
 * (so the UI never presents it as live fan duty), and the percent reflects the real
 * setpoint fraction (a 20%-of-scale setpoint reads 20, not 100).
 */
class RetroidLiveFanReadoutTest {

    @Test
    fun `setpoint reading is flagged as setpoint not live duty`() {
        val reading = LiveFanReading(
            dutyRaw = RetroidFanConfig.SPEED_MAX,
            periodRaw = RetroidFanConfig.SPEED_MAX,
            fanMode = RetroidFanConfig.SMART_MODE,
            dutyIsSetpoint = true,
        )
        // The raw 25000/25000 ratio IS 100% as a setpoint fraction — but the flag tells
        // the UI to label it "Custom speed (setpoint)", never "Fan duty 100%". The honest
        // note then explains the governor controls the fan in Smart mode.
        assertThat(reading.dutyIsSetpoint).isTrue()
        assertThat(reading.dutyPct).isEqualTo(100) // setpoint % of full scale, honestly labelled
    }

    @Test
    fun `a 20 percent-of-scale setpoint reads 20 not 100`() {
        // A custom speed set to 20% of full scale (5000 / 25000) must read back as 20%,
        // proving the percent is a true fraction of the setpoint scale — the misread that
        // made idle show 100% was raw==period (setpoint==full scale), not a maths bug.
        val twentyPctSpeed = RetroidFanConfig.SPEED_MAX / 5 // 5000
        val reading = LiveFanReading(
            dutyRaw = twentyPctSpeed,
            periodRaw = RetroidFanConfig.SPEED_MAX,
            fanMode = RetroidFanConfig.CUSTOM_MODE,
            dutyIsSetpoint = true,
        )
        assertThat(reading.dutyPct).isEqualTo(20)
    }

    @Test
    fun `unreadable setpoint yields null percent never a fabricated value`() {
        val reading = LiveFanReading(
            dutyRaw = null,
            periodRaw = null,
            fanMode = RetroidFanConfig.SMART_MODE,
            dutyIsSetpoint = true,
        )
        assertThat(reading.dutyPct).isNull()
    }

    @Test
    fun `fan mode names resolve honestly instead of unknown`() {
        assertThat(RetroidFanConfig.fanModeName(0)).isEqualTo("Off")
        assertThat(RetroidFanConfig.fanModeName(1)).isEqualTo("Quiet")
        assertThat(RetroidFanConfig.fanModeName(RetroidFanConfig.SMART_MODE)).isEqualTo("Smart (auto)")
        assertThat(RetroidFanConfig.fanModeName(3)).isEqualTo("Sport")
        assertThat(RetroidFanConfig.fanModeName(RetroidFanConfig.CUSTOM_MODE)).isEqualTo("Custom")
    }

    @Test
    fun `unknown fan mode int returns null so the UI can fall back honestly`() {
        assertThat(RetroidFanConfig.fanModeName(99)).isNull()
        assertThat(RetroidFanConfig.fanModeName(null)).isNull()
    }

    @Test
    fun `only custom mode counts as the setpoint actually driving the fan`() {
        assertThat(RetroidFanConfig.isCustomMode(RetroidFanConfig.CUSTOM_MODE)).isTrue()
        assertThat(RetroidFanConfig.isCustomMode(RetroidFanConfig.SMART_MODE)).isFalse()
        assertThat(RetroidFanConfig.isCustomMode(null)).isFalse()
    }

    @Test
    fun `odin and ayaneo readings keep dutyIsSetpoint false so they render as live duty`() {
        // Regression guard: the new flag must default false so Odin/AYANEO live-duty
        // semantics (and labels) are unchanged.
        val odin = LiveFanReading(dutyRaw = 10000, periodRaw = 50000, fanMode = 4)
        assertThat(odin.dutyIsSetpoint).isFalse()
        assertThat(odin.dutyPct).isEqualTo(20)
    }
}
