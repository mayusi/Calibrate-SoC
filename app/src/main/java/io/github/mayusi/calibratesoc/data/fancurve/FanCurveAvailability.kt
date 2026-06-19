package io.github.mayusi.calibratesoc.data.fancurve

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * Whether the custom fan-curve feature can run on this device, and if not, an
 * honest reason. Pure decision so it is unit-testable without Android.
 *
 * The feature now spans TWO vendors, each with its OWN apply mechanism. The gate
 * resolves the RIGHT vendor per device (see [FanCurveGate.resolve]):
 *
 *  - [FanCurveVendor.ODIN] — a SPECIFIC AYN Odin model (`ayn_odin3` / `ayn_odin2`)
 *    whose fan-curve storage + reload procedure we have verified. The apply path
 *    writes HARDCODED Odin paths (`com.odin.settings` config.xml + the
 *    `/sys/class/gpio5_pwm2/` nodes) via a privileged shell, so it must NOT
 *    over-match: a loose `startsWith("ayn")` would let other AYN models through,
 *    and a sideloaded `com.odin.settings` alone could qualify a non-Odin device.
 *    We require a recognized Odin key; the settings package only corroborates a
 *    device that ALSO reports an AYN handheld key. ALSO requires a privileged
 *    write path: AYN PServer whitelisted ([CapabilityReport.pserverSysfsLive]) or
 *    real root.
 *
 *  - [FanCurveVendor.AYANEO] — an AYANEO model (`ayaneo_*`) whose
 *    `com.ayaneo.gamewindow` perf binder is LIVE ([CapabilityReport.ayaneoBinderLive],
 *    set ONLY after a real bind round-trip). The apply path sends a
 *    `com_set_fan_speed_strategy` command over that binder (ZERO-SETUP — no root,
 *    no script); the overlay (uid=system) actuates the PWM. NO config.xml is
 *    touched. Requires the binder to be live; "AYANEO device" alone is not enough
 *    (a firmware variant without the gamewindow service degrades honestly).
 *
 *  - [FanCurveVendor.RETROID] — a Retroid model (`retroid_*`) whose vendor fan
 *    binder is LIVE: the registered `SettingsController` service yields a
 *    `FanProvider` binder (verified on the RP6). The apply path sets a single
 *    CUSTOM fan SPEED over that binder (ZERO-SETUP — no root, no script). Unlike
 *    Odin/AYANEO this is NOT a temp-reactive curve: the governor HOLDS a fixed
 *    speed (the decompile showed no curve-array transaction), so the curve is
 *    mapped to one representative held speed and the UI is honest about that.
 *    The live-binder check is passed in as [retroidBinderLive] (the controller
 *    supplies [io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanBinderClient.isAvailable]),
 *    keeping this gate a pure function — exactly like [odinSettingsInstalled].
 *    "Retroid device" alone is not enough (a firmware without the FanProvider
 *    degrades honestly).
 *
 * On a device matching NONE of these paths → [Unavailable] with an honest reason.
 */
sealed interface FanCurveAvailability {
    /** Feature is usable; the controller may read + apply curves. */
    data class Available(val vendor: FanCurveVendor) : FanCurveAvailability

    /** Feature is hidden/disabled; [reason] is shown to the user verbatim. */
    data class Unavailable(val reason: String) : FanCurveAvailability

    val isAvailable: Boolean get() = this is Available
}

/**
 * Which vendor's fan-curve mechanism applies.
 *  - [ODIN]    — config.xml rewrite + fan_mode bounce via the privileged shell.
 *  - [AYANEO]  — `com_set_fan_speed_strategy` over the gamewindow binder (zero-setup).
 *  - [RETROID] — a single CUSTOM fan SPEED over the SettingsController/FanProvider
 *    binder (zero-setup). NOT a temp-reactive curve — a fixed held speed.
 */
enum class FanCurveVendor { ODIN, AYANEO, RETROID }

object FanCurveGate {

