package io.github.mayusi.calibratesoc.data.hardware

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatteryDesignSpecsTest {

    @Test
    fun `Odin3 returns 7838 mAh`() {
        assertThat(BatteryDesignSpecs.lookupByModel("Odin3")).isEqualTo(7838)
    }

    @Test
    fun `Odin3 case-insensitive match`() {
        assertThat(BatteryDesignSpecs.lookupByModel("odin3")).isEqualTo(7838)
        assertThat(BatteryDesignSpecs.lookupByModel("ODIN3")).isEqualTo(7838)
    }

    @Test
    fun `Retroid Pocket 6 returns 6442 mAh`() {
        assertThat(BatteryDesignSpecs.lookupByModel("Retroid Pocket 6")).isEqualTo(6442)
    }

    @Test
    fun `Retroid Pocket 6 partial match`() {
        // Build.MODEL on RP6 may vary slightly; contains() match should work.
        assertThat(BatteryDesignSpecs.lookupByModel("Pocket 6 Pro")).isEqualTo(6442)
    }

    @Test
    fun `unknown device returns null`() {
        assertThat(BatteryDesignSpecs.lookupByModel("Galaxy S99")).isNull()
        assertThat(BatteryDesignSpecs.lookupByModel("")).isNull()
        assertThat(BatteryDesignSpecs.lookupByModel("Pixel 9")).isNull()
    }

    @Test
    fun `AYN Thor returns null - no verified value`() {
        // AYN Thor design capacity is unverified — must NOT return a guessed value.
        assertThat(BatteryDesignSpecs.lookupByModel("AYN Thor")).isNull()
        assertThat(BatteryDesignSpecs.lookupByModel("Thor")).isNull()
    }
}
