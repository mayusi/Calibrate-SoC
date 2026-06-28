package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ChargingBundle] defaults and structural invariants.
 *
 * Rules under test:
 *   1. DEFAULT goal is COOL_QUIET (not EFFICIENCY, not BALANCED_SMART).
 *   2. DEFAULT fan mode is QUIET (preset 0).
 *   3. DEFAULT refresh rate is 60 Hz.
 *   4. Axes are independently nullable — fanMode null = don't touch fan.
 *   5. refreshRateHz null = don't touch display rate.
 *   6. Custom bundle preserves all provided values exactly.
 *   7. Data-class equality: two bundles with same fields are equal.
 *   8. Data-class copy: copy() overrides only the specified field.
 */
class ChargingBundleTest {

    // ── DEFAULT cool/quiet values ─────────────────────────────────────────────

    @Test
    fun `DEFAULT goal is COOL_QUIET`() {
        assertThat(ChargingBundle.DEFAULT.autoTdpGoal).isEqualTo(GoalProfile.COOL_QUIET)
    }

    @Test
    fun `DEFAULT goal is not BATTERY_SAVER`() {
        assertThat(ChargingBundle.DEFAULT.autoTdpGoal).isNotEqualTo(GoalProfile.BATTERY_SAVER)
    }

    @Test
    fun `DEFAULT goal is not BALANCED_SMART`() {
        assertThat(ChargingBundle.DEFAULT.autoTdpGoal).isNotEqualTo(GoalProfile.BALANCED_SMART)
    }

    @Test
    fun `DEFAULT fan mode is QUIET preset (0)`() {
        assertThat(ChargingBundle.DEFAULT.fanMode).isEqualTo(GoalProfile.FanPresets.QUIET)
        assertThat(ChargingBundle.DEFAULT.fanMode).isEqualTo(0)
    }

    @Test
    fun `DEFAULT refresh rate is 60 Hz`() {
        assertThat(ChargingBundle.DEFAULT.refreshRateHz).isEqualTo(60f)
    }

    // ── Nullable axes ─────────────────────────────────────────────────────────

    @Test
    fun `fanMode null means don't touch fan`() {
        val bundle = ChargingBundle(
            autoTdpGoal = GoalProfile.COOL_QUIET,
            fanMode = null,
            refreshRateHz = 60f,
        )
        assertThat(bundle.fanMode).isNull()
    }

    @Test
    fun `refreshRateHz null means don't touch display rate`() {
        val bundle = ChargingBundle(
            autoTdpGoal = GoalProfile.COOL_QUIET,
            fanMode = GoalProfile.FanPresets.QUIET,
            refreshRateHz = null,
        )
        assertThat(bundle.refreshRateHz).isNull()
    }

    @Test
    fun `both optional axes null is valid`() {
        val bundle = ChargingBundle(
            autoTdpGoal = GoalProfile.BATTERY_SAVER,
            fanMode = null,
            refreshRateHz = null,
        )
        assertThat(bundle.fanMode).isNull()
        assertThat(bundle.refreshRateHz).isNull()
        assertThat(bundle.autoTdpGoal).isEqualTo(GoalProfile.BATTERY_SAVER)
    }

    // ── Custom bundle preserves values ────────────────────────────────────────

    @Test
    fun `custom bundle with BATTERY_SAVER goal preserves goal`() {
        val bundle = ChargingBundle(autoTdpGoal = GoalProfile.BATTERY_SAVER)
        assertThat(bundle.autoTdpGoal).isEqualTo(GoalProfile.BATTERY_SAVER)
    }

    @Test
    fun `custom bundle with SPORT fan preserves fan mode`() {
        val bundle = ChargingBundle(fanMode = GoalProfile.FanPresets.SPORT)
        assertThat(bundle.fanMode).isEqualTo(GoalProfile.FanPresets.SPORT)
    }

    @Test
    fun `custom bundle with 90 Hz refresh preserves rate`() {
        val bundle = ChargingBundle(refreshRateHz = 90f)
        assertThat(bundle.refreshRateHz).isEqualTo(90f)
    }

    // ── Data class contract ───────────────────────────────────────────────────

    @Test
    fun `two identical bundles are equal`() {
        val a = ChargingBundle(GoalProfile.COOL_QUIET, GoalProfile.FanPresets.QUIET, 60f)
        val b = ChargingBundle(GoalProfile.COOL_QUIET, GoalProfile.FanPresets.QUIET, 60f)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `copy overrides only specified field`() {
        val original = ChargingBundle.DEFAULT
        val modified = original.copy(fanMode = null)
        assertThat(modified.autoTdpGoal).isEqualTo(original.autoTdpGoal)
        assertThat(modified.refreshRateHz).isEqualTo(original.refreshRateHz)
        assertThat(modified.fanMode).isNull()
    }

    @Test
    fun `DEFAULT matches explicit construction with same values`() {
        val explicit = ChargingBundle(
            autoTdpGoal = GoalProfile.COOL_QUIET,
            fanMode = GoalProfile.FanPresets.QUIET,
            refreshRateHz = 60f,
        )
        assertThat(ChargingBundle.DEFAULT).isEqualTo(explicit)
    }
}
