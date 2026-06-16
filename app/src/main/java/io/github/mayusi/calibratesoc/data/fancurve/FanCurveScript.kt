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
     *
     * The script:
     *   1. base64-decodes [newXmlBase64] into a temp file in the same prefs dir,
     *      then atomically `mv`s it over config.xml (so a crash mid-write can't
     *      leave a truncated file the stock app would choke on),
     *   2. restores owner/group/mode to match a SharedPreferences file,
     *   3. kills com.odin.settings,
     *   4. bounces fan_mode 3 → 4.
     *
     * Each step is chained with `;` (not `&&`) for steps 2-4 so a benign failure
     * (e.g. process already dead → `kill` non-zero) does not abort the reload
     * bounce. The decode+mv (step 1) uses `&&` because writing a bad file then
     * reloading it would be worse than not writing at all.
     */
    fun buildApplyScript(newXmlBase64: String): String {
        val path = shellQuote(CONFIG_XML_PATH)
        val tmp = shellQuote("$CONFIG_XML_PATH.calibrate.tmp")
        val b64 = shellQuote(newXmlBase64)
        return buildString {
            // 1. Decode to temp then atomically replace. base64 -d reads stdin.
            append("printf %s ").append(b64).append(" | base64 -d > ").append(tmp)
            append(" && mv -f ").append(tmp).append(' ').append(path)
            // 2. Match SharedPreferences perms so the stock app can still read it.
            //    com.odin.settings is a system app; its de-storage prefs are
            //    owned by the package UID. We restore u+rw,g+r (0660) which is the
            //    framework default for prefs, and chown to the package via the
            //    `--reference` of the parent dir so we don't hardcode a UID.
            append("; chmod 660 ").append(path)
            append("; chown --reference=").append(shellQuote(prefsDir())).append(' ').append(path)
            append("; restorecon ").append(path)
            // 3. Drop the cached copy.
            append("; kill -9 $(pidof ").append(ODIN_SETTINGS_PKG).append(')')
            // 4. Bounce the mode to force a reload and return to Smart.
            append("; settings put system ").append(FAN_MODE_KEY).append(' ').append(FAN_MODE_BOUNCE_VIA)
            append("; settings put system ").append(FAN_MODE_KEY).append(' ').append(FAN_MODE_SMART)
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
}
