package io.github.mayusi.calibratesoc.ui.setup

/**
 * Pure, Android-free decision logic for WHEN to even offer "Force SELinux"
 * (SELinux Permissive) to the user.
 *
 * Force SELinux is a LAST RESORT, not a normal setup step. Enabling it puts
 * the whole device into SELinux Permissive mode, which:
 *   - breaks a lot of emulators / many apps (the primary use of these
 *     handhelds), and
 *   - weakens device security.
 *
 * It is only ever needed for ONE narrow write tier: the `chmod 666`
 * direct-write path from the unlock script. Every other live-tuning path —
 * AYN/Odin PServer, the AYANEO gamewindow binder, root — works perfectly on
 * Enforcing SELinux and needs NOTHING from Permissive. So the rule is simple:
 *
 *   Only offer Force SELinux when there is genuinely NO other way to do live
 *   tuning on this device. If ANY live path already exists (or could exist
 *   without Permissive), we must NOT push the user toward Permissive.
 *
 * This object is intentionally a plain Kotlin function over the already-probed
 * capability signals so it is hermetically unit-testable (no Compose, no
 * Android, no device).
 */
object ForceSelinuxGate {

    /**
     * The set of capability signals that decide whether Force SELinux is even
     * worth offering. Mirror of the relevant fields on
     * [io.github.mayusi.calibratesoc.data.capability.CapabilityReport] plus the
     * unlock-script grant — kept as a flat value type so callers can build it
     * from either source and tests don't need a full CapabilityReport.
     *
     * @param sysfsDirectlyWritable chmod-666 direct-write path is LIVE (the
     *   unlock script ran AND the kernel let the chmod stick). If this is
     *   already true, Force SELinux already did its only job — no need to ask.
     * @param pserverSysfsLive AYN/Odin PServer binder accepts our transacts
     *   (no-Permissive live tuning).
     * @param ayaneoBinderLive AYANEO gamewindow binder is bindable
     *   (no-Permissive, zero-setup live tuning).
     * @param isRoot the device is rooted AND the user opted into root mode
     *   (full live tuning, no Permissive needed).
     */
    data class Signals(
        val sysfsDirectlyWritable: Boolean,
        val pserverSysfsLive: Boolean,
        val ayaneoBinderLive: Boolean,
        val isRoot: Boolean,
    )

    /**
     * True when ANY live-tuning path is already available WITHOUT SELinux
     * Permissive. When this is true we must never push the user toward Force
     * SELinux — they already have (or can have) live tuning the safe way.
     */
    fun hasAnyNoPermissiveLivePath(s: Signals): Boolean =
        s.sysfsDirectlyWritable || s.pserverSysfsLive || s.ayaneoBinderLive || s.isRoot

    /**
     * The core gate: should the wizard offer the Force SELinux (last-resort)
     * step at all?
     *
     * Returns true ONLY in the genuine "chmod-direct is your only option" case:
     * no live path exists by any no-Permissive route. In every other case the
     * device can tune live without Permissive, so we return false and the
     * Force SELinux step is never shown.
     *
     * Note: this is deliberately the strict complement of
     * [hasAnyNoPermissiveLivePath] — if a single live path exists, do not offer.
     */
    fun shouldOfferForceSelinux(s: Signals): Boolean = !hasAnyNoPermissiveLivePath(s)
}
