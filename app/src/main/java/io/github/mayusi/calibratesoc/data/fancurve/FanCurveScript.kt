package io.github.mayusi.calibratesoc.data.fancurve

/**
 * Pure builder for the privileged shell commands that apply / read a fan curve
 * on the Odin 3. NO Android, NO I/O — every method returns a self-contained
 * command string so the exact sequence is unit-testable.
 *
 * The commands are executed via the existing PServer root shell
 * ([io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter.executeShell]);
 * this object only assembles them.
 *
 * ## The apply sequence (reverse-engineered Odin facts)
 * Editing `config.xml` alone does NOT take effect — the stock service caches
 * it. The full, ordered procedure is:
 *
 *   1. Overwrite `config.xml` with the read-modify-written document (other prefs
 *      preserved — the RMW happens in Kotlin via [SharedPrefsXml], this builder
 *      only emits the write of the FINAL document).
 *   2. `kill -9 $(pidof com.odin.settings)` — drop the cached copy.
 *   3. `settings put system fan_mode 3 && settings put system fan_mode 4` —
 *      bounce the mode so the service reloads the curve and re-enters Smart
 *      mode (value 4). Bouncing WITHOUT the kill does not reload an externally
 *      edited curve.
 *
 * Writing the file: we cannot pass the multi-KB XML as a `printf` argument
 * cleanly (newlines, quoting). Instead the controller base64-encodes the new
 * XML in Kotlin and we decode it on-device with `base64 -d`, which is present
 * in the Odin's toybox. This is binary-safe and immune to shell-quoting issues
 * in the XML body. We then `chmod`/`chown` to match the original so the stock
 * app can still read it.
 */
object FanCurveScript {

    /** The Odin stock settings package whose prefs hold the curve. */
    const val ODIN_SETTINGS_PKG = "com.odin.settings"

    /** Absolute path to the SharedPreferences file holding the curve. */
    const val CONFIG_XML_PATH =
        "/data/user_de/0/com.odin.settings/shared_prefs/config.xml"

    /** SharedPreferences key whose value is the curve JSON string. */
    const val CURVE_PREF_KEY = "fan_temp_control_curve_point_key"

    /** Settings.System key selecting the fan mode. */
    const val FAN_MODE_KEY = "fan_mode"

    /** The value of [FAN_MODE_KEY] that engages the curve-driven Smart mode. */
    const val FAN_MODE_SMART = 4

    /** A different, valid-ish mode we bounce THROUGH to force a reload. Mode 3
     *  is only ever a transient stepping-stone — we always return to Smart (4).
     *  (Only 4=Smart is confirmed; 3 is used purely as a "not-4" bounce value,
     *  exactly as the reverse-engineered procedure specifies.) */
    const val FAN_MODE_BOUNCE_VIA = 3

    /** Command to read the current config.xml (for the read side / RMW). */
    fun readConfigCommand(): String = "cat ${shellQuote(CONFIG_XML_PATH)}"

    /**
     * Command to capture the ORIGINAL ownership + mode + SELinux context of
     * config.xml BEFORE we touch it, so the apply can restore EXACTLY those
     * afterwards (and not leave the stock app unable to read its own prefs).
     *
     * Emits a single line `user:group mode context` (e.g.
     * `system:system 660 u:object_r:system_app_data_file:s0`). `%C` is the
     * SELinux context on toybox/coreutils `stat`. We pad missing fields with `-`
     * so the line always has exactly three space-separated tokens; the parser
     * ([parseConfigMetadata]) treats `-` as "unknown".
     */
    fun readConfigMetadataCommand(): String {
        val path = shellQuote(CONFIG_XML_PATH)
        // Two stat invocations: one for user:group + octal mode, one for context.
        // `||` fallbacks keep the token count stable when a field is unavailable.
        return "stat -c '%U:%G %a' $path 2>/dev/null || echo '-:- -'; " +
            "stat -c '%C' $path 2>/dev/null || echo '-'"
    }

