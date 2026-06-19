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
        ayaneoBinderLive: Boolean = false,
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
        ayaneoBinderLive = ayaneoBinderLive,
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
    fun `a non-handheld device is Unavailable even with PServer`() {
        // A generic device that is none of Odin/AYANEO/Retroid falls to the "none"
        // branch even when PServer is live (PServer doesn't grant a fan path on its own).
        val result = FanCurveGate.resolve(
            report("anbernic_rg556", pserverLive = true),
            odinSettingsInstalled = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
        assertThat((result as FanCurveAvailability.Unavailable).reason).contains("Odin")
    }

    @Test
    fun `settings package present WITH an AYN key qualifies (corroboration path)`() {
        // The Odin settings package is only accepted as corroboration when the
        // device ALSO reports an AYN handheld key — never package-presence alone.
        val result = FanCurveGate.resolve(
            report(handheldKey = "ayn_loki", pserverLive = true),
            odinSettingsInstalled = true,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
    }

    @Test
    fun `M1 - settings package on a NON-AYN device does NOT take the Odin path`() {
        // A sideloaded com.odin.settings on, say, a Retroid must NOT light up the ODIN
        // config.xml path (it would write hardcoded Odin-3 paths to the wrong device).
        // The Retroid resolves to its OWN binder path instead — and without a live
        // Retroid binder it is Unavailable with a Retroid-specific reason, never ODIN.
        val result = FanCurveGate.resolve(
            report(handheldKey = "retroid_pocket6", pserverLive = true),
            odinSettingsInstalled = true,
            retroidBinderLive = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
        assertThat((result as FanCurveAvailability.Unavailable).reason).contains("Retroid fan service")
        // The crux of M1: a sideloaded Odin settings pkg never grants the Odin path here.
        assertThat(FanCurveGate.isOdin(report("retroid_pocket6", pserverLive = true), odinSettingsInstalled = true)).isFalse()
    }

    @Test
    fun `M1 - settings package with a null handheld key does NOT qualify`() {
        // Package-presence alone (no device key at all) must not qualify.
        val result = FanCurveGate.resolve(
            report(handheldKey = null, pserverLive = true),
            odinSettingsInstalled = true,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
    }

    @Test
    fun `M1 - a non-Odin AYN model without the settings package is Unavailable`() {
        // startsWith("ayn") no longer qualifies on its own — a recognized Odin
        // key (or the corroboration path) is required.
        val result = FanCurveGate.resolve(
            report(handheldKey = "ayn_odin_lite", pserverLive = true),
            odinSettingsInstalled = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
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

    // ── AYANEO (binder path) ──────────────────────────────────────────────────

    @Test
    fun `AYANEO with a LIVE gamewindow binder is Available as AYANEO vendor`() {
        val result = FanCurveGate.resolve(
            report("ayaneo_pocket_ds", pserverLive = false, ayaneoBinderLive = true),
            odinSettingsInstalled = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.AYANEO)
    }

    @Test
    fun `AYANEO WITHOUT a live binder is Unavailable with a binder-specific reason`() {
        // An AYANEO device whose gamewindow binder isn't reachable (firmware
        // variant) is honestly unavailable — no setup can enable it.
        val result = FanCurveGate.resolve(
            report("ayaneo_pocket_ds", pserverLive = false, ayaneoBinderLive = false),
            odinSettingsInstalled = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
        assertThat((result as FanCurveAvailability.Unavailable).reason).contains("game-window")
    }

    @Test
    fun `AYANEO does NOT use the Odin config-xml path even with PServer live`() {
        // Even if PServer somehow reported live on an AYANEO, the vendor resolves
        // to AYANEO (binder), never ODIN — the config.xml path is Odin-only.
        val result = FanCurveGate.resolve(
            report("ayaneo_pocket_ds", pserverLive = true, ayaneoBinderLive = true),
            odinSettingsInstalled = false,
        )
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.AYANEO)
    }

    @Test
    fun `Odin stays the ODIN config-xml vendor even when an AYANEO binder flag is set`() {
        // An Odin device key resolves to ODIN first; the AYANEO binder flag is
        // irrelevant on an Odin (defensive — the two paths never cross).
        val result = FanCurveGate.resolve(
            report("ayn_odin3", pserverLive = true, ayaneoBinderLive = true),
            odinSettingsInstalled = true,
        )
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.ODIN)
    }

    @Test
    fun `a device that is neither Odin nor AYANEO nor Retroid is Unavailable`() {
        val result = FanCurveGate.resolve(
            report("anbernic_rg556", pserverLive = true, ayaneoBinderLive = false),
            odinSettingsInstalled = false,
            retroidBinderLive = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
    }

    // ── RETROID (SettingsController/FanProvider-binder path) ───────────────────

    @Test
    fun `Retroid with a LIVE FanProvider binder is Available as RETROID vendor`() {
        val result = FanCurveGate.resolve(
            report("retroid_pocket6", pserverLive = false),
            odinSettingsInstalled = false,
            retroidBinderLive = true,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Available::class.java)
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.RETROID)
    }

    @Test
    fun `Retroid WITHOUT a live binder is Unavailable with a binder-specific reason`() {
        // A Retroid whose FanProvider isn't reachable (passive model / firmware variant)
        // is honestly unavailable — no setup can enable it.
        val result = FanCurveGate.resolve(
            report("retroid_pocket6", pserverLive = false),
            odinSettingsInstalled = false,
            retroidBinderLive = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
        assertThat((result as FanCurveAvailability.Unavailable).reason).contains("Retroid fan service")
    }

    @Test
    fun `Retroid does NOT use the Odin config-xml path even with PServer live`() {
        // Even if PServer somehow reported live on a Retroid, the vendor resolves to
        // RETROID (binder), never ODIN — the config.xml path is Odin-only, and the M1
        // corroboration guard already blocks a sideloaded com.odin.settings here.
        val result = FanCurveGate.resolve(
            report("retroid_pocket6", pserverLive = true),
            odinSettingsInstalled = false,
            retroidBinderLive = true,
        )
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.RETROID)
    }

    @Test
    fun `a passive Retroid (Pocket 5) without a live binder is Unavailable`() {
        val result = FanCurveGate.resolve(
            report("retroid_pocket5", pserverLive = false),
            odinSettingsInstalled = false,
            retroidBinderLive = false,
        )
        assertThat(result).isInstanceOf(FanCurveAvailability.Unavailable::class.java)
    }

    @Test
    fun `Odin stays ODIN even when the Retroid binder flag is somehow set`() {
        // Defensive: an Odin device key resolves to ODIN first; a stray retroidBinderLive
        // is irrelevant on an Odin (the two paths never cross).
        val result = FanCurveGate.resolve(
            report("ayn_odin3", pserverLive = true),
            odinSettingsInstalled = true,
            retroidBinderLive = true,
        )
        assertThat((result as FanCurveAvailability.Available).vendor).isEqualTo(FanCurveVendor.ODIN)
    }

    @Test
    fun `isRetroid matches retroid keys and nothing else`() {
        assertThat(FanCurveGate.isRetroid(report("retroid_pocket6", pserverLive = false))).isTrue()
        assertThat(FanCurveGate.isRetroid(report("retroid_pocket5", pserverLive = false))).isTrue()
        assertThat(FanCurveGate.isRetroid(report("ayn_odin3", pserverLive = false))).isFalse()
        assertThat(FanCurveGate.isRetroid(report("ayaneo_pocket_ds", pserverLive = false))).isFalse()
        assertThat(FanCurveGate.isRetroid(report(null, pserverLive = false))).isFalse()
    }
}
