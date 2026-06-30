package io.github.mayusi.calibratesoc.ui.capability

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.vendor.VendorBrand
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [CapabilityUi] extension functions on [CapabilityReport].
 *
 * [CapabilityReport] is a large data class — mockk(relaxed=true) + flag stubs
 * keeps tests free of boilerplate field construction while still exercising the
 * real function logic.
 *
 * The central contract under test is the LIVE-FLAG PRECEDENCE: the tier chip /
 * accent / explainer must reflect the orthogonal live-write flags
 * (pserverSysfsLive / ayaneoBinderLive / sysfsDirectlyWritable / ROOT) BEFORE
 * the privilege enum — so a zero-setup AYANEO (ayaneoBinderLive=true, privilege
 * NONE) shows a live "AYANEO" branch, not "NONE"/read-only copy. HONESTY: a live
 * branch is only taken when a live flag is genuinely set.
 */
class CapabilityUiTest {

    private fun reportWith(
        tier: PrivilegeTier,
        pserverLive: Boolean = false,
        ayaneoBinderLive: Boolean = false,
        sysfsDirectlyWritable: Boolean = false,
    ): CapabilityReport =
        mockk(relaxed = true) {
            every { privilege } returns tier
            every { pserverSysfsLive } returns pserverLive
            every { this@mockk.ayaneoBinderLive } returns ayaneoBinderLive
            every { this@mockk.sysfsDirectlyWritable } returns sysfsDirectlyWritable
        }

    // --- liveTuningActive (the central predicate) ---

    @Test
    fun `liveTuningActive true for pserver`() {
        assertThat(reportWith(PrivilegeTier.NONE, pserverLive = true).liveTuningActive()).isTrue()
    }

    @Test
    fun `liveTuningActive true for ayaneo binder even when privilege NONE`() {
        assertThat(
            reportWith(PrivilegeTier.NONE, ayaneoBinderLive = true).liveTuningActive(),
        ).isTrue()
    }

    @Test
    fun `liveTuningActive true for ROOT`() {
        assertThat(reportWith(PrivilegeTier.ROOT).liveTuningActive()).isTrue()
    }

    @Test
    fun `liveTuningActive true for direct sysfs writable`() {
        assertThat(
            reportWith(PrivilegeTier.NONE, sysfsDirectlyWritable = true).liveTuningActive(),
        ).isTrue()
    }

    @Test
    fun `liveTuningActive false for plain NONE with no flags`() {
        assertThat(reportWith(PrivilegeTier.NONE).liveTuningActive()).isFalse()
    }

    @Test
    fun `liveTuningActive false for VENDOR_SETTINGS with no live flags`() {
        // Vendor preset keys writable is NOT a live cpufreq path.
        assertThat(reportWith(PrivilegeTier.VENDOR_SETTINGS).liveTuningActive()).isFalse()
    }

    // --- tierAccent ---