    /** Command to read the live fan duty node (verification). */
    fun readFanDutyCommand(): String = "cat /sys/class/gpio5_pwm2/duty"

    /** Command to read the live fan period node (verification). */
    fun readFanPeriodCommand(): String = "cat /sys/class/gpio5_pwm2/period"

    /** Command to read the live fan state node (verification). */
    fun readFanStateCommand(): String = "cat /sys/class/gpio5_pwm2/state"

    /** Command to read the current Settings.System fan_mode. */
    fun readFanModeCommand(): String = "settings get system $FAN_MODE_KEY"

    /**
     * Build the complete, ordered apply script as a single shell command string
     * suitable for one [PServerWriter.executeShell] call.
     *
     * @param newXmlBase64  the FINAL config.xml document (already read-modify-
     *   written in Kotlin), base64-encoded with NO line breaks.
     * @param original  the metadata (owner/group, mode, SELinux context) captured
     *   from the ORIGINAL config.xml BEFORE the write, so we restore EXACTLY what
     *   was there. When a field is unknown we fall back to a safe default
     *   (660 mode, sibling-dir owner, `restorecon`).
     *
     * The script is structured as a single `if … then … else exit 1 fi` so the
     * ONE status code returned by [PServerWriter.executeShell] honestly reflects
     * the outcome:
     *   1. base64-decode [newXmlBase64] into a temp file in the same prefs dir,
     *      then atomically `mv` it over config.xml (a crash mid-write can't leave
     *      a truncated file the stock app would choke on),
     *   2. RESTORE the captured owner/group, mode, and SELinux context — chained
     *      with `&&` so ANY failure aborts before the point of no return,
     *   3. ONLY IF all of the above succeeded: kill com.odin.settings and bounce
     *      fan_mode 3 → 4 to reload the curve and re-enter Smart mode.
     *
     * If the write OR the metadata restore fails, the script takes the `else`
     * branch, prints a marker, and `exit 1` — it does NOT kill the stock service
     * against a file whose metadata we couldn't restore (relaunching it against
     * an unreadable file would be worse than not applying at all). The controller
     * treats the non-zero status as a hard failure and reports it honestly.
     *
     * Within the kill+bounce tail (step 3) we keep `;` chaining: a benign failure
     * there (e.g. process already dead → `kill` non-zero) must not abort the
     * fan_mode bounce that re-engages Smart mode.
     */
    fun buildApplyScript(
        newXmlBase64: String,
        original: ConfigFileMetadata = ConfigFileMetadata.UNKNOWN,
    ): String {
        val path = shellQuote(CONFIG_XML_PATH)
        val tmp = shellQuote("$CONFIG_XML_PATH.calibrate.tmp")
        val b64 = shellQuote(newXmlBase64)

        // ── chown: prefer the captured original owner:group; else fall back to
        //    the sibling prefs-dir reference (matches the package UID without
        //    hardcoding it), exactly as before.
        val chown = if (original.ownerGroup != null) {
            "chown ${shellQuote(original.ownerGroup)} $path"
        } else {
            "chown --reference=${shellQuote(prefsDir())} $path"
        }
        // ── chmod: prefer the captured original mode; else the framework default
        //    660 for SharedPreferences.
        val mode = original.mode ?: "660"
        val chmod = "chmod ${shellQuote(mode)} $path"
        // ── SELinux: prefer setting the EXACT captured context; if `chcon` is
        //    unavailable or the captured context is unknown, fall back to
        //    `restorecon` so the policy relabels it from the parent dir. The
        //    inner `||` keeps a missing-tool case from spuriously failing the
        //    whole apply when relabelling still succeeds.
        val context = if (original.seContext != null) {
            "{ chcon ${shellQuote(original.seContext)} $path || restorecon $path; }"
        } else {
            "restorecon $path"
        }
        return buildString {
            append("if printf %s ").append(b64).append(" | base64 -d > ").append(tmp)
            append(" && mv -f ").append(tmp).append(' ').append(path)
            append(" && ").append(chmod)
            append(" && ").append(chown)
            append(" && ").append(context)
            append("; then ")
            // Point of no return reached safely — reload the service.
            append("kill -9 $(pidof ").append(ODIN_SETTINGS_PKG).append(')')
            append("; settings put system ").append(FAN_MODE_KEY).append(' ').append(FAN_MODE_BOUNCE_VIA)
            append("; settings put system ").append(FAN_MODE_KEY).append(' ').append(FAN_MODE_SMART)
            append("; else ")
            // Restore/write failed. Clean up the temp file and fail loudly —
            // do NOT touch the running service.
            append("rm -f ").append(tmp)
            append("; echo ").append(shellQuote(APPLY_FAILED_MARKER))
            append("; exit 1")
            append("; fi")
        }
    }

