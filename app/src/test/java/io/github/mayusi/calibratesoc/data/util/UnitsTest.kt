package io.github.mayusi.calibratesoc.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnitsTest {

    // --- khzToMhz ---

    @Test
    fun `1000 kHz is 1 MHz`() {
        assertThat(1_000.khzToMhz()).isEqualTo(1)
    }

    @Test
    fun `1804800 kHz is 1804 MHz`() {
        assertThat(1_804_800.khzToMhz()).isEqualTo(1804)
    }

    @Test
    fun `0 kHz is 0 MHz`() {
        assertThat(0.khzToMhz()).isEqualTo(0)
    }

    @Test
    fun `khzToMhz truncates fractional part`() {
        // 1500 / 1000 = 1 (integer division, no rounding)
        assertThat(1_500.khzToMhz()).isEqualTo(1)
    }

    // --- hzToMhz ---

    @Test
    fun `1000000 Hz is 1 MHz`() {
        assertThat(1_000_000L.hzToMhz()).isEqualTo(1L)
    }

    @Test
    fun `845000000 Hz is 845 MHz`() {
        assertThat(845_000_000L.hzToMhz()).isEqualTo(845L)
    }

    @Test
    fun `0 Hz is 0 MHz`() {
        assertThat(0L.hzToMhz()).isEqualTo(0L)
    }

    @Test
    fun `hzToMhz truncates sub-MHz remainder`() {
        assertThat(1_500_000L.hzToMhz()).isEqualTo(1L)
    }

    // --- milliCToC ---

    @Test
    fun `1000 milliC is 1 C`() {
        assertThat(1_000.milliCToC()).isEqualTo(1.0f)
    }

    @Test
    fun `45000 milliC is 45 C`() {
        assertThat(45_000.milliCToC()).isEqualTo(45.0f)
    }

    @Test
    fun `0 milliC is 0 C`() {
        assertThat(0.milliCToC()).isEqualTo(0.0f)
    }

    @Test
    fun `milliCToC preserves fractional degrees`() {
        // 1500 / 1000f = 1.5 °C
        assertThat(1_500.milliCToC()).isEqualTo(1.5f)
    }
}
