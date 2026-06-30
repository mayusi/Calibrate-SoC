package io.github.mayusi.calibratesoc.data.hardware

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-logic tests for [HardwareScanner.describeWifi] — the honest Wi-Fi description
 * the Radios card renders. The core contract: NEVER a bare "—" when Wi-Fi is connected.
 */
class WifiDescribeTest {

    @Test
    fun `known standard renders the standard name`() {
        assertThat(
            HardwareScanner.describeWifi(
                standard = 6, wifiConnected = true, linkSpeedMbps = null, hasWifiHardware = true,
            ),
        ).isEqualTo("Wi-Fi 6 (802.11ax)")
    }

    @Test
    fun `known standard while connected appends link speed`() {
        assertThat(
            HardwareScanner.describeWifi(
                standard = 5, wifiConnected = true, linkSpeedMbps = 866, hasWifiHardware = true,
            ),
        ).isEqualTo("Wi-Fi 5 (802.11ac) · 866 Mbps")
    }

    @Test
    fun `connected but unknown standard with link speed shows Connected plus speed not a dash`() {
        // The RP6 live bug: connected + active traffic but wifiStandard came back unknown.
        val result = HardwareScanner.describeWifi(
            standard = 0, wifiConnected = true, linkSpeedMbps = 433, hasWifiHardware = true,
        )
        assertThat(result).isEqualTo("Connected · 433 Mbps")
        assertThat(result).doesNotContain("—")
    }

    @Test
    fun `connected but unknown standard and no link speed shows Connected not a dash`() {
        val result = HardwareScanner.describeWifi(
            standard = null, wifiConnected = true, linkSpeedMbps = null, hasWifiHardware = true,
        )
        assertThat(result).isEqualTo("Connected")
        assertThat(result).isNotEqualTo("—")
    }

    @Test
    fun `not connected but has wifi hardware shows Not connected`() {
        assertThat(
            HardwareScanner.describeWifi(
                standard = null, wifiConnected = false, linkSpeedMbps = null, hasWifiHardware = true,
            ),
        ).isEqualTo("Not connected")
    }

    @Test
    fun `no wifi hardware at all shows dash honestly`() {
        assertThat(
            HardwareScanner.describeWifi(
                standard = null, wifiConnected = false, linkSpeedMbps = null, hasWifiHardware = false,
            ),
        ).isEqualTo("—")
    }

    @Test
    fun `never shows a dash whenever wifi is connected`() {
        // Sweep the unknown/edge standards while connected — none may render "—".
        for (std in listOf(null, 0, 1, 2, 3, 99)) {
            val result = HardwareScanner.describeWifi(
                standard = std, wifiConnected = true, linkSpeedMbps = null, hasWifiHardware = true,
            )
            assertThat(result).isNotEqualTo("—")
        }
    }
}