    /**
     * Resolve availability + the vendor to dispatch on, from the live [report]
     * plus a cheap check of whether the Odin settings package is installed
     * ([odinSettingsInstalled]) and whether the Retroid FanProvider binder is live
     * ([retroidBinderLive]). Both are passed in (the controller supplies the
     * `packageManager` result and the
     * [io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanBinderClient.isAvailable]
     * result) so this stays a PURE, unit-testable function.
     *
     * Resolution order:
     *   1. ODIN device (recognized key / corroboration) → require a privileged
     *      write path, then Available(ODIN). Odin stays the config.xml path.
     *   2. AYANEO device whose gamewindow binder is live → Available(AYANEO)
     *      (binder path). An AYANEO device WITHOUT the live binder is Unavailable
     *      with a binder-specific reason (honest: the zero-setup path isn't reachable).
     *   3. RETROID device whose FanProvider binder is live → Available(RETROID)
     *      (binder path, single custom SPEED). A Retroid device WITHOUT the live
     *      binder is Unavailable with a binder-specific reason.
     *   4. None → Unavailable.
     */
    fun resolve(
        report: CapabilityReport?,
        odinSettingsInstalled: Boolean,
        retroidBinderLive: Boolean = false,
    ): FanCurveAvailability {
        if (report == null) {
            return FanCurveAvailability.Unavailable("Still detecting device capabilities…")
        }

        // ── 1. ODIN (config.xml path) ───────────────────────────────────────
        if (isOdin(report, odinSettingsInstalled)) {
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

        // ── 2. AYANEO (gamewindow-binder path) ──────────────────────────────
        if (isAyaneo(report)) {
            if (!report.ayaneoBinderLive) {
                return FanCurveAvailability.Unavailable(
                    "Custom fan curves on AYANEO need the AYANEO game-window service, " +
                        "which isn't reachable on this device (it may be a firmware " +
                        "variant without it). No setup can enable it here.",
                )
            }
            return FanCurveAvailability.Available(FanCurveVendor.AYANEO)
        }

        // ── 3. RETROID (SettingsController/FanProvider-binder path) ─────────
        if (isRetroid(report)) {
            if (!retroidBinderLive) {
                return FanCurveAvailability.Unavailable(
                    "Custom fan control on Retroid needs the Retroid fan service " +
                        "(SettingsController/FanProvider), which isn't reachable on this " +
                        "device (it may be a passively-cooled model or a firmware variant " +
                        "without it). No setup can enable it here.",
                )
            }
            return FanCurveAvailability.Available(FanCurveVendor.RETROID)
        }

        // ── 4. None ─────────────────────────────────────────────────────────
        return FanCurveAvailability.Unavailable(
            "Custom fan control is available on AYN Odin, AYANEO, and Retroid handhelds. " +
                "This device controls its fan differently and isn't supported yet.",
        )
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

    /**
     * AYANEO detection for the fan-curve binder path. Any AYANEO handheld key
     * (`ayaneo_*`) qualifies as the DEVICE half; the gate ALSO requires
     * [CapabilityReport.ayaneoBinderLive] before reporting Available, so a loose
     * `startsWith("ayaneo")` here is safe — the live-bind requirement (not a
     * device-key allowlist) is what actually gates the apply. Unlike Odin, the
     * AYANEO apply touches no hardcoded per-model paths: it sends one binder
     * command and the overlay actuates the fan, so the same code works across the
     * AYANEO line wherever the gamewindow binder is reachable. A device whose
     * binder isn't live is rejected upstream with a binder-specific reason.
     */
    internal fun isAyaneo(report: CapabilityReport): Boolean =
        report.device.knownHandheldKey.orEmpty().startsWith("ayaneo")

    /**
     * Retroid detection for the FanProvider binder path. Any Retroid handheld key
     * (`retroid_*`) qualifies as the DEVICE half; the gate ALSO requires the live
     * binder (`retroidBinderLive`) before reporting Available, so a loose
     * `startsWith("retroid")` here is safe — the live-acquire requirement (not a
     * device-key allowlist) is what actually gates the apply. Like AYANEO and unlike
     * Odin, the Retroid apply touches no hardcoded per-model sysfs paths: it drives
     * the FanProvider binder, so the same code works across the Retroid line wherever
     * the SettingsController/FanProvider chain is reachable. Passively-cooled Retroids
     * (Pocket 4/5) simply never have a live FanProvider, so they degrade honestly
     * upstream with a binder-specific reason.
     */
    internal fun isRetroid(report: CapabilityReport): Boolean =
        report.device.knownHandheldKey.orEmpty().startsWith("retroid")
}
