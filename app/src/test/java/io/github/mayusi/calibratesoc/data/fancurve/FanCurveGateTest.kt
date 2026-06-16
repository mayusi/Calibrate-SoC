package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import org.junit.Test

/**
 * Feature-gating: the custom fan-curve feature is available ONLY on an Odin
 * (the storage + reload procedure is Odin-specific) AND only when a privileged
 * write path is live (PServer whitelisted, or root). Anything else is honestly
 * Unavailable with a reason.
 */
class FanCurveGateTest {

    private fun report(
        handheldKey: String?,
        pserverLive: Boolean,
        tier: PrivilegeTier = PrivilegeTier.VENDOR_SETTINGS,
    ): CapabilityReport = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYN", brand = "AYN", model = "Odin3",
            device = "odin3", hardware = "pineapple",
            androidVersion = "14", sdkInt = 34, knownHandheldKey = handheldKey,
        ),
        soc = SoCIdentity("qualcomm", "CQ8725S", GpuFamily.ADRENO),
        privilege = tier,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = emptyList(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
        pserverSysfsLive = pserverLive,
    )

    @Test
    fun `Odin with live PServer is Available`() {
        val result = FanCurveGate.resolve(report("ayn_odin3", pserverLive = true), odinSettingsInstalled = true)
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.ODIN)
    }

    @Test
    fun `Odin with real root is Available even without PServer`() {
        val result = FanCurveGate.resolve(
            report("ayn_odin3", pserverLive = false, tier = PrivilegeTier.ROOT),
            odinSettingsInstalled = true,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
    }

    @Test
    fun `Odin WITHOUT a privileged write path is Unavailable`() {
        val result = FanCurveGate.resolve(report("ayn_odin3", pserverLive = false), odinSettingsInstalled = true)
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
        assertThat((result as FanCurveAvailability.Unavailable).reason).contains("privileged write path")
    }

    @Test
    fun `a non-Odin device is Unavailable even with PServer`() {
        val result = FanCurveGate.resolve(
            report("retroid_pocket6", pserverLive = true),
            odinSettingsInstalled = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
        assertThat((result as FanCurveAvailability.Unavailable).reason).contains("Odin")
    }

    @Test
    fun `Odin detected via the settings package even when the handheld key is null`() {
        val result = FanCurveGate.resolve(
            report(handheldKey = null, pserverLive = true),
            odinSettingsInstalled = true,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
    }

    @Test
    fun `a null report is Unavailable`() {
        val result = FanCurveGate.resolve(null, odinSettingsInstalled = true)
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
    }

    @Test
    fun `Odin 2 is recognized`() {
        val result = FanCurveGate.resolve(report("ayn_odin2", pserverLive = true), odinSettingsInstalled = false)
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
    }
}
