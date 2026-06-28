package io.github.mayusi.calibratesoc.ui.capability

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [CapabilityUi] extension functions on [CapabilityReport].
 *
 * [CapabilityReport] is a large data class — mockk(relaxed=true) + privilege stub
 * keeps tests free of boilerplate field construction while still exercising the
 * real function logic.
 */
class CapabilityUiTest {

    private fun reportWith(tier: PrivilegeTier, pserverLive: Boolean = false): CapabilityReport =
        mockk(relaxed = true) {
            every { privilege } returns tier
            every { pserverSysfsLive } returns pserverLive
        }

    // --- tierAccent ---

    @Test
    fun `tierAccent returns Emerald for ROOT`() {
        val report = reportWith(PrivilegeTier.ROOT)
        assertThat(report.tierAccent()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `tierAccent returns Emerald for VENDOR_SETTINGS`() {
        val report = reportWith(PrivilegeTier.VENDOR_SETTINGS)
        assertThat(report.tierAccent()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `tierAccent returns Blue for SHIZUKU`() {
        val report = reportWith(PrivilegeTier.SHIZUKU)
        assertThat(report.tierAccent()).isEqualTo(AccentBar.Blue)
    }

    @Test
    fun `tierAccent returns Neutral for NONE`() {
        val report = reportWith(PrivilegeTier.NONE)
        assertThat(report.tierAccent()).isEqualTo(AccentBar.Neutral)
    }

    // --- explainerColor ---

    @Test
    fun `explainerColor returns Emerald when pserverSysfsLive`() {
        val report = reportWith(PrivilegeTier.NONE, pserverLive = true)
        assertThat(report.explainerColor()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `explainerColor returns grey for ROOT tier without pserver`() {
        val report = reportWith(PrivilegeTier.ROOT)
        assertThat(report.explainerColor()).isEqualTo(Color(0xFF999999))
    }

    @Test
    fun `explainerColor returns grey for VENDOR_SETTINGS tier without pserver`() {
        val report = reportWith(PrivilegeTier.VENDOR_SETTINGS)
        assertThat(report.explainerColor()).isEqualTo(Color(0xFF999999))
    }

    @Test
    fun `explainerColor returns Blue for NONE tier without pserver`() {
        val report = reportWith(PrivilegeTier.NONE)
        assertThat(report.explainerColor()).isEqualTo(AccentBar.Blue)
    }

    @Test
    fun `explainerColor pserverSysfsLive takes priority over ROOT tier`() {
        // pserver live should win regardless of privilege tier
        val report = reportWith(PrivilegeTier.ROOT, pserverLive = true)
        assertThat(report.explainerColor()).isEqualTo(AccentBar.Emerald)
    }
}
