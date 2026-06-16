package io.github.mayusi.calibratesoc.data.boot

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier

/**
 * The three possible outcomes when deciding how to honour an
 * [io.github.mayusi.calibratesoc.data.profiles.UserProfile.applyOnBoot] flag at boot.
 *
 * Used by [BootRevertReceiver] to decide:
 *   - [AUTO]      → re-apply the tune immediately in the background (live write tier
 *                   confirmed available).
 *   - [REMINDER]  → post a "Tap to re-apply" notification with a deep-link into the
 *                   app (only script/unlock tier is available; live writes need the app
 *                   to re-run the apply flow).
 *   - [UNSUPPORTED] → do nothing silently (no write tier available at all — monitoring-
 *                   only or non-root stock device with no unlock script). Posting a
 *                   reminder here would be dishonest (the app still can't write anything).
 */
enum class BootApplyMode { AUTO, REMINDER, UNSUPPORTED }

/**
 * Pure helper: resolves which boot-apply behaviour is possible given
 * the current [CapabilityReport].
 *
 * Decision tree:
 *   1. If PServer-LIVE, root, Shizuku, OR the sysfs unlock-script chmod
 *      is in effect → [BootApplyMode.AUTO].  These paths survive boot and
 *      can perform a live write without user interaction.
 *   2. Else if the device has the VENDOR_SETTINGS privilege tier
 *      (Settings.System keys via the vendor path — does NOT survive reboot
 *      by itself; requires the app to open) → [BootApplyMode.REMINDER].
 *      This tier cannot write on its own at boot because it needs a live
 *      app context to call Settings.putString; the best we can do is nudge
 *      the user to open the app.
 *   3. Else → [BootApplyMode.UNSUPPORTED] (NONE tier — monitoring only).
 *
 * This function is pure (no I/O, no side effects) so it can be tested on
 * the JVM without an Android runtime.
 */
fun resolveBootApplyMode(report: CapabilityReport): BootApplyMode = when {
    // Live-write tiers that can act autonomously at boot:
    //   ROOT          — libsu/KernelSU shell, survives every reboot.
    //   SHIZUKU       — shell-UID binder (Shizuku service started on boot
    //                   by its own daemon on API 29+); app can bind at boot.
    //   pserverSysfsLive — AYN PServer whitelist confirmed (runs as root,
    //                   no per-boot chmod needed).
    //   sysfsDirectlyWritable — unlock script chmod-ed the nodes; they
    //                   remain chmod 666 until the firmware resets them
    //                   (typically not across reboot, but the unlock script
    //                   re-applies on boot for devices that run it in init.d).
    //                   We still class this AUTO: the write will either
    //                   succeed or silently produce WriteResult.CapabilityDenied,
    //                   which is then logged.  Honest: if the chmod reset,
    //                   the denial is visible in the tune history.
    report.privilege == PrivilegeTier.ROOT ||
        report.privilege == PrivilegeTier.SHIZUKU ||
        report.pserverSysfsLive ||
        report.sysfsDirectlyWritable -> BootApplyMode.AUTO

    // VENDOR_SETTINGS tier: the vendor Settings.System keys are accessible but
    // writing them requires a live app context to call Settings.putString (not
    // just a receiver context).  Post a reminder instead.
    report.privilege == PrivilegeTier.VENDOR_SETTINGS -> BootApplyMode.REMINDER

    // NONE tier or anything else: no write path at all at boot.
    else -> BootApplyMode.UNSUPPORTED
}
