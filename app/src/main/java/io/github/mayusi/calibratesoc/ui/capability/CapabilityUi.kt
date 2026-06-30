package io.github.mayusi.calibratesoc.ui.capability

import androidx.compose.ui.graphics.Color
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.vendor.VendorBrand
import io.github.mayusi.calibratesoc.ui.components.AccentBar

/**
 * UI mappings from [CapabilityReport] / [PrivilegeTier] to display values.
 * Extracted to eliminate duplication across TuneScreen and DashboardScreen.
 *
 * LIVE-FLAG PRECEDENCE (the central fix)
 * --------------------------------------
 * The [PrivilegeTier] enum is NOT the signal that decides whether live tuning
 * actually works. The real signals are the ORTHOGONAL live-write flags —
 * [CapabilityReport.pserverSysfsLive], [CapabilityReport.ayaneoBinderLive],
 * [CapabilityReport.sysfsDirectlyWritable], and the ROOT tier — any of which can
 * make Apply work DIRECTLY while `privilege` is still NONE or VENDOR_SETTINGS
 * (e.g. a zero-setup AYANEO Pocket DS: ayaneoBinderLive=true, privilege possibly
 * NONE). Keying the chip/accent/explainer only on `privilege` therefore showed
 * "NONE" / read-only copy on devices that tune live.
 *
 * The whole app already gates "is live tuning active" on this OR of flags
 * (TuneScreen / ProfilesScreen `bootIsAutomatic`, OnboardingScreen
 * `liveAlreadyActive`). [liveTuningActive] mirrors that exact precedence so the
 * tier display is consistent with the rest of the app — and HONEST: a "live"
 * branch is only ever taken when one of those flags is genuinely set.
 */

/**
 * True when a REAL live-write path is active on this device — the same predicate
 * the rest of the app uses to decide that Apply / boot-reapply work with no
 * script and no reboot (see TuneScreen/ProfilesScreen `bootIsAutomatic`,
 * OnboardingScreen `liveAlreadyActive`).
 *
 * HONESTY: every term here is a probe-confirmed live signal — never inferred from
 * the privilege enum alone.
 */
fun CapabilityReport.liveTuningActive(): Boolean =
    pserverSysfsLive ||
        ayaneoBinderLive ||
        privilege == PrivilegeTier.ROOT ||
        sysfsDirectlyWritable

/** Accent colour for the privilege tier chip. */
fun CapabilityReport.tierAccent(): Color = when {
    // Any live-write path → the device tunes live → Emerald, regardless of the
    // privilege enum (covers zero-setup AYANEO binder + chmod-direct where
    // privilege can still read NONE/VENDOR_SETTINGS).
    liveTuningActive() -> AccentBar.Emerald
    privilege == PrivilegeTier.SHIZUKU -> AccentBar.Blue
    privilege == PrivilegeTier.VENDOR_SETTINGS -> AccentBar.Emerald
    else -> AccentBar.Neutral
}

/** Display label for the privilege tier chip. */
fun CapabilityReport.chipLabel(vb: VendorBrand): String = when {
    // PServer live (cross-vendor root runner): the strongest path, NOT a
    // PrivilegeTier value — label it honestly as the live tier.
    pserverSysfsLive -> "${vb.brand} LIVE"
    // AYANEO zero-setup vendor binder is live → branded "live" label, not "NONE".
    ayaneoBinderLive -> "${vb.brand} LIVE"
    // Real root, or the unlock-script chmod-direct path is active → LIVE.
    privilege == PrivilegeTier.ROOT -> "ROOT"
    sysfsDirectlyWritable -> "${vb.brand} LIVE"
    privilege == PrivilegeTier.VENDOR_SETTINGS -> vb.tierLabel
    else -> privilege.name
}

/**
 * Single-line explainer text shown below the tier chip.
 *
 * The LIVE branches come FIRST (see [liveTuningActive]) so a device that tunes
 * live never falls through to read-only "grant via adb" copy that contradicts
 * its real, working write path. Order is most-specific-live-path first.
 *
 * PServer-LIVE is the strongest path and is NOT one of the [PrivilegeTier]
 * values — it's a cross-vendor root runner (AYN Odin + Retroid RP6 confirmed)
 * that becomes available whenever transact() round-trips. AYANEO's vendor binder
 * ([CapabilityReport.ayaneoBinderLive]) is the zero-setup analog. Both apply
 * custom MHz / GPU / governor writes DIRECTLY, so both must take precedence over
 * the tier-based copy below.
 */
fun CapabilityReport.explainerText(vb: VendorBrand): String = when {
    pserverSysfsLive ->
        "PServer live — Apply works for everything directly (custom CPU/GPU MHz, governors, DDR), no script and no reboot needed."
    ayaneoBinderLive ->
        "${vb.brand} live — tuning applies through the ${vb.brand} system service, no root, no script, no reboot. Live CPU/GPU MHz caps, governor, and fan all work right now. (Core parking isn't on the binder path — the tuner uses the cap lever instead, so AutoTDP runs live.)"
    privilege == PrivilegeTier.ROOT ->
        "Magisk/KernelSU detected. Direct sysfs writes available — Apply works for everything."
    sysfsDirectlyWritable ->
        "Live tuning active — the unlock script has chmod'd the cpufreq nodes, so Apply writes custom MHz caps directly (no root, no reboot)."
    privilege == PrivilegeTier.VENDOR_SETTINGS ->
        "${vb.brand} tier active. Vendor tuning is owned by the firmware. For custom MHz caps, generate a script and run it via ${vb.settingsApp} → Run script as Root."
    privilege == PrivilegeTier.SHIZUKU ->
        "Shizuku bound. Custom MHz needs root or the script path. Vendor tuning pending UserService support."
    else ->
        "Read-only tier. Generate a script for custom MHz caps, or grant WRITE_SECURE_SETTINGS via adb to unlock vendor tunes:\nadb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS"
}

/** Colour for the explainer text. */
fun CapabilityReport.explainerColor(): Color = when {
    // Live path (PServer / AYANEO binder / root / chmod-direct) → Emerald.
    liveTuningActive() -> AccentBar.Emerald
    privilege == PrivilegeTier.VENDOR_SETTINGS -> Color(0xFF999999)
    else -> AccentBar.Blue
}