    @Test
    fun `tierAccent returns Emerald for ROOT`() {
        assertThat(reportWith(PrivilegeTier.ROOT).tierAccent()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `tierAccent returns Emerald for VENDOR_SETTINGS`() {
        assertThat(reportWith(PrivilegeTier.VENDOR_SETTINGS).tierAccent())
            .isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `tierAccent returns Emerald for AYANEO binder live even when privilege NONE`() {
        // The headline regression: zero-setup AYANEO showed Neutral before the fix.
        assertThat(reportWith(PrivilegeTier.NONE, ayaneoBinderLive = true).tierAccent())
            .isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `tierAccent returns Emerald for pserver live even when privilege NONE`() {
        assertThat(reportWith(PrivilegeTier.NONE, pserverLive = true).tierAccent())
            .isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `tierAccent returns Blue for SHIZUKU`() {
        assertThat(reportWith(PrivilegeTier.SHIZUKU).tierAccent()).isEqualTo(AccentBar.Blue)
    }

    @Test
    fun `tierAccent returns Neutral for NONE with no live flags`() {
        assertThat(reportWith(PrivilegeTier.NONE).tierAccent()).isEqualTo(AccentBar.Neutral)
    }

    // --- chipLabel ---

    @Test
    fun `chipLabel returns branded LIVE for AYANEO binder live when privilege NONE`() {
        val report = reportWith(PrivilegeTier.NONE, ayaneoBinderLive = true)
        assertThat(report.chipLabel(VendorBrand.AYANEO)).isEqualTo("AYANEO LIVE")
    }

    @Test
    fun `chipLabel returns branded LIVE for pserver live`() {
        val report = reportWith(PrivilegeTier.VENDOR_SETTINGS, pserverLive = true)
        assertThat(report.chipLabel(VendorBrand.RETROID)).isEqualTo("Retroid LIVE")
    }

    @Test
    fun `chipLabel returns ROOT for ROOT tier`() {
        assertThat(reportWith(PrivilegeTier.ROOT).chipLabel(VendorBrand.GENERIC)).isEqualTo("ROOT")
    }

    @Test
    fun `chipLabel returns vendor tier label for VENDOR_SETTINGS without live flags`() {
        val report = reportWith(PrivilegeTier.VENDOR_SETTINGS)
        assertThat(report.chipLabel(VendorBrand.AYN)).isEqualTo(VendorBrand.AYN.tierLabel)
    }

    @Test
    fun `chipLabel returns raw enum name for plain NONE`() {
        assertThat(reportWith(PrivilegeTier.NONE).chipLabel(VendorBrand.GENERIC)).isEqualTo("NONE")
    }

    // --- explainerText ---

    @Test
    fun `explainerText for AYANEO binder is live copy not read-only adb copy`() {
        val report = reportWith(PrivilegeTier.NONE, ayaneoBinderLive = true)
        val text = report.explainerText(VendorBrand.AYANEO)
        // Must be the honest LIVE branch, never the read-only "grant via adb" copy.
        assertThat(text).contains("live")
        assertThat(text).doesNotContain("adb shell pm grant")
        assertThat(text).doesNotContain("Read-only")
    }

    @Test
    fun `explainerText for pserver live is pserver live copy`() {
        val report = reportWith(PrivilegeTier.NONE, pserverLive = true)
        assertThat(report.explainerText(VendorBrand.RETROID)).contains("PServer live")
    }

    @Test
    fun `explainerText for ROOT is root copy`() {
        assertThat(reportWith(PrivilegeTier.ROOT).explainerText(VendorBrand.GENERIC))
            .contains("Magisk/KernelSU")
    }

    @Test
    fun `explainerText for plain NONE is read-only copy`() {
        val text = reportWith(PrivilegeTier.NONE).explainerText(VendorBrand.GENERIC)
        assertThat(text).contains("Read-only tier")
        assertThat(text).contains("adb shell pm grant")
    }

    // --- explainerColor ---

    @Test
    fun `explainerColor returns Emerald when pserverSysfsLive`() {
        val report = reportWith(PrivilegeTier.NONE, pserverLive = true)
        assertThat(report.explainerColor()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `explainerColor returns Emerald when ayaneoBinderLive even at NONE tier`() {
        val report = reportWith(PrivilegeTier.NONE, ayaneoBinderLive = true)
        assertThat(report.explainerColor()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `explainerColor returns Emerald for ROOT tier (live path)`() {
        // ROOT is a live write path → Emerald (was grey before the live-flag fix).
        assertThat(reportWith(PrivilegeTier.ROOT).explainerColor()).isEqualTo(AccentBar.Emerald)
    }

    @Test
    fun `explainerColor returns grey for VENDOR_SETTINGS tier without live flags`() {
        assertThat(reportWith(PrivilegeTier.VENDOR_SETTINGS).explainerColor())
            .isEqualTo(Color(0xFF999999))
    }

    @Test
    fun `explainerColor returns Blue for NONE tier without live flags`() {
        assertThat(reportWith(PrivilegeTier.NONE).explainerColor()).isEqualTo(AccentBar.Blue)
    }

    @Test
    fun `explainerColor pserverSysfsLive takes priority over tier`() {
        val report = reportWith(PrivilegeTier.VENDOR_SETTINGS, pserverLive = true)
        assertThat(report.explainerColor()).isEqualTo(AccentBar.Emerald)
    }
}
