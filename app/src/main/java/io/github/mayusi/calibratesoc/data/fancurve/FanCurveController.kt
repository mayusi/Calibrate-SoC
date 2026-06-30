package io.github.mayusi.calibratesoc.data.fancurve

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient
import io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanBinderClient
import io.github.mayusi.calibratesoc.data.tunables.writer.retroid.RetroidFanConfig
import io.github.mayusi.calibratesoc.data.util.readSysfsString
import io.github.mayusi.calibratesoc.data.vendor.readFanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-FanCurve"

/**
 * The control surface for the Odin custom fan-curve feature. It:
 *   - resolves availability ([availability]) from the live capability report,
 *   - reads the current device curve ([readCurrentCurve]),
 *   - applies a new curve ([applyCurve]) via the read-modify-write → privileged
 *     write → kill → fan_mode-bounce sequence, then VERIFIES it,
 *   - reads the live fan duty for the UI readout ([readLiveFanDuty]).
 *
 * ## Per-vendor dispatch
 * The controller resolves the active [FanCurveVendor] from [availability] and
 * dispatches each operation to the matching strategy:
 *   - [FanCurveVendor.ODIN]   — the original path: read-modify-write config.xml
 *     via [PServerWriter.executeShell] + [FanCurveScript] + [SharedPrefsXml],
 *     then kill/bounce to reload, verified by [FanCurveVerifier].
 *   - [FanCurveVendor.AYANEO] — ZERO-SETUP: build the `com_set_fan_speed_strategy`
 *     command via [AyaneoFanCurveMapper], send it (fire-and-forget) through
 *     [AyaneoBinderClient], and verify HONESTLY by reading back the app-readable
 *     `pwm1` node ([AyaneoFanCurveVerifier]). No config.xml, no root, no script.
 *   - [FanCurveVendor.RETROID] — ZERO-SETUP: map the curve to ONE representative held
 *     SPEED via [RetroidFanSpeedMapper] (Retroid has no curve-array transaction — its
 *     FanProvider takes a single scalar), enter CUSTOM mode + set the speed through
 *     [RetroidFanBinderClient], and verify HONESTLY via the FanProvider readback
 *     ([RetroidFanVerifier]). HONEST that this is a fixed held speed, not a
 *     temp-reactive curve. No config.xml, no root, no script.
 *
 * The Odin path is unchanged — only vendor branches were added around it.
 *
 * HONESTY: [applyCurve] never reports success it didn't verify. On Odin an
 * UNVERIFIED result means the config readback couldn't prove the apply landed; on
 * AYANEO it means the binder accepted the command but the live PWM node couldn't
 * be read back. AYANEO NEVER reports `liveConfirmed = true` (no readback can prove
 * the exact temp-dependent curve to that standard).
 */