    /** Directory containing config.xml — used as a chown reference. */
    fun prefsDir(): String = CONFIG_XML_PATH.substringBeforeLast('/')

    /**
     * POSIX single-quote escaping for a shell argument. Identical in spirit to
     * PServerWriter.shellQuote / RootWriter.shellQuote. Safe to embed between
     * outer single quotes even when the input contains quotes or metacharacters.
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Printed by the apply script's failure branch so the controller can tell a
     *  metadata-restore abort apart from other non-zero exits if needed. */
    const val APPLY_FAILED_MARKER = "CALIBRATE_FANCURVE_APPLY_FAILED"

    /**
     * Parse the two-line stdout of [readConfigMetadataCommand] into structured
     * metadata. Line 1 is `user:group mode`; line 2 is the SELinux context.
     * A `-` token (our padding) or an unparsable value yields `null` for that
     * field so the apply falls back to a safe default.
     *
     * Tolerant: never throws. Returns [ConfigFileMetadata.UNKNOWN] when the
     * output is blank/missing entirely.
     */
    fun parseConfigMetadata(raw: String?): ConfigFileMetadata {
        if (raw.isNullOrBlank()) return ConfigFileMetadata.UNKNOWN
        val lines = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.getOrNull(0).orEmpty().split(Regex("\\s+"))
        val ownerGroupRaw = first.getOrNull(0)
        val modeRaw = first.getOrNull(1)
        val contextRaw = lines.getOrNull(1)

        // owner:group is valid only when it has the `u:g` shape and neither half
        // is our `-` placeholder.
        val ownerGroup = ownerGroupRaw
            ?.takeIf { it.contains(':') }
            ?.takeIf { part -> part.split(':').none { it.isBlank() || it == "-" } }

        // mode is a 3- or 4-digit octal string (e.g. 660 / 0660).
        val mode = modeRaw
            ?.takeIf { it != "-" && it.length in 3..4 && it.all { c -> c in '0'..'7' } }

        // context is a full SELinux label `u:object_r:...:s0`; reject placeholder
        // and anything that doesn't look like a label.
        val seContext = contextRaw
            ?.takeIf { it != "-" && it.count { c -> c == ':' } >= 2 && it.contains("object_r") }

        return ConfigFileMetadata(ownerGroup = ownerGroup, mode = mode, seContext = seContext)
    }
}

/**
 * Ownership + mode + SELinux context captured from the ORIGINAL config.xml so an
 * apply can restore EXACTLY what was there. A null field means "couldn't capture
 * it" and the apply falls back to a safe default (sibling-dir owner reference,
 * 660 mode, `restorecon`).
 */
data class ConfigFileMetadata(
    /** `user:group` (e.g. `system:system`), or null if uncaptured. */
    val ownerGroup: String?,
    /** Octal mode string (e.g. `660`), or null if uncaptured. */
    val mode: String?,
    /** Full SELinux context label, or null if uncaptured. */
    val seContext: String?,
) {
    companion object {
        /** Nothing captured — apply falls back to safe defaults for every field. */
        val UNKNOWN = ConfigFileMetadata(ownerGroup = null, mode = null, seContext = null)
    }
}
