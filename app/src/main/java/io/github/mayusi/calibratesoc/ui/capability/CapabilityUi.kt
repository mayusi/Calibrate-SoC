package io.github.mayusi.calibratesoc.ui.capability

import androidx.compose.ui.graphics.Color
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.vendor.VendorBrand
import io.github.mayusi.calibratesoc.ui.components.AccentBar

/**
 * UI mappings from [CapabilityReport] / [PrivilegeTier] to display values.
 * Extracted to eliminate duplication across TuneScreen and DashboardScreen.
 */

/** Accent colour for the privilege tier chip. */
fun CapabilityReport.tierAccent(): Color = when (privilege) {
    PrivilegeTier.ROOT -> AccentBar.Emerald
    PrivilegeTier.VENDOR_SETTINGS -> AccentBar.Emerald
    PrivilegeTier.SHIZUKU -> AccentBar.Blue
    PrivilegeTier.NONE -> AccentBar.Neutral
}

/** Display label for the privilege tier chip. */
fun CapabilityReport.chipLabel(vb: VendorBrand): String = when (privilege) {
    PrivilegeTier.VENDOR_SETTINGS -> vb.tierLabel
    else -> privilege.name
}

/**
 * Single-line explainer text shown below the tier chip.
 *
 * PServer-LIVE is the strongest path and is NOT one of the [PrivilegeTier]
 * values — it's a cross-vendor root runner (AYN Odin + Retroid RP6 confirmed)
 * that becomes available whenever transact() round-trips. When it's live,
 * custom MHz / GPU / governor writes apply DIRECTLY — no script, no vendor
 * round-trip — so it must take precedence over the tier-based copy below.
 */
fun CapabilityReport.explainerText(vb: VendorBrand): String = when {
    pserverSysfsLive ->
        "PServer live — Apply works for everything directly (custom CPU/GPU MHz, governors, DDR), no script and no reboot needed."
    privilege == PrivilegeTier.ROOT ->
        "Magisk/KernelSU detected. Direct sysfs writes available — Apply works for everything."
    privilege == PrivilegeTier.VENDOR_SETTINGS ->
        "${vb.brand} tier active. Vendor tuning is owned by the firmware. For custom MHz caps, generate a script and run it via ${vb.settingsApp} → Run script as Root."
    privilege == PrivilegeTier.SHIZUKU ->
        "Shizuku bound. Custom MHz needs root or the script path. Vendor tuning pending UserService support."
    else ->
        "Read-only tier. Generate a script for custom MHz caps, or grant WRITE_SECURE_SETTINGS via adb to unlock vendor tunes:\nadb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS"
}

/** Colour for the explainer text. */
fun CapabilityReport.explainerColor(): Color = when {
    pserverSysfsLive -> AccentBar.Emerald
    privilege == PrivilegeTier.ROOT || privilege == PrivilegeTier.VENDOR_SETTINGS -> Color(0xFF999999)
    else -> AccentBar.Blue
}
