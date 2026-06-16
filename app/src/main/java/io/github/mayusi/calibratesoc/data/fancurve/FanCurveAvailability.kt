package io.github.mayusi.calibratesoc.data.fancurve

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * Whether the custom fan-curve feature can run on this device, and if not, an
 * honest reason. Pure decision so it is unit-testable without Android.
 *
 * Two hard requirements (BOTH must hold):
 *   1. The device is a SPECIFIC AYN Odin model whose fan-curve storage + reload
 *      procedure we have verified (`ayn_odin3` / `ayn_odin2`). The apply path
 *      writes HARDCODED Odin-3 paths (`com.odin.settings` config.xml +
 *      the `/sys/class/gpio5_pwm2/` nodes), so we must NOT over-match: a loose
 *      `startsWith("ayn")` would let other AYN models (Loki, Odin Lite, future
 *      SKUs) through, and a sideloaded `com.odin.settings` alone could qualify a
 *      non-Odin device. We therefore require a recognized Odin key; the settings
 *      package is only accepted as corroboration on a device that ALSO reports an
 *      AYN handheld key (never package-presence by itself).
 *   2. A privileged write path is live: AYN PServer is whitelisted
 *      ([CapabilityReport.pserverSysfsLive]). Editing `com.odin.settings`'s
 *      private prefs and killing its process both require root, which on a
 *      non-rooted Odin is only reachable through PServer. (Real root counts too.)
 *
 * Architected so a future vendor adapter could supply a different
 * [FanCurveVendor]; today only [FanCurveVendor.ODIN] is implemented.
 */
sealed interface FanCurveAvailability {
    /** Feature is usable; the controller may read + apply curves. */
    data class Available(val vendor: FanCurveVendor) : FanCurveAvailability

    /** Feature is hidden/disabled; [reason] is shown to the user verbatim. */
    data class Unavailable(val reason: String) : FanCurveAvailability

    val isAvailable: Boolean get() = this is Available
}

/** Which vendor's fan-curve mechanism applies. Only ODIN is built today. */
enum class FanCurveVendor { ODIN }

object FanCurveGate {

    /**
     * Resolve availability from the live [report] plus a cheap check of whether
     * the Odin settings package is installed ([odinSettingsInstalled] — the
     * controller passes `packageManager` result; kept as a param so this stays
     * a pure function).
     */
    fun resolve(
        report: CapabilityReport?,
        odinSettingsInstalled: Boolean,
    ): FanCurveAvailability {
        if (report == null) {
            return FanCurveAvailability.Unavailable("Still detecting device capabilities…")
        }

        if (!isOdin(report, odinSettingsInstalled)) {
            return FanCurveAvailability.Unavailable(
                "Custom fan curves are an AYN Odin feature. This device stores its " +
                    "fan curve differently and isn't supported yet.",
            )
        }

        // Privileged write path: PServer whitelisted, OR real root.
        val privileged = report.pserverSysfsLive ||
            report.privilege == io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT
        if (!privileged) {
            return FanCurveAvailability.Unavailable(
                "Writing the Odin fan curve needs a privileged write path. Run the " +
                    "one-time unlock (PServer) so Calibrate can edit the curve, then " +
                    "come back.",
            )
        }

        return FanCurveAvailability.Available(FanCurveVendor.ODIN)
    }

    /**
     * The specific AYN Odin device keys whose fan-curve schema + node paths this
     * feature targets. Verified live on the Odin 3; the Odin 2 shares the same
     * `com.odin.settings` config.xml + `fan_mode` mechanism. Deliberately an
     * allowlist, NOT a `startsWith("ayn")` prefix — other AYN SKUs may store the
     * curve differently or lack the gpio5_pwm2 node.
     */
    private val ODIN_DEVICE_KEYS = setOf("ayn_odin3", "ayn_odin2")

    /**
     * Odin detection (tightened — see class doc, bug M1). A recognized Odin
     * device key is required; the Odin settings package being installed only
     * counts when the device ALSO reports an AYN handheld key (so a sideloaded
     * `com.odin.settings` on a non-AYN device can never qualify, and AYN models
     * we don't target are excluded).
     */
    internal fun isOdin(report: CapabilityReport, odinSettingsInstalled: Boolean): Boolean {
        val key = report.device.knownHandheldKey.orEmpty()
        if (key in ODIN_DEVICE_KEYS) return true
        // Corroboration path: the Odin settings package is present AND the device
        // is at least an AYN handheld. Never package-presence alone.
        return odinSettingsInstalled && key.startsWith("ayn")
    }
}
