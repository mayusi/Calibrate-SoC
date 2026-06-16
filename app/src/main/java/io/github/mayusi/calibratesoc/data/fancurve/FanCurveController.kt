package io.github.mayusi.calibratesoc.data.fancurve

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
 * It reuses the EXISTING privileged executor ([PServerWriter.executeShell]) —
 * no new root path is invented. All shell command text comes from the pure
 * [FanCurveScript] builder; the read-modify-write of config.xml happens in
 * Kotlin via [SharedPrefsXml] so every other Odin preference is preserved.
 *
 * HONESTY: [applyCurve] never reports success it didn't verify. If the readback
 * can't prove the apply landed it returns an UNVERIFIED result.
 */
@Singleton
class FanCurveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityProbe: CapabilityProbe,
    private val pServer: PServerWriter,
    private val store: FanCurveStore,
) {

    /** Resolve current availability from the cached/refreshed capability report. */
    suspend fun availability(): FanCurveAvailability {
        val report = capabilityProbe.report.first() ?: runCatching { capabilityProbe.refresh() }.getOrNull()
        return FanCurveGate.resolve(report, odinSettingsInstalled())
    }

    private fun odinSettingsInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(FanCurveScript.ODIN_SETTINGS_PKG, 0); true
    }.getOrDefault(false)

    /**
     * Read the curve currently stored in the device's config.xml.
     *
     * Returns [ReadCurveResult.Ok] with the parsed curve, or an honest error.
     * Requires privilege (the file lives in com.odin.settings's private dir);
     * gated on [availability] by the caller, but we also fail gracefully if the
     * privileged read returns nothing.
     */
    suspend fun readCurrentCurve(): ReadCurveResult = withContext(Dispatchers.IO) {
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

        // ── 1. Validate ─────────────────────────────────────────────────────
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

        // ── 2. Read current XML and read-modify-write the single pref ───────
        val currentXml = privilegedRead(FanCurveScript.readConfigCommand())
            ?: return@withContext ApplyResult.Failed(
                "Could not read the current config.xml to preserve your other Odin " +
                    "settings — refusing to overwrite the whole file.",
            )
        val newJson = FanCurveJson.serialize(curve)
        val newXml = when (val r = SharedPrefsXml.replaceString(currentXml, FanCurveScript.CURVE_PREF_KEY, newJson)) {
            is ReplaceResult.Replaced -> r.xml
            is ReplaceResult.Inserted -> r.xml
            ReplaceResult.NotPrefsFile -> return@withContext ApplyResult.Failed(
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
            return@withContext ApplyResult.Failed(
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
            return@withContext ApplyResult.Failed(
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
        when (verification) {
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
        val duty = privilegedRead(FanCurveScript.readFanDutyCommand())?.trim()?.toIntOrNull()
        val period = privilegedRead(FanCurveScript.readFanPeriodCommand())?.trim()?.toIntOrNull()
        val mode = privilegedRead(FanCurveScript.readFanModeCommand())?.trim()?.toIntOrNull()
        LiveFanReading(dutyRaw = duty, periodRaw = period, fanMode = mode)
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
         *  re-enter Smart mode. */
        const val SETTLE_MS = 1_200L
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
) {
    /** Duty as a 0..100 percentage when both nodes are readable, else null. */
    val dutyPct: Int?
        get() {
            val p = periodRaw ?: return null
            val d = dutyRaw ?: return null
            if (p <= 0) return null
            return ((d.toLong() * 100L) / p.toLong()).toInt().coerceIn(0, 100)
        }
}
