package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The duty-percent derivation used for the UI readout. */
class LiveFanReadingTest {

    @Test
    fun `20 percent fan derives correctly`() {
        // Documented: at 20% fan period=50000, duty=10000.
        val reading = LiveFanReading(dutyRaw = 10000, periodRaw = 50000, fanMode = 4)
        assertThat(reading.dutyPct).isEqualTo(20)
    }

    @Test
    fun `0 percent fan derives correctly`() {
        val reading = LiveFanReading(dutyRaw = 0, periodRaw = 50000, fanMode = 4)
        assertThat(reading.dutyPct).isEqualTo(0)
    }

    @Test
    fun `unreadable nodes yield null percent`() {
        assertThat(LiveFanReading(null, 50000, 4).dutyPct).isNull()
        assertThat(LiveFanReading(10000, null, 4).dutyPct).isNull()
        assertThat(LiveFanReading(10000, 0, 4).dutyPct).isNull()
    }

    @Test
    fun `percent is clamped to 0-100`() {
        assertThat(LiveFanReading(60000, 50000, 4).dutyPct).isEqualTo(100)
    }
}