@Singleton
class FanCurveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityProbe: CapabilityProbe,
    private val pServer: PServerWriter,
    private val ayaneoBinder: AyaneoBinderClient,
    private val retroidBinder: RetroidFanBinderClient,
    private val fs: FileSystem,
    private val store: FanCurveStore,
) {

    /**
     * Resolve current availability from the cached/refreshed capability report.
     *
     * The gate is a pure function, so the two non-report inputs are supplied here:
     * whether the Odin settings package is installed, and whether the Retroid
     * FanProvider binder is live. The Retroid liveness is probed lazily and ONLY when
     * the device is actually a Retroid — on every other device we short-circuit to
     * false with no IPC, so non-Retroid devices never pay a bind cost.
     */
    suspend fun availability(): FanCurveAvailability {
        val report = capabilityProbe.report.first() ?: runCatching { capabilityProbe.refresh() }.getOrNull()
        val retroidLive = report != null && FanCurveGate.isRetroid(report) && retroidBinder.isAvailable()
        return FanCurveGate.resolve(report, odinSettingsInstalled(), retroidBinderLive = retroidLive)
    }

    /** The resolved vendor for the active device, or null when unavailable. */
    private suspend fun activeVendor(): FanCurveVendor? =
        (availability() as? FanCurveAvailability.Available)?.vendor

    private fun odinSettingsInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(FanCurveScript.ODIN_SETTINGS_PKG, 0); true
    }.getOrDefault(false)

    /**
     * Read the curve currently stored on the device.
     *
     * ODIN: read the curve JSON back out of config.xml (privileged read). AYANEO:
     * there is NO app-readable node that echoes the applied curve back — the curve
     * lives inside the overlay (uid=system) — so this honestly returns an error
     * explaining that, rather than fabricating a "current" curve. (The UI's
     * "Load device curve" affordance is hidden on AYANEO accordingly.)
     */
    suspend fun readCurrentCurve(): ReadCurveResult = withContext(Dispatchers.IO) {
        when (activeVendor()) {
            FanCurveVendor.AYANEO -> return@withContext ReadCurveResult.Error(
                "AYANEO applies the fan curve through its system overlay, which doesn't " +
                    "expose the active curve for reading back. Pick a preset or edit the " +
                    "curve and apply it instead.",
            )
            FanCurveVendor.RETROID -> return@withContext ReadCurveResult.Error(
                "Retroid holds a single custom fan speed through its vendor fan service " +
                    "(not a temperature curve), and doesn't expose a curve to read back. " +
                    "Pick a preset or edit the curve and apply it instead.",
            )
            else -> Unit // ODIN reads config.xml below.
        }
        val xml = privilegedRead(FanCurveScript.readConfigCommand())
            ?: return@withContext ReadCurveResult.Error(
                "Could not read the Odin fan-curve config (privileged read returned nothing).",
            )
        val rawValue = SharedPrefsXml.readString(xml, FanCurveScript.CURVE_PREF_KEY)
            ?: return@withContext ReadCurveResult.Error(
                "config.xml did not contain the '${FanCurveScript.CURVE_PREF_KEY}' key.",
            )
        when (val parsed = FanCurveJson.parse(rawValue)) {
            is FanCurveParse.Ok -> ReadCurveResult.Ok(parsed.curve)
            is FanCurveParse.Error -> ReadCurveResult.Error(
                "Stored curve JSON is malformed: ${parsed.reason}",
            )
        }
    }

    /**
     * Apply [curve] to the device.
     *
     * Steps:
     *   1. Validate the curve (reject invalid structurally). If it contains
     *      sub-floor duty points, require [allowSubFloor] = true.
     *   2. Read the current config.xml (privileged) and read-modify-write ONLY
     *      the curve pref in Kotlin, preserving all other keys.
     *   3. base64-encode the new XML and run the apply script (decode+replace →
     *      kill → fan_mode bounce) via the privileged shell.
     *   4. Settle briefly, re-read config.xml + the live PWM nodes, and verify.
     *   5. Persist the curve on a verified-or-unverified apply (so re-apply works),
     *      but NOT on a hard failure.
     *
     * @param allowSubFloor when true, a curve with points below the 20% safe
     *   minimum is permitted (the UI gates this behind an explicit opt-in +
     *   warning). When false, such a curve is rejected here as a safety stop.
     */
    suspend fun applyCurve(
        curve: FanCurve,
        allowSubFloor: Boolean,
    ): ApplyResult = withContext(Dispatchers.IO) {
        // ── 0. Availability gate ────────────────────────────────────────────
        val avail = availability()
        if (avail !is FanCurveAvailability.Available) {
            val reason = (avail as? FanCurveAvailability.Unavailable)?.reason ?: "Feature unavailable."
            return@withContext ApplyResult.Unavailable(reason)
        }

        // ── 1. Validate (VENDOR-NEUTRAL — the hard hot-zone cooling floor in
        //       FanCurve.validate() applies EQUALLY to AYANEO: an overheating
        //       curve can never reach the apply path on either vendor) ────────
        when (val v = curve.validate()) {
            is FanCurveValidation.Invalid -> return@withContext ApplyResult.Invalid(v.reason)
            FanCurveValidation.Valid -> Unit
        }
        val subFloor = curve.warnings().any { it is FanCurveWarning.SubFloorDuty }
        if (subFloor && !allowSubFloor) {
            return@withContext ApplyResult.Invalid(
                "This curve drops below the 20% safe minimum. Enable the sub-20% " +
                    "option explicitly to apply it.",
            )
        }

        // ── 2. Dispatch on the resolved vendor ──────────────────────────────
        when (avail.vendor) {
            FanCurveVendor.ODIN -> applyCurveOdin(curve)
            FanCurveVendor.AYANEO -> applyCurveAyaneo(curve)
            FanCurveVendor.RETROID -> applyCurveRetroid(curve)
        }
    }

    /**
     * AYANEO apply (ZERO-SETUP binder path). Builds the `com_set_fan_speed_strategy`
     * command + the linearity command via [AyaneoFanCurveMapper], sends BOTH through
     * [AyaneoBinderClient] (fire-and-forget), then reads back the app-readable `pwm1`
     * node and verifies HONESTLY via [AyaneoFanCurveVerifier].
     *
     * The curve was already validated (incl. the hard hot-zone cooling floor) by the
     * caller, so by here it is structurally safe to apply on AYANEO.
     */
    private suspend fun applyCurveAyaneo(curve: FanCurve): ApplyResult {
        val curveCmd = AyaneoFanCurveMapper.buildCurveCommand(curve)
        val linearCmd = AyaneoFanCurveMapper.buildLinearityCommand(linear = false)
        Log.i(TAG, "applyCurveAyaneo(): curve cmd='$curveCmd'")

        // Send the curve, then the interpolation mode. BOTH must be accepted at the
        // binder layer to call the apply "accepted". A failed send (binder down /
        // overlay refusal) is an honest hard failure — the curve was NOT applied.
        val curveAccepted = runCatching { ayaneoBinder.sendCommand(curveCmd) }.getOrElse { t ->
            Log.w(TAG, "applyCurveAyaneo(): curve send threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!curveAccepted) {
            return ApplyResult.Failed(
                "The AYANEO game-window service did not accept the fan curve (the binder " +
                    "was unavailable or the overlay refused it). Your curve was NOT applied.",
            )
        }
        // Linearity is secondary: if it's refused we still applied the curve, so we
        // don't fail the whole apply on it — but we DO log it. The curve command is
        // what carries the duty floor that matters for thermals.
        val linearAccepted = runCatching { ayaneoBinder.sendCommand(linearCmd) }.getOrElse { false }
        if (!linearAccepted) {
            Log.w(TAG, "applyCurveAyaneo(): linearity cmd not accepted (curve still applied)")
        }

        // Settle, then read back the live pwm1 node (app-readable on AYANEO — verified).
        delay(SETTLE_MS)
        val pwmRaw = fs.readSysfsString(AYANEO_PWM1_PATH.toPath())

        return when (val v = AyaneoFanCurveVerifier.verify(accepted = true, pwmReadback = pwmRaw)) {
            is FanCurveVerification.Applied -> {
                store.saveCustomCurve(curve)
                ApplyResult.Applied(
                    liveConfirmed = v.liveConfirmed, // always false on AYANEO (honest)
                    fanDuty = v.fanDuty,
                    fanPeriod = v.fanPeriod,
                )
            }
            is FanCurveVerification.Unverified -> {
                // Binder accepted but pwm1 unreadable — persist so re-apply works,
                // but report honestly that we couldn't confirm the fan node is active.
                store.saveCustomCurve(curve)
                ApplyResult.Unverified(v.reason)
            }
            // verify(accepted=true, …) never returns NotApplied; handle defensively.
            is FanCurveVerification.NotApplied -> ApplyResult.Failed(v.reason)
        }
    }

    /**
     * RETROID apply (ZERO-SETUP FanProvider-binder path). Retroid has NO curve-array
     * transaction — its `FanProvider` exposes a single `r(int speed)` scalar — so we
     * map the (already-validated) curve to ONE representative held SPEED via
     * [RetroidFanSpeedMapper] (which clamps UP to the hard safety floor), enter CUSTOM
     * mode (txn 5), set the speed (txn 7), settle, read the value back (txn 2), and
     * verify HONESTLY via [RetroidFanVerifier].
     *
     * HONESTY: this is a fixed held speed, NOT a temp-reactive curve like Odin/AYANEO.
     * The CUSTOM-mode integer + speed range are CALIBRATE-LIVE constants
     * ([RetroidFanConfig]); if the read-back proves the value didn't move (the most
     * likely cause being the wrong CUSTOM-mode int for this firmware), we report
     * Unverified with that exact reason — never a faked success.
     */
    private suspend fun applyCurveRetroid(curve: FanCurve): ApplyResult {
        val targetSpeed = RetroidFanSpeedMapper.curveToSpeed(curve)
        Log.i(TAG, "applyCurveRetroid(): repDuty=${RetroidFanSpeedMapper.representativeDutyPct(curve)}% → speed=$targetSpeed")

        // Snapshot the pre-apply fan value so the verifier can tell "moved toward
        // target" from "stuck at the previous/stock value" (the wrong-custom-mode signal).
        val previousValue = runCatching { retroidBinder.readFanValue() }.getOrNull()

        // 1) Enter CUSTOM mode so the manual speed takes effect (in Smart/auto the
        //    governor ignores r(int)). 2) Set the mapped, clamped speed.
        val modeAccepted = runCatching { retroidBinder.setMode(RetroidFanConfig.CUSTOM_MODE) }.getOrElse { t ->
            Log.w(TAG, "applyCurveRetroid(): setMode threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        val speedAccepted = runCatching { retroidBinder.setSpeed(targetSpeed) }.getOrElse { t ->
            Log.w(TAG, "applyCurveRetroid(): setSpeed threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }

        if (!modeAccepted && !speedAccepted) {
            return ApplyResult.Failed(
                "The Retroid fan service did not accept the custom-fan commands (the " +
                    "FanProvider binder was unavailable or refused them). Your fan setting " +
                    "was NOT applied.",
            )
        }

        // Settle, then read back the fan value to verify it moved to the target.
        delay(SETTLE_MS)
        val readBack = runCatching { retroidBinder.readFanValue() }.getOrNull()

        return when (
            val v = RetroidFanVerifier.verify(
                modeAccepted = modeAccepted,
                speedAccepted = speedAccepted,
                targetSpeed = targetSpeed,
                readBack = readBack,
                previousValue = previousValue,
            )
        ) {
            is FanCurveVerification.Applied -> {
                store.saveCustomCurve(curve)
                ApplyResult.Applied(
                    liveConfirmed = v.liveConfirmed, // true on Retroid when readback ≈ target
                    fanDuty = v.fanDuty,
                    fanPeriod = v.fanPeriod, // null on Retroid (no period node)
                )
            }
            is FanCurveVerification.Unverified -> {
                // Accepted but the readback couldn't confirm (most likely the CUSTOM-mode
                // int differs on this firmware — the live-calibration signal). Persist so
                // re-apply works after calibration, but report honestly.
                store.saveCustomCurve(curve)
                ApplyResult.Unverified(v.reason)
            }
            is FanCurveVerification.NotApplied -> ApplyResult.Failed(v.reason)
        }
    }

    /**
     * ODIN apply (the ORIGINAL config.xml path — unchanged). Read-modify-writes the
     * curve pref, runs the privileged apply script, settles, re-reads config.xml +
     * the live PWM nodes, and verifies via [FanCurveVerifier].
     */
    private suspend fun applyCurveOdin(curve: FanCurve): ApplyResult {
        // ── 2. Read current XML and read-modify-write the single pref ───────
        val currentXml = privilegedRead(FanCurveScript.readConfigCommand())
            ?: return ApplyResult.Failed(
                "Could not read the current config.xml to preserve your other Odin " +
                    "settings — refusing to overwrite the whole file.",
            )
        val newJson = FanCurveJson.serialize(curve)
        val newXml = when (val r = SharedPrefsXml.replaceString(currentXml, FanCurveScript.CURVE_PREF_KEY, newJson)) {
            is ReplaceResult.Replaced -> r.xml
            is ReplaceResult.Inserted -> r.xml
            ReplaceResult.NotPrefsFile -> return ApplyResult.Failed(
                "The Odin config file didn't look like a valid settings file — refusing " +
                    "to write to avoid corrupting it.",
            )
        }

        // ── 2b. Capture the ORIGINAL file metadata so we can restore it EXACTLY
        //        after the write (H1: never leave com.odin.settings unable to
        //        read its own prefs). Best-effort: a null capture falls back to
        //        the sibling-dir reference + 660 + restorecon inside the script.
        val metadata = FanCurveScript.parseConfigMetadata(
            privilegedRead(FanCurveScript.readConfigMetadataCommand()),
        )

        // ── 3. Encode + run the apply script ────────────────────────────────
        // Standard base64, NO_WRAP, so the device's toybox `base64 -d` decodes it.
        val b64 = Base64.encodeToString(newXml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = FanCurveScript.buildApplyScript(b64, metadata)
        val applyResult = pServer.executeShell(script)
        if (applyResult == null) {
            return ApplyResult.Failed(
                "The privileged write path did not respond (PServer unavailable). " +
                    "The curve was NOT applied.",
            )
        }
        Log.i(TAG, "applyCurve(): apply script status=${applyResult.first}")

        // The apply script is a single `if … then (reload) else exit 1 fi`. A
        // non-zero status means the write OR the metadata restore failed — in
        // which case the script did NOT kill/relaunch com.odin.settings. Treat
        // it as a hard failure and report honestly: we never claim "applied"
        // when we couldn't restore the file's ownership/mode/context, and we
        // never persist such a curve.
        if (applyResult.first != 0) {
            return ApplyResult.Failed(
                "The fan-curve write could not be completed safely (could not restore " +
                    "the config file's ownership/permissions/SELinux context, or the " +
                    "write itself failed). To avoid leaving the Odin settings service " +
                    "with a file it can't read, Calibrate aborted WITHOUT reloading it. " +
                    "Your curve was NOT applied and other settings are untouched.",
            )
        }

        // ── 4. Settle + verify ──────────────────────────────────────────────
        delay(SETTLE_MS)
        val configBack = privilegedRead(FanCurveScript.readConfigCommand())
            ?.let { SharedPrefsXml.readString(it, FanCurveScript.CURVE_PREF_KEY) }
        val dutyBack = privilegedRead(FanCurveScript.readFanDutyCommand())
        val periodBack = privilegedRead(FanCurveScript.readFanPeriodCommand())

        val verification = FanCurveVerifier.verify(
            intendedCurve = curve,
            configReadback = configBack,
            fanDutyReadback = dutyBack,
            fanPeriodReadback = periodBack,
        )

        // ── 5. Persist (only when the file is in place or unverified) ───────
        return when (verification) {
            is FanCurveVerification.Applied -> {
                store.saveCustomCurve(curve)
                ApplyResult.Applied(
                    liveConfirmed = verification.liveConfirmed,
                    fanDuty = verification.fanDuty,
                    fanPeriod = verification.fanPeriod,
                )
            }
            is FanCurveVerification.Unverified -> {
                // The write may have landed; persist so re-apply / re-verify works,
                // but tell the user honestly we couldn't confirm.
                store.saveCustomCurve(curve)
                ApplyResult.Unverified(verification.reason)
            }
            is FanCurveVerification.NotApplied ->
                ApplyResult.Failed(verification.reason)
        }
    }

    /** Live fan duty / period for the UI readout. Null fields on read failure. */
    suspend fun readLiveFanDuty(): LiveFanReading = withContext(Dispatchers.IO) {
        when (activeVendor()) {
            FanCurveVendor.AYANEO -> readLiveFanDutyAyaneo()
            FanCurveVendor.RETROID -> readLiveFanDutyRetroid()
            // ODIN (and the null/unavailable case) use the Odin gpio nodes.
            else -> {
                val duty = privilegedRead(FanCurveScript.readFanDutyCommand())?.trim()?.toIntOrNull()
                val period = privilegedRead(FanCurveScript.readFanPeriodCommand())?.trim()?.toIntOrNull()
                val mode = privilegedRead(FanCurveScript.readFanModeCommand())?.trim()?.toIntOrNull()
                LiveFanReading(dutyRaw = duty, periodRaw = period, fanMode = mode)
            }
        }
    }

    /**
     * AYANEO live readout: read the app-readable `pwm1` node (0..255) directly and
     * map it onto [LiveFanReading] so the SAME UI renders it. We put the raw PWM in
     * [LiveFanReading.dutyRaw] and [AyaneoPwm.PWM_MAX] (255) in [LiveFanReading.periodRaw]
     * so the existing [LiveFanReading.dutyPct] getter (`duty*100/period`) computes
     * EXACTLY the `pwm/255*100` percentage the brief specifies — no duplicate math.
     * AYANEO has no "fan_mode" node here, so [LiveFanReading.fanMode] is null.
     */
    private fun readLiveFanDutyAyaneo(): LiveFanReading {
        val rawPwm = fs.readSysfsString(AYANEO_PWM1_PATH.toPath())?.trim()?.toIntOrNull()
        return LiveFanReading(
            dutyRaw = rawPwm,
            periodRaw = rawPwm?.let { AyaneoPwm.PWM_MAX },
            fanMode = null,
        )
    }

    /**
     * RETROID live readout — HONEST.
     *
     * The FanProvider binder (txn 2 `b()`) returns the configured custom-SPEED SETPOINT
     * on the ~25000 scale (it returned the stock 25000 on the live probe), NOT the
     * governor's live actual fan duty — Retroid's vendor service exposes no live-duty
     * node. So we must NOT present `setpoint/SPEED_MAX` as the live "Fan duty": at idle
     * the fan runs at the Smart-mode governor's ~20% while the setpoint reads 25000,
     * which would otherwise show a fabricated 100%.
     *
     * Instead we:
     *   - put the setpoint in [LiveFanReading.dutyRaw] and [RetroidFanConfig.SPEED_MAX]
     *     in [LiveFanReading.periodRaw] so [LiveFanReading.dutyPct] is the setpoint as a
     *     percent of full scale, and
     *   - flag [LiveFanReading.dutyIsSetpoint] = true so the UI labels it "Custom speed
     *     (setpoint)" — never the live actual duty — and explains that in Smart/auto the
     *     governor controls the fan and the live duty isn't exposed, and
     *   - resolve the REAL fan mode from the vendor's `fan_mode` Settings.System key
     *     (public read, no root) so "Fan mode unknown" becomes the honest current mode.
     *
     * NOTE: this is a suspend binder call (not a sysfs read), so unlike the AYANEO/Odin
     * helpers it is itself suspending. Null fields on read failure (honest — no fake 0).
     */
    private suspend fun readLiveFanDutyRetroid(): LiveFanReading {
        val raw = runCatching { retroidBinder.readFanValue() }.getOrNull()
        val mode = runCatching {
            readFanMode(context.contentResolver, RETROID_FAN_MODE_KEY)?.trim()?.toIntOrNull()
        }.getOrNull()
        return LiveFanReading(
            dutyRaw = raw,
            periodRaw = raw?.let { RetroidFanConfig.SPEED_MAX },
            fanMode = mode,
            dutyIsSetpoint = true,
        )
    }

    /**
     * Restore the device's STOCK fan governor — the "turn custom fan off" path.
     *
     * ODIN/AYANEO already restore Smart/auto inside their apply/revert flows (Odin via
     * the fan_mode bounce, AYANEO via the overlay holding the last curve until a preset
     * is re-sent). On RETROID, custom mode HOLDS a fixed speed, so turning it off must
     * actively hand control back to the stock governor: set the fan mode back to
     * [RetroidFanConfig.SMART_MODE] (txn 5). Returns true when the restore command was
     * accepted at the binder layer. No-op (false) on non-Retroid vendors — they have
     * nothing held to restore here. Never throws.
     */
    suspend fun restoreAutoFan(): Boolean = withContext(Dispatchers.IO) {
        if (activeVendor() != FanCurveVendor.RETROID) return@withContext false
        runCatching { retroidBinder.setMode(RetroidFanConfig.SMART_MODE) }.getOrElse { t ->
            Log.w(TAG, "restoreAutoFan(): setMode(SMART) threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Lightweight "re-apply on app open" hook. When the user enabled it and a
     * curve is saved AND the feature is available, re-applies silently. Returns
     * the result (or null when nothing was attempted). Reuses [applyCurve], so
     * it inherits the same verification + honesty. Deliberately NOT a new
     * BootReceiver — the existing boot-apply path covers tunables; the fan curve
     * is re-asserted when the app is next opened, which is sufficient and avoids
     * over-engineering.
     */
    suspend fun maybeReapplyOnOpen(): ApplyResult? {
        if (!store.applyOnOpenNow()) return null
        val curve = store.savedCurveNow() ?: return null
        if (availability() !is FanCurveAvailability.Available) return null
        val allowSub = store.allowSubFloor.first()
        return applyCurve(curve, allowSubFloor = allowSub)
    }

    /**
     * Privileged read of a single file/setting via the existing PServer shell.
     * Returns the trimmed stdout, or null on circuit-break / non-zero status /
     * empty output.
     */
    private suspend fun privilegedRead(command: String): String? {
        val result = pServer.executeShell(command) ?: return null
        val (status, out) = result
        if (status != 0) return null
        return out.ifBlank { null }
    }

    private companion object {
        /** How long to wait after the apply script before re-reading the live
         *  PWM nodes — the controller needs a moment to re-read the curve and
         *  re-enter Smart mode. Also used by the AYANEO path before the pwm1
         *  readback, and by the RETROID path before the FanProvider txn-2 readback,
         *  so the vendor governor has settled the fan after the binder send. */
        const val SETTLE_MS = 1_200L

        /** The AYANEO hwmon PWM fan node — app-readable (verified live on the
         *  Pocket DS). Used for the AYANEO readback-verify + live readout. Same
         *  node the AYANEO writer matches for fan verification. */
        const val AYANEO_PWM1_PATH =
            "/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon0/pwm1"

        /** Retroid's vendor `fan_mode` Settings.System key (owned by
         *  com.rp.gameassistant). Public read (no root) — lets the live readout
         *  resolve the honest current mode (Off/Quiet/Smart/Sport/Custom) instead
         *  of "Fan mode unknown". Mode ints map via [retroidFanModeName]. */
        const val RETROID_FAN_MODE_KEY = "fan_mode"
    }
}

/** Result of [FanCurveController.readCurrentCurve]. */
sealed interface ReadCurveResult {
    data class Ok(val curve: FanCurve) : ReadCurveResult
    data class Error(val reason: String) : ReadCurveResult
}

/** Result of [FanCurveController.applyCurve]. */
sealed interface ApplyResult {
    /** Applied; [liveConfirmed] true when the live PWM nodes corroborated it. */
    data class Applied(
        val liveConfirmed: Boolean,
        val fanDuty: Int?,
        val fanPeriod: Int?,
    ) : ApplyResult

    /** The write ran but we couldn't verify it took. Honest, NOT a success. */
    data class Unverified(val reason: String) : ApplyResult

    /** A hard failure — the curve was not applied (or readback proved it didn't). */
    data class Failed(val reason: String) : ApplyResult

    /** The curve failed validation / safety gate; nothing was written. */
    data class Invalid(val reason: String) : ApplyResult

    /** The feature isn't available on this device. */
    data class Unavailable(val reason: String) : ApplyResult
}

/** Live fan node snapshot for the UI. */
data class LiveFanReading(
    val dutyRaw: Int?,
    val periodRaw: Int?,
    val fanMode: Int?,
    /**
     * HONESTY (Retroid): on Odin/AYANEO [dutyRaw]/[periodRaw] are a LIVE duty over a
     * period (gpio duty, or pwm1/255), so [dutyPct] is the actual fan duty. On Retroid
     * the only readable value (FanProvider txn 2) is the configured custom-SPEED
     * SETPOINT on the ~25000 scale — NOT the live actual fan duty, which the vendor
     * service does not expose. When true, [dutyPct] is the setpoint as a percent of
     * full scale (so the UI can label it "Custom speed (setpoint)" instead of falsely
     * claiming it is the live fan duty). Default false (Odin/AYANEO unchanged).
     */
    val dutyIsSetpoint: Boolean = false,
) {
    /**
     * Duty as a 0..100 percentage when both nodes are readable, else null.
     *
     * On Odin/AYANEO this is the live actual fan duty. On Retroid (`dutyIsSetpoint`)
     * it is the configured custom-speed SETPOINT as a percent of full scale — the
     * vendor binder cannot read the governor's live actual duty, so this honestly
     * reflects only what the setpoint is, NOT the real fan speed in Smart/auto mode.
     */
    val dutyPct: Int?
        get() {
            val p = periodRaw ?: return null
            val d = dutyRaw ?: return null
            if (p <= 0) return null
            return ((d.toLong() * 100L) / p.toLong()).toInt().coerceIn(0, 100)
        }
}
