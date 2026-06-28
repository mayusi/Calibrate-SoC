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

    // --- hzToMhzInt ---

    @Test
    fun `1_000_000 Hz is 1 MHz as Int`() {
        assertThat(1_000_000L.hzToMhzInt()).isEqualTo(1)
    }

    @Test
    fun `845_000_000 Hz is 845 MHz as Int`() {
        assertThat(845_000_000L.hzToMhzInt()).isEqualTo(845)
    }

    @Test
    fun `0 Hz is 0 MHz as Int`() {
        assertThat(0L.hzToMhzInt()).isEqualTo(0)
    }

    @Test
    fun `hzToMhzInt truncates sub-MHz remainder`() {
        assertThat(1_500_000L.hzToMhzInt()).isEqualTo(1)
    }

    // --- mwFromUaUv ---

    @Test
    fun `mwFromUaUv returns correct milliwatts for typical battery draw`() {
        // 2000 mA = 2_000_000 µA, 3.7 V = 3_700_000 µV → 7.4 W = 7400 mW
        assertThat(2_000_000L.mwFromUaUv(3_700_000L)).isEqualTo(7400L)
    }

    @Test
    fun `mwFromUaUv returns 0 when current is 0`() {
        assertThat(0L.mwFromUaUv(3_700_000L)).isEqualTo(0L)
    }

    @Test
    fun `mwFromUaUv returns 0 when voltage is 0`() {
        assertThat(2_000_000L.mwFromUaUv(0L)).isEqualTo(0L)
    }

    @Test
    fun `mwFromUaUv matches the inline formula it replaces`() {
        val absUa = 1_500_000L
        val uv = 4_200_000L
        val expected = (absUa * uv) / 1_000_000_000L
        assertThat(absUa.mwFromUaUv(uv)).isEqualTo(expected)
    }
}
