package io.github.mayusi.calibratesoc.data.fancurve

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * Whether the custom fan-curve feature can run on this device, and if not, an
 * honest reason. Pure decision so it is unit-testable without Android.
 *
 * Two hard requirements (BOTH must hold):
 *   1. The device is an AYN Odin (the curve storage + reload procedure is
 *      Odin-specific). We detect this via the existing handheld key
 *      (`ayn_odin3` / `ayn_odin2` / any `ayn*`) — the same signal the rest of
 *      the app uses — falling back to the presence of `com.odin.settings`.
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

    /** Odin detection: handheld key OR the Odin settings package being present. */
    internal fun isOdin(report: CapabilityReport, odinSettingsInstalled: Boolean): Boolean {
        val key = report.device.knownHandheldKey.orEmpty()
        val keyMatch = key == "ayn_odin3" || key == "ayn_odin2" || key.startsWith("ayn")
        return keyMatch || odinSettingsInstalled
    }
}
